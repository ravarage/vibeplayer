import unittest
from PIL import Image, ImageDraw
from _path import *  # noqa
from devcheck import uix, imgx, pipx

XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="1"><node index="0" text="" class="android.widget.FrameLayout" bounds="[0,0][2400,1080]">
<node text="ichiban.mp4" content-desc="" clickable="false" bounds="[100,40][900,120]"/>
<node text="" content-desc="Aspect ratio" clickable="true" bounds="[1700,20][1800,140]"/>
<node text="❚❚" content-desc="" clickable="true" bounds="[1100,500][1300,580]"/>
<node text="1.5x" content-desc="" clickable="false" bounds="[100,600][200,660]"/>
</node></hierarchy>"""


class UiTests(unittest.TestCase):
    def test_find_and_center(self):
        ns = uix.parse_dump("UI hierarchy dumped to: /x\n" + XML)
        self.assertEqual(len(ns), 5)
        n = uix.find(ns, desc="Aspect ratio")
        self.assertEqual(uix.center(n), (1750, 80))
        self.assertEqual(uix.center(uix.find(ns, text="❚❚")), (1200, 540))
        self.assertIsNotNone(uix.find(ns, regex=r"^\d(\.\d)?x$"))
        self.assertIsNone(uix.find(ns, text="nope"))
        self.assertEqual(uix.texts_in(ns, (0, 0, 1000, 200)), ["ichiban.mp4"])

    def test_bad_xml(self):
        self.assertEqual(uix.parse_dump("ERROR: null root node"), [])
        self.assertEqual(uix.parse_dump("<?xml version='1.0'?><hierarchy><node"), [])


class ImgTests(unittest.TestCase):
    def test_metrics(self):
        black = Image.new("RGB", (1080, 2400), (0, 0, 0))
        self.assertLess(imgx.nonblack_ratio(black), 0.001)
        grey = Image.new("RGB", (1080, 2400), (90, 90, 90))
        self.assertGreater(imgx.nonblack_ratio(grey), 0.99)
        self.assertFalse(imgx.is_moving(grey, grey.copy()))
        moved = grey.copy()
        ImageDraw.Draw(moved).rectangle((100, 100, 700, 900), fill=(250, 10, 10))
        self.assertTrue(imgx.is_moving(grey, moved))
        r = imgx.changed_ratio(grey, moved)
        self.assertTrue(0.05 < r < 0.3, r)
        self.assertTrue(imgx.is_landscape(Image.new("RGB", (2400, 1080))))

    def test_crop_and_stats(self):
        img = Image.new("RGB", (1000, 500), (0, 0, 0))
        ImageDraw.Draw(img).rectangle((600, 300, 900, 450), fill=(200, 200, 200))
        box = imgx.crop_box(img, (600, 300, 900, 450), shrink=0.1)
        self.assertGreater(imgx.nonblack_ratio(box), 0.99)
        self.assertEqual(imgx.nonblack_ratio(imgx.crop_frac(img, 0, 0, 0.5, 0.5)), 0.0)
        flat = Image.new("RGB", (100, 100), (30, 30, 30))
        self.assertLess(imgx.luma_stats(flat)[1], 1)
        with self.assertRaises(ValueError):
            imgx.crop_box(img, (5, 5, 5, 5))


PIP = """\
  * Task{abc #12 type=standard A=10123:com.android.launcher3 U=0}
    mBounds=Rect(0, 0 - 1080, 2400)
    windowingMode=fullscreen
  * Task{def #57 type=standard A=10412:com.vibeplayer U=0 visible=true}
    mBounds=Rect(1300, 700 - 2300, 1263)
    windowingMode=pinned
    * ActivityRecord{1 u0 com.vibeplayer/.ui.player.PlayerActivity t57}
"""


class PipTests(unittest.TestCase):
    def test_pinned(self):
        pinned, b = pipx.parse_pip(PIP)
        self.assertTrue(pinned)
        self.assertEqual(b, (1300, 700, 2300, 1263))
        self.assertTrue(pipx.aspect_matches(b, 1920, 1080))
        self.assertFalse(pipx.aspect_matches(b, 1080, 1920))

    def test_not_pinned(self):
        txt = PIP.replace("windowingMode=pinned", "windowingMode=fullscreen")
        self.assertEqual(pipx.parse_pip(txt), (False, None))

    def test_bracket_form(self):
        txt = "Task{1 A=1:com.vibeplayer}\n  windowingMode=pinned\n  bounds=[100,200][500,425]\n"
        self.assertEqual(pipx.parse_pip(txt), (True, (100, 200, 500, 425)))


if __name__ == "__main__":
    unittest.main()
