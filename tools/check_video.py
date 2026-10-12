#!/usr/bin/env python3
"""Pixel check: is video actually visible on the phone screen?

Takes two raw screencaps 1 s apart and prints the share of non-black pixels and
whether the picture changed. Exit 0 = video visible, 1 = black screen, 2 = adb error.
Usage: tools/check_video.py [--still]   (--still: paused video, skip the motion check)
"""
import subprocess, sys, time

def grab():
    # Screenshot as PNG on the phone, then pull the file: `exec-out` streams hang on a slow
    # Wi-Fi adb link, and PNG is far smaller than the raw dump.
    from PIL import Image
    import os, tempfile
    remote = "/data/local/tmp/vp_check.png"
    local = os.path.join(tempfile.gettempdir(), f"vp_check_{os.getpid()}.png")
    try:
        subprocess.run(["adb", "shell", "screencap", "-p", remote], timeout=30, check=True)
        subprocess.run(["adb", "pull", remote, local], timeout=180, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        img = Image.open(local).convert("RGBA")
    except Exception as e:
        print(f"adb screenshot failed: {e}")
        sys.exit(2)
    w, h = img.size
    return w, h, img.tobytes()

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
