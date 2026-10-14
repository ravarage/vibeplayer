package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSyncTest {
    @Test fun stepMovesBy50AndClamps() {
        assertEquals(50, SyncDelay.step(0, 1))
        assertEquals(-50, SyncDelay.step(0, -1))
        assertEquals(100, SyncDelay.step(50, 1))
        assertEquals(SyncDelay.MAX_MS, SyncDelay.step(SyncDelay.MAX_MS, 1))
        assertEquals(-SyncDelay.MAX_MS, SyncDelay.step(-SyncDelay.MAX_MS, -1))
        assertEquals(50, SyncDelay.step(0, 7))
    }

    @Test fun optionsOnlyForNonZero() {
        assertTrue(SyncDelay.loadOptions(0, 0).isEmpty())
        assertEquals(listOf("sub-delay=0.150"), SyncDelay.loadOptions(150, 0))
        assertEquals(listOf("sub-delay=-0.050", "audio-delay=1.250"), SyncDelay.loadOptions(-50, 1250))
    }

    @Test fun formatSign() {
        assertEquals("+50 ms", SyncDelay.format(50))
        assertEquals("-50 ms", SyncDelay.format(-50))
        assertEquals("0 ms", SyncDelay.format(0))
    }

    @Test fun styleClampsAndOverride() {
        val s = SubtitleStyle(scale = 5.0, pos = -3, assOverride = false).mpvOptions().toMap()
        assertEquals("2.00", s["sub-scale"])
        assertEquals("0", s["sub-pos"])
        assertEquals("no", s["sub-ass-override"])
        val t = SubtitleStyle(scale = 0.1, pos = 250, assOverride = true).mpvOptions().toMap()
        assertEquals("0.60", t["sub-scale"])
        assertEquals("100", t["sub-pos"])
        assertEquals("force", t["sub-ass-override"])
        assertEquals("1.00", SubtitleStyle(scale = Double.NaN).mpvOptions().toMap()["sub-scale"])
    }
}
