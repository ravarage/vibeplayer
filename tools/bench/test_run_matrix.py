#!/usr/bin/env python3
"""Unit tests for the host benchmark matrix runner (tools/bench/run_matrix.py)."""

import os
import sys
import unittest

# Ensure tools/bench is on sys.path
sys.path.insert(0, os.path.dirname(__file__))

from run_matrix import (
    MatrixRow,
    compute_summary_from_samples,
    format_markdown_table,
    get_predefined_matrix,
    parse_benchmark_csv,
    should_skip_row,
)


class TestMatrixDefinitions(unittest.TestCase):

    def test_short_matrix(self):
        rows = get_predefined_matrix("short")
        self.assertEqual(len(rows), 12)
        # Check files
        files = {r.file for r in rows}
        self.assertEqual(
            files,
            {
                "/sdcard/Movies/vibeplayer-test/ichiban.mp4",
                "/sdcard/Movies/vibeplayer-test/hevc10_4k.mp4",
                "/sdcard/Movies/vibeplayer-bench/av1_1080p60_10bit.mp4",
            },
        )
        # Check renderers and VOs
        self.assertEqual({r.renderer for r in rows}, {"vulkan", "gles"})
        self.assertEqual({r.vo for r in rows}, {"gpu-next", "gpu"})
        # 5 min each = 300 s
        for r in rows:
            self.assertEqual(r.seconds, 300)

    def test_av1_attribution_matrix(self):
        rows = get_predefined_matrix("av1_attribution")
        self.assertEqual(len(rows), 24)
        # 4 clips x 2 renderers x 3 configs
        self.assertEqual(
            {os.path.basename(r.file) for r in rows},
            {
                "av1_4k.mp4",
                "av1_1080p60_10bit.mp4",
                "av1_4k30_10bit.mp4",
                "av1_4k60_8bit.mp4",
            },
        )
        for r in rows:
            self.assertEqual(r.seconds, 60)
        # Contains profile=fast on gpu-next
        fast_rows = [r for r in rows if r.profile == "fast"]
        self.assertEqual(len(fast_rows), 8)  # 4 clips x 2 renderers
        for r in fast_rows:
            self.assertEqual(r.vo, "gpu-next")

    def test_stability_matrix(self):
        rows = get_predefined_matrix("stability")
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0].renderer, "vulkan")
        self.assertEqual(rows[1].renderer, "gles")
        self.assertEqual(rows[0].seconds, 1800)  # 30 min
        self.assertEqual(rows[1].seconds, 1800)

    def test_shaders_matrix(self):
        rows = get_predefined_matrix("shaders")
        self.assertEqual(len(rows), 5)
        presets = [r.shader for r in rows]
        self.assertEqual(
            presets,
            ["none", "anime4k_a", "anime4k_b", "anime4k_c", "snapdragon_gsr"],
        )
        for r in rows:
            self.assertEqual(r.seconds, 300)
            self.assertEqual(r.renderer, "vulkan")  # Anime4K is Vulkan-only


class TestResumeSkipLogic(unittest.TestCase):

    def test_resume_skip_matches_existing_row(self):
        row = MatrixRow(
            file="/sdcard/Movies/vibeplayer-test/ichiban.mp4",
            mode="quality",
            vo="gpu-next",
            renderer="vulkan",
            shader="none",
            seconds=300,
            cache="warm",
            profile="default",
            decoder="auto",
        )
        existing_rows = [
            {
                "file": "ichiban.mp4",
                "mode": "quality",
                "vo": "gpu-next",
                "renderer": "vulkan",
                "shader": "none",
                "shader_cache": "warm",
                "profile": "default",
                "decoder": "auto",
                "requested_seconds": "300",
            }
        ]
        self.assertTrue(should_skip_row(row, existing_rows))

    def test_resume_skip_does_not_skip_different_row(self):
        row = MatrixRow(
            file="/sdcard/Movies/vibeplayer-test/ichiban.mp4",
            mode="quality",
            vo="gpu-next",
            renderer="gles",
            shader="none",
            seconds=300,
            cache="warm",
            profile="default",
            decoder="auto",
        )
        existing_rows = [
            {
                "file": "ichiban.mp4",
                "mode": "quality",
                "vo": "gpu-next",
                "renderer": "vulkan",
                "shader": "none",
                "shader_cache": "warm",
                "profile": "default",
                "decoder": "auto",
                "requested_seconds": "300",
            }
        ]
        self.assertFalse(should_skip_row(row, existing_rows))

    def test_parse_csv_and_skip(self):
        csv_text = (
            "timestamp,run_id,file,mode,vo,renderer,profile,decoder,shader,shader_cache,requested_seconds,actual_seconds,loops,startup_drops,steady_drops,total_drops\n"
            "2026-10-13T20:00:00Z,run1,hevc10_4k.mp4,quality,gpu-next,vulkan,default,auto,none,warm,300,300,10,2,5,7\n"
        )
        parsed = parse_benchmark_csv(csv_text)
        self.assertEqual(len(parsed), 1)
        matching_row = MatrixRow(
            file="/sdcard/Movies/vibeplayer-test/hevc10_4k.mp4",
            mode="quality",
            vo="gpu-next",
            renderer="vulkan",
            shader="none",
            seconds=300,
            cache="warm",
            profile="default",
            decoder="auto",
        )
        self.assertTrue(should_skip_row(matching_row, parsed))


