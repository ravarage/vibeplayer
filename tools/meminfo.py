#!/usr/bin/env python3
"""P7-07: read-only memory snapshot of com.vibeplayer.

Usage: tools/meminfo.py <label> [--csv docs/meminfo.csv] [--pkg com.vibeplayer]
Prints TOTAL PSS, Native Heap, Graphics (EGL/GL mtrack), Dalvik (all KB, from
`adb shell dumpsys meminfo`) and appends a labelled row to the CSV. Never changes the phone.
"""
import argparse, csv, datetime, os, re, subprocess, sys

os.environ["PATH"] = "/home/rava/vibeplayer/tools/bin:" + os.environ.get("PATH", "")


def dumpsys(pkg):
    r = subprocess.run(["adb", "shell", "dumpsys", "meminfo", pkg],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        sys.exit("adb failed: " + r.stderr.strip())
    return r.stdout


def parse(text):
    """Return dict of KB values; missing entries are None."""
    def first_int(pattern):
        m = re.search(pattern, text, re.M)
        return int(m.group(1)) if m else None
    # Summary table lines look like "  Native Heap:   253000" ; table rows "Native Heap  253000 ..."
    total = first_int(r"^\s*TOTAL PSS:\s+(\d+)") or first_int(r"^\s*TOTAL\s+(\d+)")
    native = first_int(r"^\s*Native Heap:\s+(\d+)") or first_int(r"^\s*Native Heap\s+(\d+)")
    dalvik = first_int(r"^\s*Dalvik Heap:\s+(\d+)") or first_int(r"^\s*Dalvik Heap\s+(\d+)")
    graphics = first_int(r"^\s*Graphics:\s+(\d+)")
    gl = first_int(r"^\s*(?:EGL|GL) mtrack\s+(\d+)")
    return {"total_pss_kb": total, "native_heap_kb": native, "graphics_kb": graphics,
            "gl_mtrack_kb": gl, "dalvik_kb": dalvik}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--csv", default=os.path.join(os.path.dirname(__file__), "..", "docs", "meminfo.csv"))
    ap.add_argument("--pkg", default="com.vibeplayer")
    a = ap.parse_args()
    v = parse(dumpsys(a.pkg))
    if v["total_pss_kb"] is None:
        sys.exit("no TOTAL PSS found (is %s running?)" % a.pkg)
    for k, x in v.items():
        print("%-15s %s" % (k, "?" if x is None else "%d KB (%.0f MB)" % (x, x / 1024)))
    row = {"time": datetime.datetime.now().isoformat(timespec="seconds"), "label": a.label, **v}
    new = not os.path.exists(a.csv)
    with open(a.csv, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(row))
        if new:
            w.writeheader()
        w.writerow(row)


if __name__ == "__main__":
    main()
