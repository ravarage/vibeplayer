"""Hard-coded adb safety filter. Every adb invocation made by devcheck goes through check_adb_args().

Default deny: only the commands the checks need are accepted. Refused (non-exhaustive): uninstall,
install, pm clear, reboot, settings put, any volume other than STREAM_MUSIC (3), any package other
than com.vibeplayer, unknown shell commands, pipes/redirects/substitution. HOME/BACK/WAKEUP keys are
allowed; volume keys are not (key events change the volume of whatever stream is active).
"""
import re
import shlex

PKG = "com.vibeplayer"
TMP_RE = re.compile(r"^/data/local/tmp/vp_[A-Za-z0-9_.\-]+$")
ALLOWED_KEYS = {"KEYCODE_HOME", "KEYCODE_BACK", "KEYCODE_WAKEUP", "KEYCODE_APP_SWITCH", "3", "4", "187", "224"}
NUM_RE = re.compile(r"^-?\d+(\.\d+)?$")
PUNCT_OK = {"&", "&&", ";"}
RUN_AS_SETTINGS_DUMP = [PKG, "od", "-An", "-tx1", "-v", "files/datastore/app_settings.preferences_pb"]


class Refused(Exception):
    """Raised for an adb command the safety filter does not allow."""


def _pkg_ok(value):
    return value == PKG or value.startswith(PKG + "/") or value.startswith(PKG + ".")


def check_adb_args(args):
    if not args or not all(isinstance(a, str) for a in args):
        raise Refused("empty or non-string adb arguments")
    sub = args[0]
    if sub == "shell":
        if len(args) < 2:
            raise Refused("interactive adb shell")
        _check_shell(" ".join(args[1:]))
    elif sub == "pull":
        if len(args) != 3 or not TMP_RE.match(args[1]):
            raise Refused("pull only from /data/local/tmp/vp_*")
    elif sub == "logcat":
        _check_logcat(args[1:])
    elif sub in ("devices", "get-state"):
        if len(args) > 2 or (sub == "get-state" and len(args) != 1):
            raise Refused(f"unexpected arguments for adb {sub}")
    elif sub == "connect":
        if len(args) != 2 or not re.match(r"^[0-9.]+:\d+$", args[1]):
            raise Refused("adb connect needs host:port")
    else:
        raise Refused(f"adb {sub} is not allowed")
    return True


def _check_shell(s):
    if "`" in s or "$(" in s:
        raise Refused("command substitution")
    try:
        lex = shlex.shlex(s, posix=True, punctuation_chars=True)
        lex.whitespace_split = True
        tokens = list(lex)
    except ValueError as e:
        raise Refused(f"unparsable shell command: {e}")
    segments, cur = [], []
    for t in tokens:
        if t and all(c in "();<>|&" for c in t):
            if t not in PUNCT_OK:
                raise Refused(f"shell operator {t!r}")
            if cur:
                segments.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        segments.append(cur)
    if not segments:
        raise Refused("empty shell command")
    for seg in segments:
        _check_segment(seg)


def _check_segment(t):
    cmd = t[0]
    fn = _SEGMENTS.get(cmd)
    if fn is None:
        raise Refused(f"shell command {cmd!r} is not allowed")
    fn(t)


def _input(t):
    if len(t) > 1 and t[1] in ("touchscreen", "touchpad"):
        t = [t[0]] + t[2:]
    if len(t) < 2:
        raise Refused("input needs a subcommand")
    sub = t[1]
    if sub == "tap" and len(t) == 4 and all(NUM_RE.match(x) for x in t[2:]):
        return
    if sub == "swipe" and len(t) in (6, 7) and all(NUM_RE.match(x) for x in t[2:]):
        return
    if sub == "keyevent" and len(t) >= 3 and all(k in ALLOWED_KEYS for k in t[2:]):
        return
    # P13: typing a test link into a text field: an http(s) URL in URL characters only (no spaces, quotes or
    # shell syntax); any other text stays refused.
    if sub == "text" and len(t) == 3 and re.match(r"^https?://[A-Za-z0-9:/._%~=?&-]{1,200}$", t[2]):
        return
    raise Refused(f"input {' '.join(t[1:])} not allowed")


_AM_FLAGS = {"-W", "-S", "-D", "--activity-clear-top", "--activity-single-top", "--activity-new-task",
             "--activity-brought-to-front", "--activity-reorder-to-front"}
_AM_ARG1 = {"-a", "-c", "-t", "-d", "-n", "-p", "-f", "--user"}
_AM_ARG2 = {"--es", "--ez", "--ei", "--el", "--ef", "-e"}


def _am(t):
    if len(t) < 2:
        raise Refused("am needs a subcommand")
    if t[1] == "force-stop":
        if t[2:] != [PKG]:
            raise Refused("force-stop only for " + PKG)
        return
    if t[1] not in ("start", "broadcast"):
        raise Refused(f"am {t[1]} not allowed")
    comps, i = [], 2
    while i < len(t):
        a = t[i]
        if a in _AM_FLAGS:
            i += 1
        elif a in _AM_ARG1:
            if i + 1 >= len(t):
                raise Refused("missing value for " + a)
            v = t[i + 1]
            if a in ("-n", "-p"):
                comps.append(v)
            if a == "-a" and v.startswith("android.settings"):
                raise Refused("settings action")
            if a == "-d" and v.startswith(("content://settings", "package:")) :
                raise Refused("uri not allowed")
            i += 2
        elif a in _AM_ARG2:
            if i + 2 >= len(t):
                raise Refused("missing value for " + a)
            i += 3
        elif a.startswith("-"):
            raise Refused(f"am {t[1]} option {a} not allowed")
        else:
            comps.append(a)
            i += 1
    if not comps or not all(_pkg_ok(c) for c in comps):
        raise Refused(f"am {t[1]} must target " + PKG)


