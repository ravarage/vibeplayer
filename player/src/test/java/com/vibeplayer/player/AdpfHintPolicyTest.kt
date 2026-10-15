package com.vibeplayer.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdpfHintPolicyTest {

    @Test
    fun `matches mpv playback threads exactly`() {
        for (name in listOf("vo", "core", "demux", "dec/video", "vo\n", " core ")) {
            assertTrue(name, AdpfHintPolicy.isPlaybackThread(name))
        }
    }

    @Test
    fun `matches dav1d workers and ffmpeg frame threads by prefix`() {
        assertTrue(AdpfHintPolicy.isPlaybackThread("dav1d-worker"))
        assertTrue(AdpfHintPolicy.isPlaybackThread("av:hevc:df0"))
        assertTrue(AdpfHintPolicy.isPlaybackThread("av:h264:df7"))
    }

    @Test
    fun `excludes audio, pool and unrelated threads`() {
        for (name in listOf(
            "dec/audio", "ao/audiotrack", "worker", "opener", "input", "log", "",
            "main", "RenderThread", "DefaultDispatch", "voice", "vold", "coreX", "demuxer", "mpv/vo",
        )) {
            assertFalse(name, AdpfHintPolicy.isPlaybackThread(name))
        }
    }

    @Test
    fun `selectTids keeps matching tids sorted and unique`() {
        val tids = AdpfHintPolicy.selectTids(
            listOf(
                30 to "dav1d-worker", 12 to "vo", 5 to "main", 20 to "demux",
                12 to "vo", 40 to "ao/audiotrack", 0 to "core", 11 to "core",
            )
        )
        assertArrayEquals(intArrayOf(11, 12, 20, 30), tids)
        assertEquals(0, AdpfHintPolicy.selectTids(listOf(1 to "main")).size)
    }

    @Test
    fun `target duration follows fps with 60 fps fallback`() {
        assertEquals(41_708_375L, AdpfHintPolicy.targetDurationNanos(23.976))
        assertEquals(16_666_666L, AdpfHintPolicy.targetDurationNanos(60.0))
        assertEquals(16_666_666L, AdpfHintPolicy.targetDurationNanos(null))
        assertEquals(16_666_666L, AdpfHintPolicy.targetDurationNanos(0.0))
        assertEquals(16_666_666L, AdpfHintPolicy.targetDurationNanos(Double.NaN))
        assertEquals(16_666_666L, AdpfHintPolicy.targetDurationNanos(90000.0))
        assertTrue(AdpfHintPolicy.targetDurationNanos(240.0) > 0)
    }

    @Test
    fun `power efficiency only for zero-copy hardware decode`() {
        assertTrue(AdpfHintPolicy.preferPowerEfficiency("mediacodec"))
        assertFalse(AdpfHintPolicy.preferPowerEfficiency("mediacodec-copy"))
        assertFalse(AdpfHintPolicy.preferPowerEfficiency("no"))
        assertFalse(AdpfHintPolicy.preferPowerEfficiency(""))
        assertFalse(AdpfHintPolicy.preferPowerEfficiency(null))
    }

    @Test
    fun `reported duration rises only when drops increased`() {
        val t = 16_666_666L
        assertEquals(t, AdpfHintPolicy.reportedDurationNanos(t, null, 5))
        assertEquals(t, AdpfHintPolicy.reportedDurationNanos(t, 5, 5))
        assertEquals(24_999_999L, AdpfHintPolicy.reportedDurationNanos(t, 5, 6))
        // Counter reset by a new file: not an overrun.
        assertEquals(t, AdpfHintPolicy.reportedDurationNanos(t, 500, 0))
        assertEquals(1L, AdpfHintPolicy.reportedDurationNanos(0, 0, 0))
    }

    @Test
    fun `rescan schedule after a load`() {
        val rescans = (0..30).filter { AdpfHintPolicy.shouldRescan(it) }
        assertEquals(listOf(0, 1, 2, 5, 10, 20, 30), rescans)
    }

    @Test
    fun `overlay text`() {
        assertEquals("off", AdpfHintPolicy.overlayText(false, true, 4))
        assertEquals("unsupported", AdpfHintPolicy.overlayText(true, false, null))
        assertEquals("idle", AdpfHintPolicy.overlayText(true, true, null))
        assertEquals("on (6 threads)", AdpfHintPolicy.overlayText(true, true, 6))
    }
}
