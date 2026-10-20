"""Check context: launching clips, waiting on logs, screenshots, UI taps, folder ordering."""
import functools
import os
import re
import time
import urllib.parse

from . import imgx, uix
from .logs import LogView, scan_crashes
from .safety import PKG

TEST_DIR = "/sdcard/Movies/vibeplayer-test"
PLAYER = ".ui.player.PlayerActivity"
VIDEO_EXT = {"mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "3gp", "ogv"}
PAUSE_TXT, PLAY_TXT = "❚❚", "▶"


class Fail(Exception):
    pass


class Skip(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise Fail(msg)


def natural_cmp(a, b):
    """Mirror of FolderPlaylist.NaturalOrder: case-insensitive, digit runs compared as numbers."""
    i = j = 0
    while i < len(a) and j < len(b):
        ca, cb = a[i], b[j]
        if ca.isdigit() and cb.isdigit():
            ei = i
            while ei < len(a) and a[ei].isdigit():
                ei += 1
            ej = j
            while ej < len(b) and b[ej].isdigit():
                ej += 1
            na, nb = a[i:ei].lstrip("0"), b[j:ej].lstrip("0")
            if len(na) != len(nb):
                return len(na) - len(nb)
            if na != nb:
                return -1 if na < nb else 1
            i, j = ei, ej
        else:
            la, lb = ca.lower(), cb.lower()
            if la != lb:
                return -1 if la < lb else 1
            i += 1
            j += 1
    return (len(a) - i) - (len(b) - j)


def folder_order(names):
    vids = [n for n in names if n.rsplit(".", 1)[-1].lower() in VIDEO_EXT and "." in n]
    return sorted(vids, key=functools.cmp_to_key(natural_cmp))


def file_uri(path):
    return "file://" + urllib.parse.quote(path, safe="/")


class Ctx:
    def __init__(self, phone, outdir, sleep=time.sleep):
        self.phone = phone
        self.outdir = outdir
        self.sleep = sleep
        self.log = LogView(phone.logcat_recent, phone.pids)
        self.crashes = []
        self.cur = "init"
        self.evidence = []
        self._size = None
        self._dp = None
        self._order = None
        self._shot_n = 0

    # -- bookkeeping ----------------------------------------------------------------------------
    def begin(self, name):
        self.cur = name
        self.evidence = []
        self._shot_n = 0
        self._t0 = time.time()
        self.trace_lines = []
        os.makedirs(os.path.join(self.outdir, name), exist_ok=True)

    def trace(self, msg):
        """A timestamped step, written to <out>/<check>/trace.txt when the check fails."""
        self.trace_lines.append(f"{time.time() - getattr(self, '_t0', time.time()):7.1f}s  {msg}")

    @property
    def dp(self):
        if self._dp is None:
            self._dp = self.phone.density_dp()
        return self._dp

    def dpx(self, v):
        return int(v * self.dp)

    def size(self):
        if self._size is None:
            w, h = self.phone.wm_size()
            self._size = (max(w, h), min(w, h))  # the player is always landscape for the test clips
        return self._size

    def shot(self, label="s"):
        self._shot_n += 1
        path = os.path.join(self.outdir, self.cur, f"{self._shot_n:02d}_{label}.png")
        img = self.phone.screenshot(path)
        self._size = (max(img.size), min(img.size))
        self.evidence.append(path)
        return img

    def nodes(self, retries=2):
        for _ in range(retries):
            ns = uix.parse_dump(self.phone.ui_xml())
            if ns:
                root = ns[0].bounds
                if root[2] > 0 and root[3] > 0:
                    self._size = (root[2], root[3])
                return ns
            self.sleep(0.6)
        return []

    def scan_crash(self):
        txt = self.phone.logcat_dump() + "\n" + self.phone.crash_buffer()
        for c in scan_crashes(txt):
            if c not in self.crashes:
                self.crashes.append(f"[{self.cur}] {c}")

    def clear_log(self):
        self.log.refresh()
        self.scan_crash()
        self.phone.logcat_clear()
        self.log.reset()

    # -- app control ----------------------------------------------------------------------------
    def stop_app(self):
        try:
            self.log.refresh()
        except Exception:
            pass
        self.phone.force_stop()
        self.sleep(0.5)

    def launch_clip(self, name, extras=""):
        self.ctrl_pinned = False
        self._ctrl_until = 0.0
        """Cold start of the player on a test clip. Returns the log mark taken before the launch."""
        self.phone.wake()
        self.stop_app()
        self.clear_log()
        mark = self.log.mark()
        self.phone.start_activity(PLAYER, data=file_uri(f"{TEST_DIR}/{name}"), extras=extras)
        return mark

    def launch_main(self):
        self.phone.wake()
        self.stop_app()
        self.clear_log()
        mark = self.log.mark()
        self.phone.start_activity(".MainActivity")
        return mark

    def wait_restart(self, since, timeout=20):
        return self.log.wait(r"playback-restart", timeout, since=since, tag="VP-Perf")

    def play_clip(self, name, extras="", timeout=20, settle=2.0):
        mark = self.launch_clip(name, extras)
        need(self.wait_restart(mark, timeout), f"no playback-restart within {timeout} s of launching {name}")
        self.sleep(settle)
        return mark

    def order(self):
        if self._order is None:
            self._order = folder_order(self.phone.listing(TEST_DIR))
        return self._order

    def last_value(self, pattern, since=0, tag=None, group=1):
        self.log.refresh()
        hits = self.log.find_all(pattern, since, tag)
        return hits[-1][1].group(group) if hits else None

    def duration_s(self, since=0):
        c = self.last_value(r"dur_ms=(\d+)", since, tag="VP-Check")
        if c and int(c) > 0:
            return int(c) / 1000.0
        v = self.last_value(r"onPropertyChange: duration = ([\d.]+)", since)
        if v:
            return float(v)
        d = self.last_value(r"Saved position \d+ ms \(dur=(\d+) ms", since)
        return int(d) / 1000.0 if d else None

    # -- picture ---------------------------------------------------------------------------------
    def motion(self, shots=3, gap=0.8):
        """(best non-black ratio, max changed ratio, images) over consecutive screenshots."""
        imgs = []
        for i in range(shots):
            imgs.append(self.shot(f"m{i}"))
            if i < shots - 1:
                self.sleep(gap)
        nb = max(imgx.nonblack_ratio(i) for i in imgs)
        ch = max(imgx.changed_ratio(imgs[i], imgs[i + 1]) for i in range(len(imgs) - 1))
        return nb, ch, imgs

    def need_moving(self, what="picture"):
        nb, ch, _ = self.motion()
        need(nb > 0.03, f"{what} is black (non_black={nb:.1%})")
        need(ch > 0.002, f"{what} is frozen (changed={ch:.2%}, non_black={nb:.1%})")
        return nb, ch

    def need_still(self, what="picture"):
        nb, ch, _ = self.motion(2, 1.0)
        need(nb > 0.03, f"{what} is black while paused")
        need(ch <= 0.002, f"{what} still changes while paused (changed={ch:.2%})")

    # -- controls --------------------------------------------------------------------------------
    def controls_nodes(self, tries=3):
        """Make the control overlay visible and return the UI nodes ([] when the dump is unavailable)."""
        for _ in range(tries):
            ns = self.nodes()
            if not ns:
                w, h = self.size()
                self.phone.tap(w // 2, int(h * 0.25))
                self.sleep(0.8)
                return []
            if uix.find(ns, desc="play_pause") or uix.find(ns, text=PAUSE_TXT) or uix.find(ns, text=PLAY_TXT):
                return ns
            w, h = self.size()
            self.phone.tap(w // 2, int(h * 0.25))
            self.sleep(0.9)
        ns = self.nodes()
        need(uix.find(ns, desc="play_pause") or uix.find(ns, text=PAUSE_TXT) or uix.find(ns, text=PLAY_TXT), "controls did not appear on tap")
        return ns

    # The overlay hides 3 s after the last interaction (unless paused) and a uiautomator dump takes ~2 s, so the
    # buttons are tapped at fixed dp positions (measured from a dump on the phone) right after showing the overlay.
    _ctrl_until = 0.0
    ctrl_pinned = False   # set by checks while the video is paused (overlay does not auto-hide)

    def tap_ctrl(self, x, y):
        """Tap an overlay button without knowing whether the overlay is visible: a tap that lands on the gesture
        layer (overlay hidden) logs 'Gesture detected: TAP' and shows the overlay, so repeat the tap once then."""
        m = self.log.mark()
        self.phone.tap(x, y)
        self.sleep(0.5)
        self.log.refresh()
        if self.log.find(r"Gesture detected: TAP", since=m):
            self.sleep(0.2)
            self.phone.tap(x, y)

    def top_xy(self, k_dp):
        w, h = self.size()
        return w - self.dpx(k_dp), self.dpx(45)

    # Fallback positions (dp from the right) in landscape: lock, More (⋮, P11-05), mini player, aspect, sub, audio;
    # the speed chip moves left by one button when the file has chapters (P4-15).
    TOP = {"lock": 37, "more": 86, "pip": 135, "aspect": 184, "sub": 233, "audio": 281, "speed": 328}

    def tap_top(self, which, ns=None):
        if not ns:
            # prefer the contentDescription hooks over fixed dp positions (the top bar layout changes, e.g.
            # the quality chip moved the speed chip): show the controls if hidden, then dump
            ns = self.nodes()
            if ns and not uix.find_all(ns, desc=which):
                w, h = self.size()
                self.phone.tap(w // 2, h // 3)
                self.sleep(0.6)
                ns = self.nodes()
        cands = uix.find_all(ns, desc=which) if ns else []
        if not cands and ns:
            spec = {"lock": dict(text="🔒"), "pip": dict(desc="Mini player"), "aspect": dict(desc="Aspect ratio"),
                    "sub": dict(text="💬"), "audio": dict(text="🎵"),
                    "speed": dict(regex=r"^\d+(\.\d+)?x$")}[which]
            cands = uix.find_all(ns, **spec)
            if which == "speed":
                cands = [n for n in cands if n.bounds[1] < self.dpx(120)]
        if cands:
            self.phone.tap(*uix.center(cands[0]))
            return "ui"
        self.tap_ctrl(*self.top_xy(self.TOP[which]))
        return "fixed-xy"

    def tap_center_button(self, which, ns=None):
        w, h = self.size()
        if which == "pause":
            n = (uix.find(ns, desc="play_pause") or
                 uix.find(ns, text=PAUSE_TXT) or
                 uix.find(ns, text=PLAY_TXT)) if ns else None
            if n:
                self.phone.tap(*uix.center(n))
                return "ui"
            self.tap_ctrl(w // 2, h // 2)
            return "fixed-xy"
        else:
            n = (uix.find(ns, desc=which) or
                 uix.find(ns, text="|◀" if which == "prev" else "▶|")) if ns else None
            if n:
                self.phone.tap(*uix.center(n))
                return "ui"
            self.tap_ctrl(w // 2 + self.dpx(78) * (-1 if which == "prev" else 1), h // 2)
            return "fixed-xy"

    def set_gesture_mark(self):
        return self.log.mark()

    def gesture_logged(self, name, since, timeout=4):
        return self.log.wait(r"Gesture detected: " + name, timeout, since=since, tag="VP")

    def seek_near_end(self, remaining_s=4.0, start_pos_s=None, max_swipes=5):
        """Swipe-seek until about remaining_s seconds are left. Needs the duration from the debug log."""
        w, h = self.size()
        dur = self.duration_s()
        if not dur:
            self.sleep(11)  # periodic position save logs the duration
            dur = self.duration_s()
        need(dur, "duration unknown (no debug onPropertyChange log and no Saved position line)")
        pos = None
        if start_pos_s is None:
            r = self.last_value(r"Resume: resuming from (\d+) ms")
            start_pos_s = int(r) / 1000.0 if r else 0.0
        for _ in range(max_swipes):
            left = dur - (pos if pos is not None else (start_pos_s or 0.0))
            if pos is not None and left <= remaining_s + 4:
                break
            dx = int(min(max((left - remaining_s) / 0.04, 150), w * 0.85))
            mark = self.log.mark()
            x0 = max(int(w * 0.07), self.dpx(48))
            self.phone.swipe(x0, int(h * 0.5), x0 + dx, int(h * 0.5), 500)
            hit = self.log.wait(r"Swipe seek commit: deltaSec=-?[\d.]+ \(start=(\d+), target=(\d+)\)", 6, since=mark)
            need(hit, "swipe seek did not commit (no 'Swipe seek commit' log)")
            pos = int(hit[1].group(2)) / 1000.0
            self.sleep(0.8)
        return dur, pos

    def end(self):
        pass
