import unittest
from _path import *  # noqa
from devcheck.safety import check_adb_args as chk, Refused


class SafetyTests(unittest.TestCase):
    def ok(self, *a):
        self.assertTrue(chk(list(a)))

    def bad(self, *a):
        with self.assertRaises(Refused):
            chk(list(a))

    def test_allowed(self):
        self.ok("shell", "input keyevent KEYCODE_HOME")
        self.ok("shell", "input", "tap", "10", "20")
        self.ok("shell", "input swipe 100 900 100 300 500")
        self.ok("shell", "am force-stop com.vibeplayer")
        self.ok("shell", "am start -n com.vibeplayer/.ui.player.PlayerActivity -d 'file:///sdcard/Movies/a%20b.mp4'")
        self.ok("shell", "am start -a android.intent.action.VIEW -d file:///x.mp4 -t video/mp4 com.vibeplayer")
        self.ok("shell", "cmd media_session volume --stream 3 --get")
        self.ok("shell", "cmd media_session volume --stream 3 --set 7")
        self.ok("shell", "screencap -p /data/local/tmp/vp_x.png")
        self.ok("pull", "/data/local/tmp/vp_x.png", "/tmp/x.png")
        self.ok("shell", "input tap 5 5 & input tap 5 5 & wait")
        self.ok("shell", "dumpsys activity activities")
        self.ok("shell", "dumpsys package com.vibeplayer")
        self.ok("shell", "pidof com.vibeplayer")
        self.ok("shell", "sha256sum /vendor/lib64/hw/vulkan.adreno.so")
        self.ok("shell", "uiautomator dump /data/local/tmp/vp_ui.xml")
        self.ok("logcat", "-d", "-v", "threadtime")
        self.ok("shell", "logcat -c")
        self.ok("shell", "settings get system screen_off_timeout")
        self.ok("shell", "ls -1 /sdcard/Movies/vibeplayer-test")

    def test_refused(self):
        self.bad("uninstall", "com.vibeplayer")
        self.bad("install", "-r", "a.apk")
        self.bad("reboot")
        self.bad("kill-server")
        self.bad("tcpip", "5555")
        self.bad("shell", "pm clear com.vibeplayer")
        self.bad("shell", "pm uninstall com.vibeplayer")
        self.bad("shell", "settings put system screen_brightness 10")
        self.bad("shell", "reboot")
        self.bad("shell", "am force-stop com.android.chrome")
        self.bad("shell", "am start -n com.android.settings/.Settings")
        self.bad("shell", "am start -a android.intent.action.VIEW -d file:///x.mp4")
        self.bad("shell", "am broadcast -a x")
        self.bad("shell", "cmd media_session volume --stream 4 --set 1")
        self.bad("shell", "cmd media_session volume --set 1")
        self.bad("shell", "cmd media_session volume --stream 3 --set 1 --stream 5")
        self.bad("shell", "media volume --stream 2 --set 0")
        self.bad("shell", "input keyevent KEYCODE_VOLUME_UP")
        self.bad("shell", "input keyevent KEYCODE_POWER")
        self.bad("shell", "input text hello")
        self.bad("shell", "rm -rf /sdcard")
        self.bad("shell", "dumpsys package com.android.chrome")
        self.bad("shell", "dumpsys activity com.google.android.apps.nexuslauncher")
        self.bad("shell", "cat /data/data/com.vibeplayer/x")
        self.bad("shell", "input tap 1 1 | sh")
        self.bad("shell", "echo $(reboot)")
        self.bad("shell", "input tap 1 1 > /sdcard/x")
        self.bad("pull", "/data/data/com.vibeplayer/databases/x", "/tmp/x")
        self.bad("shell", "svc wifi disable")
        self.bad("shell")
        self.bad()


if __name__ == "__main__":
    unittest.main()