class TestSummaryComputation(unittest.TestCase):

    def test_startup_and_steady_drops_split(self):
        # 5 samples: at t=1, drops=3; at t=2, drops=5; at t=3, drops=6; at t=4, drops=6; at t=5, drops=8
        samples = [
            {
                "elapsed_seconds": 1,
                "time_pos": 1.0,
                "frame_drop_count": 3,
                "decoder_frame_drop_count": 1,
                "mistimed_frame_count": 0,
                "vo_delayed_frame_count": 0,
                "estimated_vf_fps": 60.0,
                "container_fps": 60.0,
                "display_fps": 120.0,
                "cpu_percent": 15.0,
                "battery_current": 450000,
            },
            {
                "elapsed_seconds": 2,
                "time_pos": 2.0,
                "frame_drop_count": 5,
                "decoder_frame_drop_count": 2,
                "mistimed_frame_count": 0,
                "vo_delayed_frame_count": 0,
                "estimated_vf_fps": 60.0,
                "container_fps": 60.0,
                "display_fps": 120.0,
                "cpu_percent": 25.0,
                "battery_current": 480000,
            },
            {
                "elapsed_seconds": 3,
                "time_pos": 3.0,
                "frame_drop_count": 6,
                "decoder_frame_drop_count": 2,
                "mistimed_frame_count": 1,
                "vo_delayed_frame_count": 0,
                "estimated_vf_fps": 59.5,
                "container_fps": 60.0,
                "display_fps": 120.0,
                "cpu_percent": 20.0,
                "battery_current": 500000,
            },
            {
                "elapsed_seconds": 4,
                "time_pos": 4.0,
                "frame_drop_count": 6,
                "decoder_frame_drop_count": 2,
                "mistimed_frame_count": 1,
                "vo_delayed_frame_count": 1,
                "estimated_vf_fps": 60.0,
                "container_fps": 60.0,
                "display_fps": 120.0,
                "cpu_percent": 18.0,
                "battery_current": 470000,
            },
            {
                "elapsed_seconds": 5,
                "time_pos": 5.0,
                "frame_drop_count": 8,
                "decoder_frame_drop_count": 3,
                "mistimed_frame_count": 1,
                "vo_delayed_frame_count": 1,
                "estimated_vf_fps": 59.8,
                "container_fps": 60.0,
                "display_fps": 120.0,
                "cpu_percent": 22.0,
                "battery_current": 460000,
            },
        ]

        summary = compute_summary_from_samples(
            samples=samples,
            timestamp="2026-10-13T21:00:00Z",
            run_id="run_test",
            file_path="/sdcard/Movies/vibeplayer-test/ichiban.mp4",
            mode="quality",
            vo="gpu-next",
            renderer="vulkan",
            profile="default",
            decoder="auto",
            shader="none",
            shader_cache="warm",
            requested_seconds=5,
            loops=0,
            battery_temp_start=29.5,
            battery_temp_end=30.2,
            charging=False,
            vulkan_api="1.3.295",
            vulkan_device="Adreno (TM) 642L",
            driver_hash="c0b92d25178c8b21",
            driver_mtime="2026-10-13 21:09",
        )

        # At t=2, frame_drop_count=5. Total drops=8.
        # Startup drops = 5, Steady drops = 8 - 5 = 3
        self.assertEqual(summary["startup_drops"], 5)
        self.assertEqual(summary["steady_drops"], 3)
        self.assertEqual(summary["total_drops"], 8)

        # Decoder drops: at t=2, dec_drops=2. Total dec_drops=3.
        # Startup dec drops = 2, Steady dec drops = 1
        self.assertEqual(summary["startup_decoder_drops"], 2)
        self.assertEqual(summary["steady_decoder_drops"], 1)
        self.assertEqual(summary["total_decoder_drops"], 3)

        # Averages
        expected_cpu = (15.0 + 25.0 + 20.0 + 18.0 + 22.0) / 5
        self.assertAlmostEqual(summary["cpu_percent_avg"], expected_cpu)

        expected_current = (450000 + 480000 + 500000 + 470000 + 460000) / 5
        self.assertAlmostEqual(summary["battery_current_avg"], expected_current)

        self.assertEqual(summary["mistimed_frames"], 1)
        self.assertEqual(summary["vo_delayed_frames"], 1)
        self.assertEqual(summary["driver_hash"], "c0b92d25178c8b21")


class TestMarkdownGeneration(unittest.TestCase):

    def test_markdown_table_formatting(self):
        row = {
            "file": "ichiban.mp4",
            "mode": "quality",
            "vo": "gpu-next",
            "renderer": "vulkan",
            "profile": "default",
            "decoder": "auto",
            "shader": "none",
            "shader_cache": "warm",
            "actual_seconds": 300,
            "startup_drops": 2,
            "steady_drops": 1,
            "total_drops": 3,
            "startup_decoder_drops": 0,
            "steady_decoder_drops": 0,
            "total_decoder_drops": 0,
            "estimated_vf_fps": 59.9,
            "cpu_percent_avg": 24.3,
            "battery_current_avg": 420000,
            "battery_temp_start": 28.5,
            "battery_temp_end": 30.1,
        }
        md = format_markdown_table(
            rows=[row],
            matrix_name="short",
            driver_hash="c0b92d25178c8b21",
            driver_mtime="2026-10-13 21:09",
            start_battery_level=85,
            charging=False,
        )
        self.assertIn("c0b92d25178c8b21", md)
        self.assertIn("85%", md)
        self.assertIn("No (on battery)", md)
        self.assertIn("`ichiban.mp4`", md)
        self.assertIn("2 / 1 / 3", md)
        self.assertIn("420", md)  # 420 mA


if __name__ == "__main__":
    unittest.main()
