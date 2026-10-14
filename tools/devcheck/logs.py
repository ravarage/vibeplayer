"""logcat parsing and PID-filtered waiting. Pure functions plus a small LogView that polls a fetcher."""
import re
import time
from collections import namedtuple

LogLine = namedtuple("LogLine", "seq ts pid tid level tag msg raw")
_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)\s+(\d+)\s+(\d+) ([VDIWEFA]) (.*?)\s*: (.*)$")


def parse_line(line, seq=0):
    m = _RE.match(line.rstrip("\n"))
    if not m:
        return None
    mo, d, h, mi, s, frac, pid, tid, lvl, tag, msg = m.groups()
    ts = ((int(mo) * 31 + int(d)) * 86400) + int(h) * 3600 + int(mi) * 60 + int(s) + int(frac.ljust(3, "0")[:3]) / 1000.0
    return LogLine(seq, ts, int(pid), int(tid), lvl, tag, msg, line)


def parse_text(text):
    out = []
    for i, ln in enumerate(text.splitlines()):
        p = parse_line(ln, i)
        if p:
            out.append(p)
    return out


def filter_pids(lines, pids):
    return [l for l in lines if l.pid in pids]


def scan_crashes(text, package="com.vibeplayer"):
    """Return one-line descriptions of FATAL EXCEPTION / ANR / native crash entries for the package."""
    found = []
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if "FATAL EXCEPTION" in ln:
            window = " ".join(lines[i:i + 4])
            if f"Process: {package}" in window:
                found.append("FATAL EXCEPTION in " + package)
        elif re.search(r"\bANR in " + re.escape(package), ln):
            found.append(ln.strip()[-120:])
        elif "Fatal signal" in ln and package in ln:
            found.append(ln.strip()[-120:])
    return found


class LogView:
    """fetch() returns the full logcat text (adb logcat -d); pids() returns the app's current PIDs.
    Lines are kept only for PIDs seen while this view was refreshed."""

    def __init__(self, fetch, pids, sleep=time.sleep, clock=time.monotonic):
        self.fetch, self.pids_fn, self.sleep, self.clock = fetch, pids, sleep, clock
        self.known = set()
        self._all_lines = []
        self._all_raw = []
        self.lines = []
        self.raw_count = 0

    def reset(self):
        self._all_lines, self._all_raw, self.lines, self.raw_count = [], [], [], 0

    def refresh(self):
        try:
            self.known |= set(self.pids_fn())
        except Exception:
            pass
        text = self.fetch()
        parsed = parse_text(text)
        if not self._all_lines:
            for i, p in enumerate(parsed):
                self._all_lines.append(p._replace(seq=i))
                self._all_raw.append(p.raw)
        else:
            overlap_idx = -1
            for back in range(1, min(len(self._all_raw) + 1, 50)):
                target = self._all_raw[-back]
                for j in range(len(parsed) - 1, -1, -1):
                    if parsed[j].raw == target:
                        match = True
                        for k in range(1, min(j + 1, len(self._all_raw) - back + 1, 10)):
                            if parsed[j - k].raw != self._all_raw[-back - k]:
                                match = False
                                break
                        if match:
                            overlap_idx = j
                            break
                if overlap_idx != -1:
                    break
            new_parsed = parsed[overlap_idx + 1:] if overlap_idx != -1 else parsed
            start_seq = len(self._all_lines)
            for i, p in enumerate(new_parsed):
                self._all_lines.append(p._replace(seq=start_seq + i))
                self._all_raw.append(p.raw)

        self.raw_count = len(self._all_lines)
        self.lines = filter_pids(self._all_lines, self.known)
        return self.lines

    def mark(self):
        self.refresh()
        return len(self._all_lines)

    def find_all(self, pattern, since=0, tag=None):
        rx = re.compile(pattern)
        out = []
        for l in self.lines:
            if l.seq < since or (tag and l.tag != tag):
                continue
            m = rx.search(l.msg)
            if m:
                out.append((l, m))
        return out

    def find(self, pattern, since=0, tag=None):
        r = self.find_all(pattern, since, tag)
        return r[0] if r else None

    def wait(self, pattern, timeout, since=0, tag=None, poll=0.7):
        end = self.clock() + timeout
        while True:
            self.refresh()
            r = self.find(pattern, since, tag)
            if r:
                return r
            if self.clock() >= end:
                return None
            self.sleep(poll)
