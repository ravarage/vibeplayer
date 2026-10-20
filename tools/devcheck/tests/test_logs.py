import unittest
from _path import *  # noqa
from devcheck import logs

SAMPLE = """\
10-14 12:00:00.100  1111  1111 I VP      : old process line
10-14 12:00:01.000  2222  2222 I VP      : Loading /sdcard/a.mp4 from start without options
10-14 12:00:01.500  3333  3333 I VP      : other pid
10-14 12:00:02.250  2222  2300 I VP-Perf : playback-restart at 1760000000000
10-14 12:00:03.000  2222  2300 I VP      : onPropertyChange: hwdec-current = mediacodec-copy
10-14 12:00:04.000  2222  2222 E AndroidRuntime: FATAL EXCEPTION: main
10-14 12:00:04.001  2222  2222 E AndroidRuntime: Process: com.vibeplayer, PID: 2222
garbage line
"""


class LogTests(unittest.TestCase):
    def test_parse(self):
        ls = logs.parse_text(SAMPLE)
        self.assertEqual(len(ls), 7)
        self.assertEqual(ls[3].tag, "VP-Perf")
        self.assertEqual(ls[3].pid, 2222)
        self.assertAlmostEqual(ls[3].ts - ls[1].ts, 1.25, places=3)

    def test_pid_filter(self):
        ls = logs.filter_pids(logs.parse_text(SAMPLE), {2222})
        self.assertTrue(all(l.pid == 2222 for l in ls))
        self.assertEqual(len(ls), 5)

    def test_crash_scan(self):
        self.assertEqual(logs.scan_crashes(SAMPLE), ["FATAL EXCEPTION in com.vibeplayer"])
        self.assertEqual(logs.scan_crashes("E AndroidRuntime: FATAL EXCEPTION: x\nProcess: other.app"), [])
        self.assertTrue(logs.scan_crashes("W ActivityManager: ANR in com.vibeplayer (x)"))

    def test_view_wait(self):
        state = {"n": 0}

        def fetch():
            state["n"] += 1
            return SAMPLE if state["n"] >= 3 else "\n".join(SAMPLE.splitlines()[:2])

        t = {"now": 0.0}
        v = logs.LogView(fetch, lambda: {2222}, sleep=lambda s: t.__setitem__("now", t["now"] + s),
                         clock=lambda: t["now"])
        hit = v.wait(r"playback-restart", 10, tag="VP-Perf")
        self.assertIsNotNone(hit)
        self.assertEqual(hit[0].pid, 2222)
        self.assertIsNone(v.wait(r"never appears", 2))
        self.assertIsNone(v.find(r"other pid"))
        # since= excludes earlier lines
        self.assertIsNone(v.find(r"Loading", since=3))


    def test_view_merges_sliding_windows(self):
        # logcat -t N returns only the newest N lines; the view must keep every line once, in order.
        full = [f"10-14 12:00:{i // 10:02d}.{(i % 10) * 100:03d}  2222  2222 I VP      : line {i}" for i in range(300)]
        state = {"end": 40}

        def fetch():
            end = state["end"]
            state["end"] = min(end + 37, len(full))
            return "\n".join(full[max(0, end - 50):end])  # a 50-line window

        v = logs.LogView(fetch, lambda: {2222})
        for _ in range(12):
            v.refresh()
        msgs = [l.msg for l in v.lines]
        self.assertEqual(msgs, [f"line {i}" for i in range(0, 300)][-len(msgs):])
        self.assertEqual(msgs[-1], "line 299")
        self.assertEqual(len(msgs), len(set(msgs)))

    def test_view_ignores_reordered_window(self):
        # A later read with the same lines in another order must not add any of them again, so an old
        # line never shows up after a mark.
        a = [f"10-14 12:00:00.{i:03d}  2222  2222 I VP      : setAspect: m{i}" for i in range(20)]
        reads = ["\n".join(a), "\n".join(a[:5] + a[6:] + [a[5]])]
        v = logs.LogView(lambda: reads.pop(0) if len(reads) > 1 else reads[0], lambda: {2222})
        m = v.mark()
        v.refresh()
        self.assertIsNone(v.find(r"setAspect", since=m))
        self.assertEqual(len(v.lines), 20)


if __name__ == "__main__":
    unittest.main()
