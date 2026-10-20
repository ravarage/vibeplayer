"""adb access with timeouts and the safety filter, plus device-level helpers."""
import os
import re
import shlex
import subprocess
import time

from .safety import check_adb_args, PKG

TMP_PNG = "/data/local/tmp/vp_dc_shot.png"
TMP_XML = "/data/local/tmp/vp_dc_ui.xml"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def adb_env():
    env = dict(os.environ)
    for d in (os.path.join(REPO, "tools", "bin"), "/home/rava/vibeplayer/tools/bin"):
        if os.path.exists(os.path.join(d, "adb")):
            env["PATH"] = d + os.pathsep + env.get("PATH", "")
            break
    env.pop("ANDROID_SERIAL", None)
    return env


class Phone:
    def __init__(self, runner=None, popen=None):
        self.env = adb_env()
        self._runner = runner
        self._popen = popen or subprocess.Popen
        self.calls = 0

    def run(self, args, timeout=30):
        check_adb_args(args)
        self.calls += 1
        if self._runner:
            return self._runner(args, timeout)
        try:
            p = subprocess.run(["adb"] + list(args), capture_output=True, timeout=timeout, env=self.env)
        except subprocess.TimeoutExpired:
            return 124, "", "timeout"
        except FileNotFoundError:
            return 127, "", "adb not found"
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")

    def sh(self, cmd, timeout=30):
        return self.run(["shell", cmd], timeout)[1]

    def sh_rc(self, cmd, timeout=30):
        return self.run(["shell", cmd], timeout)

    def sh_async(self, cmd):
        check_adb_args(["shell", cmd])
        return self._popen(["adb", "shell", cmd], env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # -- device state -------------------------------------------------------------------------
    def connected(self):
        # a shell call makes the adb wrapper pick/connect Wi-Fi or USB; `devices` alone would not
        return bool(self.sh("getprop ro.product.model", 20).strip())

    def wake(self):
        self.sh("input keyevent KEYCODE_WAKEUP", 10)
        self.sh("wm dismiss-keyguard", 10)
        time.sleep(1.0)
        out = self.sh("dumpsys power", 20)
        return "mWakefulness=Awake" in out

    def key(self, name):
        self.sh(f"input keyevent {name}", 10)

    def tap(self, x, y):
        self.sh(f"input tap {int(x)} {int(y)}", 10)

    def double_tap(self, x, y):
        """Two taps from two concurrent `input` processes (process start-up is slower than the app's
        250 ms double-tap window, so they are not run back to back)."""
        self.sh(f"input tap {int(x)} {int(y)} & input tap {int(x)} {int(y)} & input tap {int(x)} {int(y)} & wait", 15)

    def swipe(self, x1, y1, x2, y2, ms=400):
        self.sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}", 20)

    def swipe_async(self, x1, y1, x2, y2, ms):
        return self.sh_async(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

    def long_press(self, x, y, ms=900):
        self.swipe(x, y, x, y, ms)

    def pids(self):
        out = self.sh(f"pidof {PKG}", 10).split()
        return {int(p) for p in out if p.isdigit()}

    def force_stop(self):
        self.sh(f"am force-stop {PKG}", 15)

    def start_activity(self, component, data=None, extras=""):
        cmd = f"am start -n {PKG}/{component}"
        if data:
            cmd += f" -a android.intent.action.VIEW -d {shlex.quote(data)}"
        if extras:
            cmd += " " + extras
        return self.sh(cmd, 20)

    def broadcast(self, action="com.vibeplayer.DEBUG_ACTION", extras=""):
        cmd = f"am broadcast -p {PKG} -a {action}"
        if extras:
            cmd += " " + extras
        return self.sh(cmd, 15)

    def logcat_clear(self):
        self.run(["logcat", "-c"], 15)

    def logcat_dump(self):
        # The Adreno Vulkan driver logs two lines per frame (vkBindImageMemory2 / Gralloc query, harmless) under
        # gpu-next: ~50 lines/s that made every poll re-scan a fast-growing buffer (checks took minutes). Silenced.
        rc, out, _ = self.run(["logcat", "-d", "-v", "threadtime", "AdrenoVK-0:S"], 40)
        return out

    def logcat_recent(self, lines=4000):
        """Only the newest lines: the LogView polls every 0.7 s and merges overlapping windows, so it never needs
        the whole buffer. A full `logcat -d` over Wi-Fi took seconds per poll once the buffer filled, which made
        every check spend minutes before its first tap (P11 device run, 2026-10-20)."""
        rc, out, _ = self.run(["logcat", "-v", "threadtime", "-t", str(lines), "AdrenoVK-0:S"], 40)
        return out

    def crash_buffer(self):
        return self.run(["logcat", "-d", "-b", "crash", "-v", "threadtime"], 30)[1]

    def screenshot(self, local):
        from PIL import Image
        rc, _, err = self.sh_rc(f"screencap -p {TMP_PNG}", 30)
        if rc != 0:
            raise RuntimeError("screencap failed: " + err.strip()[:100])
        rc, _, err = self.run(["pull", TMP_PNG, local], 120)
        if rc != 0:
            raise RuntimeError("pull failed: " + err.strip()[:100])
        img = Image.open(local)
        img.load()
        return img.convert("RGB")

    def ui_xml(self):
        rc, out, _ = self.sh_rc(f"uiautomator dump {TMP_XML}", 30)
        if rc != 0 or "ERROR" in out:
            return ""
        return self.sh(f"cat {TMP_XML}", 30)

    def density_dp(self):
        out = self.sh("wm density", 10)
        m = re.search(r"(\d+)\s*$", out.strip().splitlines()[-1] if out.strip() else "")
        return (int(m.group(1)) / 160.0) if m else 2.625

    def wm_size(self):
        m = re.search(r"(\d+)x(\d+)", self.sh("wm size", 10))
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    # media volume (STREAM_MUSIC only; enforced by safety.py)
    def music_volume(self):
        out = self.sh("cmd media_session volume --stream 3 --get", 15)
        m = re.search(r"volume is (\d+)", out)
        if m:
            return int(m.group(1))
        out = self.sh("dumpsys audio", 30)
        blk = out.split("- STREAM_MUSIC:")
        if len(blk) > 1:
            m = re.search(r"(?:streamVolume|Current):\s*(?:\w+\s*\(\w+\):\s*)?(\d+)", blk[1][:1500])
            if m:
                return int(m.group(1))
        return None

    def set_music_volume(self, v):
        self.sh(f"cmd media_session volume --stream 3 --set {int(v)}", 15)

    def listing(self, directory):
        out = self.sh(f"ls -1 {shlex.quote(directory)}", 20)
        return [l.strip() for l in out.splitlines() if l.strip() and not l.startswith("ls:")]

    def app_info(self):
        out = self.sh(f"dumpsys package {PKG}", 30)
        m = re.search(r"versionName=(\S+)", out)
        dbg = bool(re.search(r"flags=\[[^\]]*DEBUGGABLE", out))
        return (m.group(1) if m else "?"), dbg

    def auto_play_next(self):
        """The app's 'Auto-play next' setting (debug build, read with run-as), or None if unreadable."""
        out = self.sh(f"run-as {PKG} od -An -tx1 -v files/datastore/app_settings.preferences_pb", 30)
        return auto_play_from_hex(out)

    def vulkan_hash(self):
        out = self.sh("sha256sum /vendor/lib64/hw/vulkan.adreno.so", 30).split()
        return out[0][:16] if out and re.match(r"^[0-9a-f]{64}$", out[0]) else "unknown"


def auto_play_from_hex(hex_text):
    """Reads auto_play_next from an od -tx1 dump of the settings DataStore (Preferences protobuf).
    The entry is the key string, then the value message: 0x12 len 0x08 <bool>. No key means the default, on.
    None when the dump is not hex (no file, run-as refused on a release build)."""
    try:
        data = bytes(int(b, 16) for b in hex_text.split())
    except ValueError:
        return None
    if not data:
        return None
    key = b"auto_play_next"
    i = data.find(key)
    if i < 0:
        return True
    rest = data[i + len(key):i + len(key) + 4]
    if len(rest) == 4 and rest[0] == 0x12 and rest[2] == 0x08:
        return rest[3] != 0
    return None
