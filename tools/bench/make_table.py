#!/usr/bin/env python3
"""Turn benchmark results into the per-format configuration table (TASKS.md P6-06 / P6-07).

The app reads the result, player/src/main/assets/format_table.json, before opening a video. The table is
keyed by the Vulkan driver hash (first 16 hex digits of sha256 of /vendor/lib64/hw/vulkan.adreno.so):
re-benchmark a new driver build, run this script, rebuild; no code change.

Inputs (one of):
  --markdown docs/BENCHMARK.md   a "## Power comparison (..., driver <hash>)" section with a table that has
                                 the columns Format, VO, Renderer, HWDEC, Steady Current (mA),
                                 Steady Drops and CPU %
  --csv benchmark.csv            rows written by the debug benchmark runner (BenchmarkModels.CSV_HEADER)
plus tools/bench/table_notes.json (rule tolerances, clip -> format, exclusions, fixed rows).

The user's rule (2026-10-14): smoothest playback first, then lowest battery, then lowest CPU; ties go to
the lower-power option. Concretely, per format:
  1. smooth set = rows whose steady drops are within dropTolerance (relative) of the fewest drops;
  2. of those, rows whose current is within currentTieTolerance (relative) of the lowest current;
  3. of those, lowest CPU; equal CPU goes to the lower current.
A Vulkan winner also gets a "gles" block: the same rule over the OpenGL ES rows, used when Vulkan is not
available on the phone.

Every row also gets an "operatingRate" policy (P6-08 fix): "auto" = the app passes MediaCodec an operating
rate of about 2 x the clip's frame rate (OperatingRate.kt), "off" = no hint. Rows that never decode with
MediaCodec (hwdec "no", also in their "gles" block) get "off"; the others get the policy from
table_notes.json "operatingRate" (per format, else its "default").

Usage:
  tools/bench/make_table.py --markdown docs/BENCHMARK.md --out player/src/main/assets/format_table.json
"""

import argparse
import csv
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
DEFAULT_NOTES = os.path.join(HERE, "table_notes.json")
DEFAULT_OUT = os.path.join(REPO, "player", "src", "main", "assets", "format_table.json")
FORMAT_ORDER = ["h264_8bit", "h264_10bit", "hevc", "vp9", "av1", "other"]
RULE_TEXT = ("smoothest first (steady drops within {drop:.0%} of the fewest count as equal), then lowest "
             "battery current (within {cur:.0%} counts as equal), then lowest CPU; ties go to lower power")


class Measurement:
    def __init__(self, fmt, renderer, vo, hwdec, drops, current_ma, cpu, clip=""):
        self.format = fmt
        self.renderer = renderer
        self.vo = vo
        self.hwdec = hwdec
        self.drops = drops
        self.current_ma = current_ma
        self.cpu = cpu
        self.clip = clip

    def label(self):
        name = {"mediacodec": "zero-copy", "mediacodec-copy": "copy-back", "no": "software"}.get(self.hwdec, self.hwdec)
        api = "Vulkan" if self.renderer == "vulkan" else "GLES"
        return f"{api} {self.vo} {name}"

    def __repr__(self):
        return f"Measurement({self.format}, {self.label()}, drops={self.drops}, {self.current_ma} mA, cpu={self.cpu})"


# ---------------------------------------------------------------------------------------------- parsing

def _cell(s):
    return s.strip().strip("`").strip()


def _num(s):
    m = re.search(r"-?\d+(?:\.\d+)?", s.replace(",", ""))
    if not m:
        raise ValueError(f"no number in {s!r}")
    return float(m.group(0))


def normalize_renderer(s):
    s = _cell(s).lower()
    if s.startswith("vulkan") or s == "vk":
        return "vulkan"
    if s in ("gles", "opengl", "opengl es", "gl"):
        return "gles"
    raise ValueError(f"unknown renderer {s!r}")


def normalize_hwdec(s):
    s = _cell(s).lower()
    if s.startswith("software") or s in ("no", "sw", "software"):
        return "no"
    if s in ("mediacodec", "mediacodec-copy"):
        return s
    raise ValueError(f"unknown hwdec {s!r}")


def format_from_label(label):
    """Format column of the markdown table, e.g. 'H.264 1080p', 'HEVC 10-bit 4K'."""
    l = label.lower()
    if l.startswith("h.264") or l.startswith("h264") or l.startswith("avc"):
        return "h264_10bit" if "10-bit" in l or "hi10p" in l else "h264_8bit"
    if l.startswith("hevc") or l.startswith("h.265"):
        return "hevc"
    if l.startswith("vp9"):
        return "vp9"
    if l.startswith("av1"):
        return "av1"
    return None


