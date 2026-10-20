package com.vibeplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {

    @Test
    fun labels() {
        assertEquals("Off", SleepTimer.label(null, 0L))
        assertEquals("At the end of this video", SleepTimer.label(SleepTimer.endOfFile(), 0L))
        val t = SleepTimer.inMinutes(30, 1_000L)
        assertEquals(1_000L + 1_800_000L, t.endsAtElapsedMs)
        assertEquals("Pauses in 30 min", SleepTimer.label(t, 1_000L))
        assertEquals("Pauses in 23 min", SleepTimer.label(t, 1_000L + 7 * 60_000L + 1L))
        assertEquals("Pauses in 0:45", SleepTimer.label(t, 1_801_000L - 45_000L))
        assertEquals("Pauses in 0:00", SleepTimer.label(t, 9_999_999L))
    }
}
