#!/usr/bin/env python3
"""Host benchmark matrix runner for VibePlayer.

Runs predefined or custom benchmark matrices over adb, waits for completion,
pulls samples and summary CSVs, and appends markdown results to docs/BENCHMARK.md.

Ensures:
- tools/bin is first in PATH (uses adb wrapper)
- tools/wake.sh is run before each row
- Timeout on every adb call
- Never clears app data
- Refuses to run a power-sensitive matrix while charging unless --allow-charging is set
- Resumes after interruption by skipping rows already present in the device CSV
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any

# Ensure tools/bin is first in PATH so the adb wrapper is used
TOOLS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOLS_BIN = os.path.join(TOOLS_DIR, "bin")
if os.path.isdir(TOOLS_BIN):
    os.environ["PATH"] = f"{TOOLS_BIN}:{os.environ.get('PATH', '')}"

REMOTE_DIR = "/sdcard/Android/data/com.vibeplayer/files"
REMOTE_SUMMARY_CSV = f"{REMOTE_DIR}/benchmark.csv"
REMOTE_SAMPLES_CSV = f"{REMOTE_DIR}/benchmark_samples.csv"
DEFAULT_MARKDOWN_DOC = os.path.abspath(os.path.join(TOOLS_DIR, "..", "docs", "BENCHMARK.md"))


@dataclass
class MatrixRow:
    file: str
    mode: str = "quality"
    vo: str = "gpu-next"
    renderer: str = "gles"
    shader: str = "none"
    seconds: int = 60
    cache: str = "warm"
    profile: str = "default"
    decoder: str = "auto"

    def to_dict(self) -> dict[str, Any]:
        return {
            "file": self.file,
            "filename": os.path.basename(self.file),
            "mode": self.mode,
            "vo": self.vo,
            "renderer": self.renderer,
            "shader": self.shader,
            "seconds": self.seconds,
            "cache": self.cache,
            "profile": self.profile,
            "decoder": self.decoder,
        }


def get_predefined_matrix(name: str) -> list[MatrixRow]:
    """Returns the predefined list of matrix rows for a given matrix name."""
    name = name.lower()
    rows: list[MatrixRow] = []

    if name in ("short", "p6-04"):
        # (a) P6-04 short matrix: {Vulkan, GLES} x {gpu-next, gpu} x {ichiban.mp4, hevc10_4k.mp4, an AV1 1080p clip} 5 min each
        clips = [
            "/sdcard/Movies/vibeplayer-test/ichiban.mp4",
            "/sdcard/Movies/vibeplayer-test/hevc10_4k.mp4",
            "/sdcard/Movies/vibeplayer-bench/av1_1080p60_10bit.mp4",
        ]
        renderers = ["vulkan", "gles"]
        vos = ["gpu-next", "gpu"]
        for clip in clips:
            for rend in renderers:
                for vo in vos:
                    rows.append(
                        MatrixRow(
                            file=clip,
                            mode="quality",
                            vo=vo,
                            renderer=rend,
                            shader="none",
                            seconds=300,
                            cache="warm",
                            profile="default",
                            decoder="auto",
                        )
                    )

    elif name in ("av1", "av1_attribution"):
        # (b) AV1 attribution: av1_4k.mp4 and 3 bench clips x {gpu, gpu-next, gpu-next+profile=fast} x {Vulkan, GLES} 60 s each
        clips = [
            "/sdcard/Movies/vibeplayer-test/av1_4k.mp4",
            "/sdcard/Movies/vibeplayer-bench/av1_1080p60_10bit.mp4",
            "/sdcard/Movies/vibeplayer-bench/av1_4k30_10bit.mp4",
            "/sdcard/Movies/vibeplayer-bench/av1_4k60_8bit.mp4",
        ]
        configs = [
            ("gpu", "default"),
            ("gpu-next", "default"),
            ("gpu-next", "fast"),
        ]
        renderers = ["vulkan", "gles"]
        for clip in clips:
            for rend in renderers:
                for vo, prof in configs:
                    rows.append(
                        MatrixRow(
                            file=clip,
                            mode="quality",
                            vo=vo,
                            renderer=rend,
                            shader="none",
                            seconds=60,
                            cache="warm",
                            profile=prof,
                            decoder="auto",
                        )
                    )

    elif name in ("stability", "p6-05"):
        # (c) P6-05 stability: 30 min hevc10_4k.mp4 per renderer
        clip = "/sdcard/Movies/vibeplayer-test/hevc10_4k.mp4"
        for rend in ["vulkan", "gles"]:
            rows.append(
                MatrixRow(
                    file=clip,
                    mode="quality",
                    vo="gpu-next",
                    renderer=rend,
                    shader="none",
                    seconds=1800,
                    cache="warm",
                    profile="default",
                    decoder="auto",
                )
            )

    elif name in ("shaders", "p8-04"):
        # (d) P8-04 shaders: 5 min per preset on a 1080p clip. Vulkan: Anime4K is Vulkan-only since 0.7.0
        # (on OpenGL ES the presets load nothing, so a GLES row would measure no shader at all).
        clip = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"
        presets = ["none", "anime4k_a", "anime4k_b", "anime4k_c", "snapdragon_gsr", "fsrcnnx", "ravu"]
        for preset in presets:
            rows.append(
                MatrixRow(
                    file=clip,
                    mode="quality",
                    vo="gpu-next",
                    renderer="vulkan",
                    shader=preset,
                    seconds=300,
                    cache="warm",
                    profile="default",
                    decoder="auto",
                )
            )

    else:
        raise ValueError(f"Unknown predefined matrix: '{name}'. Available: short, av1_attribution, stability, shaders")

    return rows


def parse_benchmark_csv(csv_text: str) -> list[dict[str, str]]:
    """Parses a CSV string into a list of row dictionaries."""
    if not csv_text or not csv_text.strip():
        return []
    reader = csv.DictReader(io.StringIO(csv_text.strip()))
    return [dict(row) for row in reader]


def should_skip_row(row: MatrixRow, existing_rows: list[dict[str, str]]) -> bool:
    """Returns True if the matrix row was already completed in existing summary CSV."""
    filename = os.path.basename(row.file)
    for ex in existing_rows:
        ex_file = os.path.basename(ex.get("file", ""))
        ex_mode = ex.get("mode", "").lower()
        ex_vo = ex.get("vo", "").lower()
        ex_rend = ex.get("renderer", "").lower()
        ex_shader = ex.get("shader", "").lower()
        ex_cache = ex.get("shader_cache", "").lower()
        ex_prof = ex.get("profile", "default").lower()
        ex_dec = ex.get("decoder", "auto").lower()
        try:
            ex_sec = int(ex.get("requested_seconds", ex.get("seconds", 0)))
        except ValueError:
            ex_sec = 0

        if (
            ex_file == filename
            and ex_mode == row.mode.lower()
            and ex_vo == row.vo.lower()
            and ex_rend == row.renderer.lower()
            and ex_shader == row.shader.lower()
            and (not ex_cache or ex_cache == row.cache.lower())
            and ex_prof == row.profile.lower()
            and ex_dec == row.decoder.lower()
            and abs(ex_sec - row.seconds) <= 3
        ):
            return True
    return False


def compute_summary_from_samples(
    samples: list[dict[str, Any]],
    timestamp: str,
    run_id: str,
    file_path: str,
    mode: str,
    vo: str,
    renderer: str,
    profile: str,
    decoder: str,
    shader: str,
    shader_cache: str,
    requested_seconds: int,
    loops: int,
    battery_temp_start: float,
    battery_temp_end: float,
    charging: bool,
    vulkan_api: str,
    vulkan_device: str,
    driver_hash: str,
    driver_mtime: str,
) -> dict[str, Any]:
    """Pure helper to derive the summary row from per-second sample dictionaries."""
    if not samples:
        return {
            "timestamp": timestamp,
            "run_id": run_id,
            "file": os.path.basename(file_path),
            "mode": mode,
            "vo": vo,
            "renderer": renderer,
            "profile": profile,
            "decoder": decoder,
            "shader": shader,
            "shader_cache": shader_cache,
            "requested_seconds": requested_seconds,
            "actual_seconds": 0,
            "loops": loops,
            "startup_drops": 0,
            "steady_drops": 0,
            "total_drops": 0,
            "startup_decoder_drops": 0,
            "steady_decoder_drops": 0,
            "total_decoder_drops": 0,
            "mistimed_frames": 0,
            "vo_delayed_frames": 0,
            "estimated_vf_fps": 0.0,
            "container_fps": 0.0,
            "display_fps": 0.0,
            "cpu_percent_avg": 0.0,
            "battery_current_avg": 0.0,
            "battery_temp_start": battery_temp_start,
            "battery_temp_end": battery_temp_end,
            "charging": charging,
            "vulkan_api_version": vulkan_api,
            "vulkan_device_name": vulkan_device,
            "driver_hash": driver_hash,
            "driver_mtime": driver_mtime,
        }

    last = samples[-1]
    total_drops = int(last.get("frame_drop_count", 0))
    total_dec_drops = int(last.get("decoder_frame_drop_count", 0))

    # Startup drops: drops at elapsed_seconds <= 2
    startup_samples = [s for s in samples if int(s.get("elapsed_seconds", 0)) <= 2]
    startup_drops = int(startup_samples[-1].get("frame_drop_count", 0)) if startup_samples else total_drops
    startup_drops = min(startup_drops, total_drops)
    steady_drops = max(0, total_drops - startup_drops)

    startup_dec_drops = int(startup_samples[-1].get("decoder_frame_drop_count", 0)) if startup_samples else total_dec_drops
    startup_dec_drops = min(startup_dec_drops, total_dec_drops)
    steady_dec_drops = max(0, total_dec_drops - startup_dec_drops)

    cpus = [float(s.get("cpu_percent", 0.0)) for s in samples]
    avg_cpu = sum(cpus) / len(cpus) if cpus else 0.0

    currents = [float(s.get("battery_current", 0.0)) for s in samples]
    avg_current = sum(currents) / len(currents) if currents else 0.0

    vf_fps = [float(s.get("estimated_vf_fps", 0.0)) for s in samples if float(s.get("estimated_vf_fps", 0.0)) > 0]
    avg_vf_fps = sum(vf_fps) / len(vf_fps) if vf_fps else float(last.get("estimated_vf_fps", 0.0))

    return {
        "timestamp": timestamp,
        "run_id": run_id,
        "file": os.path.basename(file_path),
        "mode": mode,
        "vo": vo,
        "renderer": renderer,
        "profile": profile,
        "decoder": decoder,
        "shader": shader,
        "shader_cache": shader_cache,
        "requested_seconds": requested_seconds,
        "actual_seconds": len(samples),
        "loops": loops,
        "startup_drops": startup_drops,
        "steady_drops": steady_drops,
        "total_drops": total_drops,
        "startup_decoder_drops": startup_dec_drops,
        "steady_decoder_drops": steady_dec_drops,
        "total_decoder_drops": total_dec_drops,
        "mistimed_frames": int(last.get("mistimed_frame_count", 0)),
        "vo_delayed_frames": int(last.get("vo_delayed_frame_count", 0)),
        "estimated_vf_fps": avg_vf_fps,
        "container_fps": float(last.get("container_fps", 0.0)),
        "display_fps": float(last.get("display_fps", 0.0)),
        "cpu_percent_avg": avg_cpu,
        "battery_current_avg": avg_current,
        "battery_temp_start": battery_temp_start,
        "battery_temp_end": battery_temp_end,
        "charging": charging,
        "vulkan_api_version": vulkan_api,
        "vulkan_device_name": vulkan_device,
        "driver_hash": driver_hash,
        "driver_mtime": driver_mtime,
    }


def format_markdown_table(
    rows: list[dict[str, Any]],
    matrix_name: str,
    driver_hash: str,
    driver_mtime: str,
    start_battery_level: int,
    charging: bool,
) -> str:
    """Formats benchmark results as a markdown table suitable for docs/BENCHMARK.md."""
    date_str = time.strftime("%Y-%m-%d %H:%M:%S")
    charge_str = "Yes (charging)" if charging else "No (on battery)"

    md = []
    md.append(f"\n## Benchmark Matrix: {matrix_name.upper()} ({date_str})\n")
    md.append(f"- **Vulkan Driver Build**: `{driver_hash}` (mtime: `{driver_mtime}`)")
    md.append(f"- **Initial Battery**: {start_battery_level}%")
    md.append(f"- **Charging Status**: {charge_str}\n")

    md.append(
        "| File | Mode | VO | Renderer | Profile | Decoder | Shader | Cache | Sec | Drops (Start/Steady/Total) | Dec Drops (Start/Steady/Total) | FPS | CPU % | Avg Current (mA) | Temp Start/End (°C) |"
    )
    md.append(
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    )

    for r in rows:
        filename = r.get("file", "")
        mode = r.get("mode", "")
        vo = r.get("vo", "")
        rend = r.get("renderer", "")
        prof = r.get("profile", "default")
        dec = r.get("decoder", "auto")
        shader = r.get("shader", "none")
        cache = r.get("shader_cache", "warm")
        sec = r.get("actual_seconds", r.get("requested_seconds", ""))
        drops = f"{r.get('startup_drops', 0)} / {r.get('steady_drops', 0)} / {r.get('total_drops', 0)}"
        dec_drops = f"{r.get('startup_decoder_drops', 0)} / {r.get('steady_decoder_drops', 0)} / {r.get('total_decoder_drops', 0)}"
        fps = f"{float(r.get('estimated_vf_fps', 0.0)):.1f}"
        cpu = f"{float(r.get('cpu_percent_avg', 0.0)):.1f}%"
        # current is typically in uA; convert to mA for readability
        raw_cur = float(r.get("battery_current_avg", 0.0))
        cur_ma = f"{raw_cur / 1000.0:.0f}" if abs(raw_cur) > 10000 else f"{raw_cur:.0f}"
        t_start = f"{float(r.get('battery_temp_start', 0.0)):.1f}"
        t_end = f"{float(r.get('battery_temp_end', 0.0)):.1f}"

        md.append(
            f"| `{filename}` | {mode} | {vo} | {rend} | {prof} | {dec} | {shader} | {cache} | {sec} | {drops} | {dec_drops} | {fps} | {cpu} | {cur_ma} | {t_start} → {t_end} |"
        )

    md.append("\n")
    return "\n".join(md)


def run_adb(cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[str]:
    """Runs an adb command with a strict timeout using the adb wrapper in tools/bin."""
    full_cmd = ["adb"] + cmd
    return subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout, check=True)


def wake_device() -> None:
    """Runs tools/wake.sh with a timeout."""
    wake_sh = os.path.join(TOOLS_DIR, "wake.sh")
    if os.path.isfile(wake_sh):
        subprocess.run([wake_sh], timeout=15, check=True)


def check_battery_status() -> tuple[bool, int]:
    """Queries dumpsys battery to check if device is charging and battery level."""
    res = run_adb(["shell", "dumpsys", "battery"], timeout=15)
    is_charging = False
    level = -1
    for line in res.stdout.splitlines():
        line = line.strip()
        if line.startswith("AC powered: true") or line.startswith("USB powered: true") or line.startswith("Wireless powered: true"):
            is_charging = True
        if line.startswith("status: 2"):  # BATTERY_STATUS_CHARGING
            is_charging = True
        if line.startswith("level:"):
            parts = line.split(":")
            if len(parts) > 1:
                try:
                    level = int(parts[1].strip())
                except ValueError:
                    pass
    return is_charging, level


def get_driver_hash_and_mtime() -> tuple[str, str]:
    """Extracts SHA-256 (first 16 hex digits) and mtime of the Vulkan driver file."""
    try:
        res = run_adb(["shell", "sha256sum /vendor/lib64/hw/vulkan.adreno.so"], timeout=15)
        out = res.stdout.strip().split()
        full_hash = out[0] if out else "unreadable"
        short_hash = full_hash[:16]
    except Exception:
        short_hash = "unreadable"

    try:
        res = run_adb(["shell", "stat -c %y /vendor/lib64/hw/vulkan.adreno.so 2>/dev/null || date -Iseconds"], timeout=15)
        mtime = res.stdout.strip()
    except Exception:
        mtime = "unknown"

    return short_hash, mtime


def get_remote_csv_content(remote_path: str) -> str:
    """Reads remote CSV file content via adb shell cat."""
    try:
        res = run_adb(["shell", f"cat {remote_path} 2>/dev/null"], timeout=15)
        return res.stdout
    except Exception:
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Run VibePlayer benchmark matrix")
    parser.add_argument(
        "--matrix",
        choices=["short", "av1_attribution", "stability", "shaders"],
        default="short",
        help="Predefined benchmark matrix to execute",
    )
    parser.add_argument(
        "--allow-charging",
        action="store_true",
        help="Allow running power-sensitive benchmark matrix while charging (normally refused)",
    )
    parser.add_argument(
        "--markdown-doc",
        default=DEFAULT_MARKDOWN_DOC,
        help="Path to markdown document to append results to (default: docs/BENCHMARK.md)",
    )
    parser.add_argument(
        "--out-dir",
        default=".",
        help="Local directory to pull the CSVs into",
    )
    parser.add_argument(
        "--mpv-opts",
        default="",
        help="Debug A/B: extra mpv options for every row, ';'-separated (e.g. 'vd-queue-enable=yes;hwdec-extra-frames=12'); "
        "only names in DebugMpvOptions.ALLOWED are applied; recorded in the CSV profile column as '<profile>+<opts>'",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print matrix rows and actions without executing on device",
    )
    args = parser.parse_args()

    matrix_rows = get_predefined_matrix(args.matrix)
    print(f"Loaded matrix '{args.matrix}': {len(matrix_rows)} total rows")

    if args.dry_run:
        print("\n[Dry Run] Rows to run:")
        for idx, r in enumerate(matrix_rows, 1):
            print(f"  {idx:02d}. {os.path.basename(r.file)} | {r.mode} | {r.vo} | {r.renderer} | prof={r.profile} | dec={r.decoder} | shader={r.shader} | cache={r.cache} | {r.seconds}s")
        return 0

    # 1. Check Charging Status
    is_charging, start_battery = check_battery_status()
    if is_charging and not args.allow_charging:
        sys.stderr.write(
            "\nERROR: Device is charging (AC/USB powered: true).\n"
            "Power-sensitive benchmark measurements require running on battery to measure valid discharge currents.\n"
            "Unplug the phone, or pass --allow-charging if you intend to benchmark on power.\n\n"
        )
        return 1

    # 2. Driver Identity
    driver_hash, driver_mtime = get_driver_hash_and_mtime()
    print(f"Driver identity: hash={driver_hash}, mtime={driver_mtime}")

    # 3. Read Existing CSV for Resume-Skip
    existing_csv_text = get_remote_csv_content(REMOTE_SUMMARY_CSV)
    existing_rows = parse_benchmark_csv(existing_csv_text)
    print(f"Existing rows on device: {len(existing_rows)}")

    # 4. Execute Matrix Rows
    completed_in_session: list[dict[str, Any]] = []

    for idx, row in enumerate(matrix_rows, 1):
        if should_skip_row(row, existing_rows):
            print(f"[{idx}/{len(matrix_rows)}] SKIP (already present): {os.path.basename(row.file)} {row.renderer} {row.vo} {row.profile} ({row.seconds}s)")
            continue

        print(f"\n[{idx}/{len(matrix_rows)}] RUNNING: {os.path.basename(row.file)} {row.renderer} {row.vo} {row.profile} ({row.seconds}s)...")

        # Wake screen before each row
        wake_device()

        # Count lines in remote CSV before run
        current_csv = get_remote_csv_content(REMOTE_SUMMARY_CSV)
        prev_count = len(parse_benchmark_csv(current_csv))

        run_id = f"matrix_{args.matrix}_{int(time.time())}_{idx}"
        cold_str = "true" if row.cache == "cold" else "false"

        broadcast_args = [
            "shell", "am", "broadcast",
            "-a", "com.vibeplayer.BENCHMARK",
            "-n", "com.vibeplayer/.BenchmarkReceiver",
            "--es", "path", row.file,
            "--ei", "seconds", str(row.seconds),
            "--es", "mode", row.mode,
            "--es", "renderer", row.renderer,
            "--es", "vo", row.vo,
            "--es", "shader", row.shader,
            "--ez", "cold", cold_str,
            "--es", "cache", row.cache,
            "--es", "profile", row.profile,
            "--es", "decoder", row.decoder,
            "--es", "driver_hash", driver_hash,
            "--es", "driver_mtime", driver_mtime,
            "--es", "run_id", run_id,
        ]
        if args.mpv_opts:
            # adb shell joins the arguments into one remote command line: quote the ';'-separated list.
            broadcast_args += ["--es", "mpv_opts", shlex.quote(args.mpv_opts)]

        run_adb(broadcast_args, timeout=30)

        # Wait for completion: watch the CSV on the device with a timeout
        wait_timeout = row.seconds + 45
        start_wait = time.time()
        completed = False

        while time.time() - start_wait < wait_timeout:
            time.sleep(3)
            latest_csv = get_remote_csv_content(REMOTE_SUMMARY_CSV)
            latest_rows = parse_benchmark_csv(latest_csv)
            if len(latest_rows) > prev_count:
                completed = True
                new_row = latest_rows[-1]
                completed_in_session.append(new_row)
                existing_rows.append(new_row)
                print(f"[{idx}/{len(matrix_rows)}] COMPLETED: drops={new_row.get('total_drops')} fps={new_row.get('estimated_vf_fps')} cpu={new_row.get('cpu_percent_avg')}%")
                break

        if not completed:
            sys.stderr.write(f"WARNING: Timeout waiting for row {idx} to complete ({wait_timeout}s)\n")

    # 5. Pull Both CSVs
    os.makedirs(args.out_dir, exist_ok=True)
    local_summary = os.path.join(args.out_dir, "benchmark.csv")
    local_samples = os.path.join(args.out_dir, "benchmark_samples.csv")

    try:
        run_adb(["pull", REMOTE_SUMMARY_CSV, local_summary], timeout=60)
        run_adb(["pull", REMOTE_SAMPLES_CSV, local_samples], timeout=120)
        print(f"\nPulled summary CSV to {local_summary}")
        print(f"Pulled samples CSV to {local_samples}")
    except Exception as e:
        sys.stderr.write(f"Failed to pull CSVs: {e}\n")

    # 6. Append Markdown Table to docs/BENCHMARK.md
    if completed_in_session and os.path.isfile(args.markdown_doc):
        md_table = format_markdown_table(
            rows=completed_in_session,
            matrix_name=args.matrix,
            driver_hash=driver_hash,
            driver_mtime=driver_mtime,
            start_battery_level=start_battery,
            charging=is_charging,
        )
        with open(args.markdown_doc, "a", encoding="utf-8") as f:
            f.write(md_table)
        print(f"Appended {len(completed_in_session)} results to {args.markdown_doc}")

    print("\nBenchmark matrix run complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