def parse_markdown(text, notes, heading_prefix="## Power comparison"):
    """Returns (driver_hash, measurements) of the first section whose heading starts with heading_prefix."""
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith(heading_prefix)), None)
    if start is None:
        raise ValueError(f"no section starting with {heading_prefix!r}")
    m = re.search(r"driver\s+`?([0-9a-f]{16})`?", lines[start])
    driver_hash = m.group(1) if m else None
    header = None
    out = []
    for line in lines[start + 1:]:
        if line.startswith("## ") or line.startswith("### "):
            break
        if not line.startswith("|"):
            if header is not None and out:
                break
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if header is None:
            header = [c.lower() for c in cells]
            continue
        if all(set(c) <= set("-: ") for c in cells):
            continue
        row = dict(zip(header, cells))
        clip = _cell(row.get("clip", ""))
        config = row.get("configuration", "").lower()
        if "(ctrl)" in clip or "repeat" in config:
            continue  # control rows measure drift, not a configuration
        clip_name = clip.split()[0] if clip else ""
        fmt = notes.get("clips", {}).get(clip_name) or format_from_label(row.get("format", ""))
        if fmt is None:
            continue
        out.append(Measurement(
            fmt=fmt,
            renderer=normalize_renderer(row["renderer"]),
            vo=_cell(row["vo"]),
            hwdec=normalize_hwdec(row["hwdec"]),
            drops=int(_num(row["steady drops"])),
            current_ma=abs(_num(row["steady current (ma)"])),
            cpu=_num(row["cpu %"]),
            clip=clip_name,
        ))
    if not out:
        raise ValueError("no table rows in the section")
    return driver_hash, out


def parse_csv(text, notes, allow_charging=False):
    """Rows of the debug benchmark runner's summary CSV. Returns (driver_hash, measurements)."""
    reader = csv.DictReader(text.splitlines())
    out = []
    hashes = set()
    for row in reader:
        if not allow_charging and row.get("charging", "false").strip().lower() == "true":
            continue  # current is not comparable while charging
        clip = os.path.basename(row["file"].strip())
        fmt = notes.get("clips", {}).get(clip)
        if fmt is None:
            continue
        decoder = row.get("decoder", "auto").strip().lower()
        mode = row.get("mode", "quality").strip().lower()
        if decoder in ("software", "sw", "no") or fmt == "av1":
            hwdec = "no"  # hwdec-codecs leaves AV1 to dav1d
        elif mode == "efficient":
            hwdec = "mediacodec"
        else:
            hwdec = "mediacodec-copy"
        current = abs(float(row["battery_current_avg"]))
        if current > 20000:  # raw BatteryManager microamperes
            current /= 1000.0
        h = row.get("driver_hash", "").strip()
        if re.fullmatch(r"[0-9a-f]{16}", h):
            hashes.add(h)
        out.append(Measurement(
            fmt=fmt,
            renderer=normalize_renderer(row["renderer"]),
            vo=row["vo"].strip(),
            hwdec=hwdec,
            drops=int(float(row["steady_drops"])),
            current_ma=current,
            cpu=float(row["cpu_percent_avg"]),
            clip=clip,
        ))
    if len(hashes) > 1:
        raise ValueError(f"CSV mixes driver builds {sorted(hashes)}; filter it to one")
    return (hashes.pop() if hashes else None), out


# ------------------------------------------------------------------------------------------------- rule

def choose(rows, drop_tol, cur_tol):
    """The user's rule. Returns the winning Measurement or None."""
    if not rows:
        return None
    fewest = min(r.drops for r in rows)
    smooth = [r for r in rows if r.drops <= fewest * (1.0 + drop_tol)]
    lowest = min(r.current_ma for r in smooth)
    frugal = [r for r in smooth if r.current_ma <= lowest * (1.0 + cur_tol)]
    return min(frugal, key=lambda r: (r.cpu, r.current_ma, r.drops))


def is_excluded(m, notes):
    for ex in notes.get("exclude", []):
        if all(getattr(m, k, None) == v for k, v in ex.items() if k != "reason"):
            return ex.get("reason", "excluded")
    return None


def _reason(winner, others, tag):
    rest = sorted((o for o in others if o is not winner), key=lambda o: (o.drops, o.current_ma))
    alt = "; ".join(f"{o.label()} {o.drops} drops {o.current_ma:.0f} mA" for o in rest[:2])
    text = f"{tag}: {winner.drops} drops, {winner.current_ma:.0f} mA, {winner.cpu:.0f}% CPU"
    return f"{text} (vs {alt})" if alt else text


