#!/usr/bin/env python3
"""Pixel check: is video actually visible on the phone screen?

Takes two raw screencaps 1 s apart and prints the share of non-black pixels and
whether the picture changed. Exit 0 = video visible, 1 = black screen, 2 = adb error.
Usage: tools/check_video.py [--still]   (--still: paused video, skip the motion check)
"""
import subprocess, sys, time

def grab():
    raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True, timeout=30).stdout
    if len(raw) < 16:
        sys.exit(2)
    w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
    return w, h, raw[len(raw) - w * h * 4:]

def stats(w, h, px):
    lit, sample = 0, []
    for y in range(0, h, 16):
        for x in range(0, w, 16):
            i = (y * w + x) * 4
            r, g, b = px[i], px[i + 1], px[i + 2]
            sample.append((r, g, b))
            if r + g + b > 48:
                lit += 1
    return lit / len(sample), sample

w, h, a = grab()
lit, sa = stats(w, h, a)
moving = None
if "--still" not in sys.argv:
    time.sleep(1.0)
    _, _, b = grab()
    _, sb = stats(w, h, b)
    diff = sum(1 for p, q in zip(sa, sb) if abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2]) > 24)
    moving = diff / len(sa)
print(f"screen {w}x{h} non_black={lit:.1%}" + ("" if moving is None else f" changed={moving:.1%}"))
ok = lit > 0.03 and (moving is None or moving > 0.002)
print("VIDEO VISIBLE" if ok else "BLACK OR FROZEN SCREEN: video is not being shown")
sys.exit(0 if ok else 1)
