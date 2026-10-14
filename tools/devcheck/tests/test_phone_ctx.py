import unittest
from _path import *  # noqa
from devcheck.phone import Phone
from devcheck.ctx import folder_order, natural_cmp, file_uri, Ctx, Fail, need
from devcheck.safety import Refused


class FakePhone(Phone):
    """Phone whose adb transport records commands (the safety filter still runs)."""

    def __init__(self, answers=None):
        self.calls_log = []
        self.answers = answers or {}
        super().__init__(runner=self._run)

    def _run(self, args, timeout):
        self.calls_log.append(args)
        text = " ".join(args)
        for k, v in self.answers.items():
            if k in text:
                return 0, v, ""
        return 0, "", ""


class PhoneTests(unittest.TestCase):
    def test_every_helper_passes_the_filter(self):
        p = FakePhone({"dumpsys power": "mWakefulness=Awake"})
        self.assertTrue(p.wake())
        p.key("KEYCODE_HOME"); p.tap(1, 2); p.double_tap(3, 4); p.swipe(1, 2, 3, 4, 300)
        p.long_press(5, 5); p.pids(); p.force_stop(); p.logcat_clear(); p.logcat_dump(); p.crash_buffer()
        p.start_activity(".ui.player.PlayerActivity", data=file_uri("/sdcard/Movies/vibeplayer-test/[Ironclad] a b.mkv"))
        p.start_activity(".MainActivity")
        p.ui_xml(); p.density_dp(); p.wm_size(); p.listing("/sdcard/Movies/vibeplayer-test")
        p.app_info(); p.vulkan_hash(); p.music_volume(); p.set_music_volume(5)
        self.assertGreater(len(p.calls_log), 20)

    def test_refuses_dangerous(self):
        p = FakePhone()
        for cmd in ("pm clear com.vibeplayer", "settings put system x 1", "reboot",
                    "cmd media_session volume --stream 2 --set 0", "am force-stop com.android.settings"):
            with self.assertRaises(Refused):
                p.sh(cmd)
        with self.assertRaises(Refused):
            p.run(["uninstall", "com.vibeplayer"])

    def test_volume_parse(self):
        p = FakePhone({"--get": "volume is 9 in range [0..15]"})
        self.assertEqual(p.music_volume(), 9)
        p = FakePhone({"dumpsys audio": "- STREAM_RING:\n Current: 3\n- STREAM_MUSIC:\n   Muted: false\n   streamVolume: 6\n"})
        self.assertEqual(p.music_volume(), 6)

    def test_density_and_size(self):
        p = FakePhone({"wm density": "Physical density: 420\n", "wm size": "Physical size: 1080x2400\n"})
        self.assertAlmostEqual(p.density_dp(), 2.625)
        self.assertEqual(p.wm_size(), (1080, 2400))

    def test_launch_uri_is_quoted_and_encoded(self):
        p = FakePhone()
        p.start_activity(".ui.player.PlayerActivity", data=file_uri("/sdcard/a b/[x].mkv"))
        cmd = p.calls_log[-1][1]
        self.assertIn("file:///sdcard/a%20b/%5Bx%5D.mkv", cmd)


class OrderTests(unittest.TestCase):
    def test_natural(self):
        names = ["ep 10.mkv", "ep 2.mkv", "B.mp4", "a.mp4", "[x] z.mkv", "notes.txt"]
        self.assertEqual(folder_order(names), ["[x] z.mkv", "a.mp4", "B.mp4", "ep 2.mkv", "ep 10.mkv"])
        self.assertLess(natural_cmp("a01", "a2"), 0)

    def test_need(self):
        with self.assertRaises(Fail):
            need(False, "x")


if __name__ == "__main__":
    unittest.main()