def _dumpsys(t):
    if len(t) < 2:
        raise Refused("dumpsys without service lists all services")
    svc = t[1]
    rest = t[2:]
    if svc in ("package", "gfxinfo", "meminfo"):
        if rest != [PKG]:
            raise Refused(f"dumpsys {svc} only for {PKG}")
        return
    if svc not in ("activity", "window", "power", "audio", "media_session", "display"):
        raise Refused(f"dumpsys {svc} not allowed")
    for a in rest:
        if not a.startswith("-") and "." in a and not _pkg_ok(a):
            raise Refused("dumpsys of another package")


def _volume_args(t):
    # cmd media_session volume --stream 3 [--get | --set N | --show]
    if "--stream" not in t:
        raise Refused("volume command without --stream")
    i = t.index("--stream")
    if i + 1 >= len(t) or t[i + 1] != "3":
        raise Refused("only STREAM_MUSIC (3) volume may be touched")
    rest = t[:i] + t[i + 2:]
    j = 0
    while j < len(rest):
        if rest[j] in ("--get", "--show"):
            j += 1
        elif rest[j] == "--set" and j + 1 < len(rest) and re.match(r"^\d{1,2}$", rest[j + 1]):
            j += 2
        else:
            raise Refused(f"volume option {rest[j]!r} not allowed")


def _cmd(t):
    if t[1:3] == ["media_session", "volume"]:
        return _volume_args(t[3:])
    raise Refused("cmd only for media_session volume")


def _media(t):
    if t[1:2] == ["volume"]:
        return _volume_args(t[2:])
    raise Refused("media only for volume")


def _tmp_only(t, flags=()):
    paths = [a for a in t[1:] if not (a.startswith("-") and a in flags)]
    if not paths or not all(TMP_RE.match(p) for p in paths):
        raise Refused(f"{t[0]} only on /data/local/tmp/vp_*")


def _logcat_tokens(a):
    i = 0
    while i < len(a):
        x = a[i]
        if x in ("-c", "-d"):
            i += 1
        elif x in ("-v", "-t", "-T") and i + 1 < len(a):
            i += 2
        elif x == "-b" and i + 1 < len(a) and a[i + 1] in ("main", "system", "crash", "events", "all", "main,system,crash"):
            i += 2
        elif re.match(r"^--pid=\d+$", x) or re.match(r"^[A-Za-z0-9_\-*]+:[VDIWEFS]$", x):
            i += 1
        else:
            raise Refused(f"logcat option {x!r} not allowed")


def _check_logcat(a):
    _logcat_tokens(a)


def _ls(t):
    for a in t[1:]:
        if a.startswith("-"):
            continue
        if not (a.startswith("/sdcard/") or a.startswith("/storage/emulated/0/") or a.startswith("/data/local/tmp/")):
            raise Refused("ls outside /sdcard")


_SEGMENTS = {
    "wait": lambda t: None if len(t) == 1 else (_ for _ in ()).throw(Refused("wait args")),
    "input": _input,
    "am": _am,
    "pidof": lambda t: None if t[1:] in ([PKG], ["-s", PKG]) else (_ for _ in ()).throw(Refused("pidof other package")),
    "wm": lambda t: None if t[1:] in (["dismiss-keyguard"], ["size"], ["density"]) else (_ for _ in ()).throw(Refused("wm")),
    "screencap": lambda t: None if len(t) == 3 and t[1] == "-p" and TMP_RE.match(t[2]) else (_ for _ in ()).throw(Refused("screencap")),
    "uiautomator": lambda t: None if (t[1:2] == ["dump"] and TMP_RE.match(t[-1] if len(t) > 2 else "") and all(a in ("--compressed",) or TMP_RE.match(a) for a in t[2:])) else (_ for _ in ()).throw(Refused("uiautomator")),
    "rm": lambda t: _tmp_only(t, ("-f",)),
    "cat": lambda t: _tmp_only(t),
    "ls": _ls,
    "dumpsys": _dumpsys,
    "cmd": _cmd,
    "media": _media,
    "sha256sum": lambda t: None if t[1:] == ["/vendor/lib64/hw/vulkan.adreno.so"] else (_ for _ in ()).throw(Refused("sha256sum")),
    "getprop": lambda t: None if len(t) <= 2 else (_ for _ in ()).throw(Refused("getprop")),
    "settings": lambda t: None if t[1:2] == ["get"] else (_ for _ in ()).throw(Refused("settings write")),
    "logcat": lambda t: _logcat_tokens(t[1:]),
    # read-only dump of the app's own settings file (debug build), for the auto-play warning
    "run-as": lambda t: None if t[1:] == RUN_AS_SETTINGS_DUMP else (_ for _ in ()).throw(Refused("run-as")),
}
