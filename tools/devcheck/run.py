#!/usr/bin/env python3
"""On-device regression check runner for Vibe Player.

  python3 tools/devcheck/run.py [--only a,b] [--skip a,b] [--out DIR] [--stall-opens N] [--list]

Needs the phone on adb (the tools/bin wrapper picks Wi-Fi or USB). Exit 0 only if nothing FAILED.
"""
import argparse
import atexit
import datetime
import os
import signal
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from devcheck import checks as C  # noqa: E402
from devcheck.ctx import Ctx, Fail, Skip  # noqa: E402
from devcheck.phone import Phone, REPO  # noqa: E402
from devcheck.safety import Refused  # noqa: E402

PER_CHECK_TIMEOUT = 300


def git_commit():
    try:
        return subprocess.run(["git", "-C", REPO, "rev-parse", "--short", "HEAD"], capture_output=True,
                              text=True, timeout=10).stdout.strip() or "?"
    except Exception:
        return "?"


class _Timeout(Exception):
    pass


def _alarm(signum, frame):
    raise _Timeout()


def run_check(ctx, name, fn, timeout):
    ctx.begin(name)
    signal.signal(signal.SIGALRM, _alarm)
    signal.alarm(timeout)
    t0 = time.time()
    try:
        reason = fn(ctx)
        status = "PASS"
    except Skip as e:
        status, reason = "SKIP", str(e)
    except Fail as e:
        status, reason = "FAIL", str(e)
    except Refused as e:
        status, reason = "FAIL", f"safety filter refused an adb command: {e}"
    except _Timeout:
        status, reason = "FAIL", f"check exceeded {timeout} s"
    except Exception as e:  # keep going: one broken check must not hide the others
        status, reason = "FAIL", f"harness error: {type(e).__name__}: {e}"
        traceback.print_exc()
    finally:
        signal.alarm(0)
    try:
        ctx.scan_crash()
    except Exception:
        pass
    if status == "FAIL":
        _save_failure_evidence(ctx, name, reason)
    return status, " ".join(str(reason).split()), time.time() - t0


def _save_failure_evidence(ctx, name, reason):
    """trace.txt (timed steps) and logcat.txt (the app's log since the check's launch) next to the screenshots."""
    d = os.path.join(ctx.outdir, name)
    try:
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "trace.txt"), "w") as f:
            f.write(f"FAIL: {reason}\n\n" + "\n".join(getattr(ctx, "trace_lines", [])) + "\n")
        ctx.log.refresh()
        with open(os.path.join(d, "logcat.txt"), "w") as f:
            f.write(f"{ctx.log.raw_count} lines in the buffer; the app's last 800:\n")
            f.write("\n".join(l.raw for l in ctx.log.lines[-800:]) + "\n")
    except Exception as e:
        print(f"    (could not save failure evidence: {e})")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", default="")
    ap.add_argument("--skip", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--stall-opens", type=int, default=5)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args(argv)
    names = [c[0] for c in C.CHECKS]
    if a.list:
        for n, d, _ in C.CHECKS:
            print(f"{n:22s} {d}")
        return 0
    only = [x for x in a.only.split(",") if x]
    skip = {x for x in a.skip.split(",") if x}
    unknown = [x for x in only + sorted(skip) if x not in names]
    if unknown:
        print("unknown check(s): " + ", ".join(unknown) + "\nknown: " + ", ".join(names))
        return 2
    selected = [c for c in C.CHECKS if (not only or c[0] in only) and c[0] not in skip]
    out = a.out or os.path.join("/tmp/vp_devcheck", datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
    os.makedirs(out, exist_ok=True)

    phone = Phone()
    if not phone.connected():
        print("no phone reachable over adb (tried the wrapper's Wi-Fi then USB)")
        return 2
    if not phone.wake():
        print("phone did not wake")
        return 2
    ctx = Ctx(phone, out)
    ctx.stall_opens = a.stall_opens
    ver, dbg = phone.app_info()
    env_lines = [("app version", f"{ver} ({'debug' if dbg else 'release'})"), ("git commit", git_commit()),
                 ("vulkan.adreno.so sha256[:16]", phone.vulkan_hash()),
                 ("started", datetime.datetime.now().isoformat(timespec="seconds"))]
    vol0 = phone.music_volume()
    env_lines.append(("media volume at start", str(vol0)))

    def restore():
        try:
            if vol0 is not None and phone.music_volume() != vol0:
                phone.set_music_volume(vol0)
            phone.force_stop()
            phone.key("KEYCODE_HOME")
        except Exception:
            pass

    atexit.register(restore)
    results = []
    print(f"devcheck -> {out}")
    for k, v in env_lines:
        print(f"  {k}: {v}")
    for name, desc, fn in selected:
        timeout = PER_CHECK_TIMEOUT + (60 * a.stall_opens if name == "stall" else 0)
        print(f"... {name}", flush=True)
        status, reason, dt = run_check(ctx, name, fn, timeout)
        results.append((name, status, reason, dt, list(ctx.evidence)))
        print(f"    {status}  {reason}  ({dt:.0f}s)", flush=True)
    restore()
    final_vol = phone.music_volume()
    env_lines.append(("media volume at end", f"{final_vol} ({'restored' if final_vol == vol0 else 'NOT restored'})"))
    write_summary(out, env_lines, results)
    fails = [r for r in results if r[1] == "FAIL"]
    if vol0 is not None and final_vol != vol0:
        fails.append(("volume", "FAIL", "", 0, []))
    print()
    print_table(results)
    print(f"\nsummary: {os.path.join(out, 'summary.md')}")
    print("MANUAL (not automated): " + ", ".join(m[1] for m in C.MANUAL) + " - see summary.md")
    return 1 if fails else 0


def print_table(results):
    w = max([len(r[0]) for r in results] + [5])
    for name, status, reason, dt, _ in results:
        print(f"{name:<{w}}  {status:<4}  {reason}")
    c = {s: sum(1 for r in results if r[1] == s) for s in ("PASS", "FAIL", "SKIP")}
    print(f"\n{c['PASS']} passed, {c['FAIL']} failed, {c['SKIP']} skipped")


def write_summary(out, env_lines, results):
    L = ["# devcheck summary", ""]
    L += [f"- {k}: {v}" for k, v in env_lines]
    L += ["", "| check | result | reason | evidence |", "|---|---|---|---|"]
    for name, status, reason, dt, ev in results:
        e = ", ".join(os.path.relpath(p, out) for p in ev[:4]) + (" ..." if len(ev) > 4 else "")
        L.append(f"| {name} | {status} | {reason.replace('|', '/')} | {e} |")
    L += ["", "## Manual checks (never faked)", ""]
    for _, title, how in C.MANUAL:
        L.append(f"- **{title}**: {how}")
    with open(os.path.join(out, "summary.md"), "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
