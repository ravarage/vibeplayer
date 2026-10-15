#!/usr/bin/env python3
"""Unit tests for tools/bench/make_table.py (P6-06)."""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

from make_table import (  # noqa: E402
    DEFAULT_NOTES,
    DEFAULT_OUT,
    REPO,
    Measurement,
    choose,
    dumps,
    make_table,
    operating_rate_policy,
    parse_csv,
    parse_markdown,
)

with open(DEFAULT_NOTES, encoding="utf-8") as _f:
    NOTES = json.load(_f)
BENCHMARK_MD = os.path.join(REPO, "docs", "BENCHMARK.md")


def _m(renderer, vo, hwdec, drops, ma, cpu, fmt="x"):
    return Measurement(fmt, renderer, vo, hwdec, drops, ma, cpu)


class TestRule(unittest.TestCase):

    def test_fewest_drops_win_over_power(self):
        a = _m("gles", "gpu", "no", 918, 1668, 592)
        b = _m("vulkan", "gpu", "no", 362, 1563, 579)
        c = _m("vulkan", "gpu-next", "no", 385, 1340, 576)
        # 385 is 6.4% above 362: not equal, so the lower-power gpu-next does not win.
        self.assertIs(choose([a, b, c], 0.05, 0.02), b)

    def test_drops_within_tolerance_go_to_lower_power(self):
        zc = _m("gles", "gpu", "mediacodec", 1241, 604.5, 29)
        vk = _m("vulkan", "gpu", "mediacodec-copy", 1207, 1254.3, 117)
        self.assertIs(choose([vk, zc], 0.05, 0.02), zc)

    def test_equal_smoothness_lowest_current(self):
        a = _m("gles", "gpu", "mediacodec", 0, 637, 28)
        b = _m("vulkan", "gpu-next", "mediacodec-copy", 0, 549, 36)
        self.assertIs(choose([a, b], 0.05, 0.02), b)

    def test_current_tie_goes_to_lower_cpu(self):
        a = _m("gles", "gpu", "mediacodec", 0, 500, 40)
        b = _m("vulkan", "gpu", "mediacodec-copy", 0, 505, 30)
        self.assertIs(choose([a, b], 0.05, 0.02), b)

    def test_zero_drops_has_no_slack(self):
        a = _m("gles", "gpu", "mediacodec", 0, 900, 40)
        b = _m("vulkan", "gpu", "mediacodec-copy", 1, 300, 30)
        self.assertIs(choose([a, b], 0.05, 0.02), a)

    def test_empty(self):
        self.assertIsNone(choose([], 0.05, 0.02))


