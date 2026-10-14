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


if __name__ == "__main__":
    unittest.main()
