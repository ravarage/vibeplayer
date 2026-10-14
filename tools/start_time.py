#!/usr/bin/env python3
"""P7-02: cold start time of the player. Usage: tools/start_time.py [N=10] [--threshold-ms 1000]

For each of N runs: force-stop com.vibeplayer, clear logcat, `am start -W` an ACTION_VIEW for
VIDEO, then read logcat (epoch timestamps, device clock) and compute
  intent_to_restart_ms = time of the first "VP-Perf: playback-restart" line
                         - time of ActivityTaskManager's "START u0 ... PlayerActivity" line
  total_time_ms        = `am start -W` TotalTime (activity launch only, not playback)
Prints every run and the median of intent_to_restart_ms; exits 1 if that median is above the
threshold (or if any run produced no playback-restart), 2 when there is no usable data.
Never clears app data. Needs the phone connected (plain adb, /home/rava/vibeplayer/tools/bin first
in PATH) and the test video on the phone.
"""
import os
import re
import statistics
import subprocess
import sys
import time

TOOLS = "/home/rava/vibeplayer/tools"
os.environ["PATH"] = TOOLS + "/bin:" + os.environ.get("PATH", "")
PKG = "com.vibeplayer"
VIDEO = "file:///sdcard/Movies/vibeplayer-test/h264_1080p.mp4"
COMPONENT = PKG + "/.ui.player.PlayerActivity"
LINE = re.compile(r"^\s*(\d{9,}\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*:\s(.*)$")


def run(cmd, timeout=30):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)


def adb(*args, timeout=30):
    return run(["adb", *args], timeout)


def wake():
    r = run(["bash", TOOLS + "/wake.sh"], timeout=30)
    if r.returncode != 0:
        sys.exit("phone did not wake: " + r.stdout + r.stderr)


def one_run():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    adb("logcat", "-c")
    r = adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", VIDEO,
            "-t", "video/*", "-n", COMPONENT, timeout=30)
    m = re.search(r"TotalTime:\s*(\d+)", r.stdout)
    total = int(m.group(1)) if m else None
    start_ts = restart_ts = None
    deadline = time.time() + 15
    while time.time() < deadline and restart_ts is None:
        time.sleep(0.5)
        out = adb("logcat", "-d", "-v", "epoch", "-b", "main,system,events", timeout=30).stdout
        for line in out.splitlines():
            m = LINE.match(line)
            if not m:
                continue
            ts, tag, msg = float(m.group(1)), m.group(3), m.group(4)
            if start_ts is None and "START u0" in msg and "PlayerActivity" in msg:
                start_ts = ts
            if "VP-Perf" in tag and msg.startswith("playback-restart") and restart_ts is None:
                restart_ts = ts
    if restart_ts is None:
        return total, None, "no playback-restart in logcat within 15 s"
    if start_ts is None:
        return total, None, "no ActivityTaskManager START line in logcat (cannot time the intent)"
    return total, round((restart_ts - start_ts) * 1000), ""


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 10
    threshold = 1000
    if "--threshold-ms" in sys.argv:
        threshold = int(sys.argv[sys.argv.index("--threshold-ms") + 1])
    wake()
    results, bad = [], 0
    for i in range(1, n + 1):
        try:
            total, latency, err = one_run()
        except subprocess.TimeoutExpired:
            total, latency, err = None, None, "adb timeout"
        if latency is None:
            bad += 1
        else:
            results.append(latency)
        print(f"run {i:2d}: intent->playback-restart = {latency if latency is not None else 'n/a'} ms,"
              f" am start -W TotalTime = {total if total is not None else 'n/a'} ms {err}".rstrip(),
              flush=True)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    if not results:
        print("no usable runs")
        return 2
    med = statistics.median(results)
    print(f"median intent->playback-restart over {len(results)} runs: {med:.0f} ms"
          f" (min {min(results)}, max {max(results)}); threshold {threshold} ms")
    if bad:
        print(f"{bad} run(s) without a measurement")
    return 1 if (med > threshold or bad) else 0


if __name__ == "__main__":
    sys.exit(main())