class TestP608Table(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        with open(BENCHMARK_MD, encoding="utf-8") as f:
            cls.driver, cls.ms = parse_markdown(f.read(), NOTES)
        cls.doc = make_table(cls.driver, cls.ms, NOTES, "BENCHMARK.md: Power comparison")
        cls.rows = cls.doc["default"]["rows"]

    def test_parse(self):
        self.assertEqual(self.driver, "c0b92d25178c8b21")
        self.assertEqual(len(self.ms), 15)  # the control repeat row is skipped
        self.assertEqual({m.format for m in self.ms}, {"h264_8bit", "hevc", "av1", "vp9"})

    def test_measured_winners(self):
        r = self.rows
        self.assertEqual((r["h264_8bit"]["renderer"], r["h264_8bit"]["vo"], r["h264_8bit"]["hwdec"]),
                         ("vulkan", "gpu-next", "mediacodec-copy"))
        self.assertEqual((r["hevc"]["renderer"], r["hevc"]["vo"], r["hevc"]["hwdec"]), ("gles", "gpu", "mediacodec"))
        self.assertEqual((r["av1"]["renderer"], r["av1"]["vo"], r["av1"]["hwdec"]), ("vulkan", "gpu", "no"))
        self.assertEqual((r["vp9"]["renderer"], r["vp9"]["vo"], r["vp9"]["hwdec"]), ("gles", "gpu", "mediacodec"))

    def test_gles_fallbacks(self):
        # H.264 zero-copy is excluded (B-08 start stalls), so the GLES fallback is copy-back.
        self.assertEqual(self.rows["h264_8bit"]["gles"], {**self.rows["h264_8bit"]["gles"],
                                                          "hwdec": "mediacodec-copy", "vo": "gpu"})
        self.assertEqual(self.rows["av1"]["gles"]["hwdec"], "no")
        self.assertNotIn("gles", self.rows["hevc"])

    def test_fixed_rows_kept(self):
        self.assertEqual(self.rows["h264_10bit"]["hwdec"], "no")
        self.assertEqual(self.rows["h264_10bit"]["renderer"], "vulkan")
        self.assertEqual(self.rows["other"]["renderer"], "gles")

    def test_operating_rate_policy(self):
        r = self.rows
        self.assertEqual({f: r[f]["operatingRate"] for f in r},
                         {"h264_8bit": "auto", "hevc": "auto", "vp9": "auto",
                          "av1": "off", "h264_10bit": "off", "other": "off"})

    def test_default_equals_driver_table(self):
        self.assertEqual(self.doc["tables"][0]["driverHash"], "c0b92d25178c8b21")
        self.assertEqual(self.doc["tables"][0]["rows"], self.doc["default"]["rows"])

    def test_committed_asset_is_regenerated_output(self):
        with open(DEFAULT_OUT, encoding="utf-8") as f:
            self.assertEqual(f.read(), dumps(self.doc))

    def test_other_driver_tables_kept(self):
        old = {"tables": [{"driverHash": "0000000000000000", "rows": {}, "source": "old"}]}
        doc = make_table(self.driver, self.ms, NOTES, "x", existing=old)
        self.assertEqual([t["driverHash"] for t in doc["tables"]], ["0000000000000000", "c0b92d25178c8b21"])


class TestOperatingRatePolicy(unittest.TestCase):

    def test_software_only_rows_are_off(self):
        self.assertEqual(operating_rate_policy("av1", {"hwdec": "no", "gles": {"hwdec": "no"}}, NOTES), "off")
        self.assertEqual(operating_rate_policy("other", {"hwdec": "no"}, NOTES), "off")

    def test_hardware_rows_follow_notes(self):
        self.assertEqual(operating_rate_policy("hevc", {"hwdec": "mediacodec"}, NOTES), "auto")
        # A software Vulkan row whose GLES block decodes in hardware still decodes with MediaCodec there.
        self.assertEqual(operating_rate_policy("x", {"hwdec": "no", "gles": {"hwdec": "mediacodec-copy"}}, NOTES),
                         "auto")
        notes = {"operatingRate": {"default": "auto", "formats": {"vp9": "off"}}}
        self.assertEqual(operating_rate_policy("vp9", {"hwdec": "mediacodec"}, notes), "off")
        self.assertEqual(operating_rate_policy("hevc", {"hwdec": "mediacodec"}, notes), "auto")
        self.assertEqual(operating_rate_policy("hevc", {"hwdec": "mediacodec"}, {}), "auto")

    def test_bad_policy_rejected(self):
        with self.assertRaises(ValueError):
            operating_rate_policy("hevc", {"hwdec": "mediacodec"}, {"operatingRate": {"default": "fast"}})

    def test_fixed_row_value_kept(self):
        notes = dict(NOTES, fixedRows={"other": {"hwdec": "mediacodec", "renderer": "gles", "vo": "gpu",
                                                  "reason": "x", "operatingRate": "off"}})
        doc = make_table("c0b92d25178c8b21", [], notes, "x")
        self.assertEqual(doc["default"]["rows"]["other"]["operatingRate"], "off")


class TestCsv(unittest.TestCase):
    HEADER = ("timestamp,run_id,file,mode,vo,renderer,profile,decoder,shader,shader_cache,requested_seconds,"
              "actual_seconds,loops,startup_drops,steady_drops,total_drops,startup_decoder_drops,"
              "steady_decoder_drops,total_decoder_drops,mistimed_frames,vo_delayed_frames,estimated_vf_fps,"
              "container_fps,display_fps,cpu_percent_avg,battery_current_avg,battery_temp_start,"
              "battery_temp_end,charging,vulkan_api_version,vulkan_device_name,driver_hash,driver_mtime")

    def _row(self, file, mode, vo, renderer, drops, current, cpu, charging="false", decoder="auto"):
        return (f"t,r,/sdcard/Movies/vibeplayer-test/{file},{mode},{vo},{renderer},default,{decoder},none,warm,"
                f"60,60,1,0,{drops},{drops},0,0,0,0,0,30,30,60,{cpu},{current},30,31,{charging},1.3,Adreno,"
                f"c0b92d25178c8b21,m")

    def test_csv_rows(self):
        text = "\n".join([
            self.HEADER,
            self._row("ichiban.mp4", "efficient", "gpu", "gles", 0, -637000, 28),
            self._row("ichiban.mp4", "quality", "gpu-next", "vulkan", 0, -549000, 36),
            self._row("ichiban.mp4", "quality", "gpu", "vulkan", 0, -100000, 36, charging="true"),
        ])
        h, ms = parse_csv(text, NOTES)
        self.assertEqual(h, "c0b92d25178c8b21")
        self.assertEqual(len(ms), 2)  # charging row dropped
        self.assertEqual(ms[0].hwdec, "mediacodec")
        self.assertEqual(ms[1].hwdec, "mediacodec-copy")
        self.assertAlmostEqual(ms[1].current_ma, 549.0)


if __name__ == "__main__":
    unittest.main()