def build_rows(measurements, notes, tag):
    rule = notes.get("rule", {})
    drop_tol = float(rule.get("dropTolerance", 0.05))
    cur_tol = float(rule.get("currentTieTolerance", 0.02))
    rows = {}
    for fmt in FORMAT_ORDER:
        all_rows = [m for m in measurements if m.format == fmt]
        eligible = [m for m in all_rows if not is_excluded(m, notes)]
        winner = choose(eligible, drop_tol, cur_tol)
        if winner is None:
            continue
        row = {
            "hwdec": winner.hwdec,
            "renderer": winner.renderer,
            "vo": winner.vo,
            "reason": _reason(winner, eligible, tag),
        }
        if winner.renderer == "vulkan":
            gles_rows = [m for m in eligible if m.renderer == "gles"]
            g = choose(gles_rows, drop_tol, cur_tol)
            if g is not None:
                gles_vo = "gpu" if g.vo == "gpu-next" else g.vo
                row["gles"] = {"hwdec": g.hwdec, "vo": gles_vo, "reason": _reason(g, gles_rows, tag + " GLES")}
            else:
                row["gles"] = {"hwdec": "mediacodec-copy" if winner.hwdec != "no" else "no", "vo": "gpu",
                               "reason": "no OpenGL ES measurement; same decode on GLES"}
        excluded = [is_excluded(m, notes) for m in all_rows]
        notes_text = sorted({e for e in excluded if e})
        if notes_text:
            row["reason"] += "; excluded " + "; ".join(notes_text)
        rows[fmt] = row
    for fmt, fixed in notes.get("fixedRows", {}).items():
        rows.setdefault(fmt, dict(fixed))
    for fmt, row in rows.items():
        row.setdefault("operatingRate", operating_rate_policy(fmt, row, notes))
    return rows


POLICIES = ("auto", "off")


def operating_rate_policy(fmt, row, notes):
    """The row's "operatingRate" token: "off" for software-only rows, else the notes' policy."""
    hwdecs = {row.get("hwdec"), (row.get("gles") or {}).get("hwdec", row.get("hwdec"))}
    if hwdecs <= {"no"}:
        return "off"
    cfg = notes.get("operatingRate", {})
    policy = cfg.get("formats", {}).get(fmt, cfg.get("default", "auto"))
    if policy not in POLICIES:
        raise ValueError(f"operatingRate policy {policy!r} for {fmt}: expected one of {POLICIES}")
    return policy


def make_table(driver_hash, measurements, notes, source, existing=None):
    """Builds the JSON document. Tables for other driver hashes in `existing` are kept; the default (used
    when the phone's driver hash matches no table) is the newest measurement."""
    if not driver_hash:
        raise ValueError("driver hash unknown: pass --driver-hash")
    rows = build_rows(measurements, notes, f"P6 {driver_hash[:8]}")
    tables = {}
    for t in (existing or {}).get("tables", []):
        tables[t["driverHash"]] = t
    tables[driver_hash] = {"driverHash": driver_hash, "source": source, "rows": rows}
    rule = notes.get("rule", {})
    return {
        "version": 1,
        "rule": RULE_TEXT.format(drop=float(rule.get("dropTolerance", 0.05)),
                                 cur=float(rule.get("currentTieTolerance", 0.02))),
        "tables": [tables[k] for k in sorted(tables)],
        "default": {"source": f"copy of driver {driver_hash}", "rows": rows},
    }


def dumps(doc):
    return json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--markdown", help="BENCHMARK.md with a '## Power comparison' section")
    src.add_argument("--csv", help="benchmark.csv from the debug runner")
    ap.add_argument("--section", default="## Power comparison", help="heading prefix of the markdown section")
    ap.add_argument("--notes", default=DEFAULT_NOTES)
    ap.add_argument("--driver-hash", help="overrides the hash found in the input")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fresh", action="store_true", help="drop tables of other driver builds from --out")
    ap.add_argument("--allow-charging", action="store_true")
    ap.add_argument("--check", action="store_true", help="exit 1 if --out differs from the generated table")
    args = ap.parse_args(argv)

    with open(args.notes, encoding="utf-8") as f:
        notes = json.load(f)
    if args.markdown:
        with open(args.markdown, encoding="utf-8") as f:
            found_hash, ms = parse_markdown(f.read(), notes, args.section)
        source = f"{os.path.basename(args.markdown)}: {args.section.lstrip('# ')}"
    else:
        with open(args.csv, encoding="utf-8") as f:
            found_hash, ms = parse_csv(f.read(), notes, args.allow_charging)
        source = os.path.basename(args.csv)
    driver_hash = args.driver_hash or found_hash
    existing = None
    if not args.fresh and os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            existing = json.load(f)
    text = dumps(make_table(driver_hash, ms, notes, source, existing))
    if args.check:
        current = open(args.out, encoding="utf-8").read() if os.path.exists(args.out) else ""
        if current != text:
            print(f"{args.out} is out of date", file=sys.stderr)
            return 1
        return 0
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {args.out} (driver {driver_hash}, {len(ms)} measurements)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
