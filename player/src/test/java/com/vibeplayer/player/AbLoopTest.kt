package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AbLoopTest {

    @Test
    fun setAThenBThenClear() {
        assertEquals(1_000L to null, AbLoop.next(null, null, 1_000L))
        assertEquals(1_000L to 5_000L, AbLoop.next(1_000L, null, 5_000L))
        assertEquals(null to null, AbLoop.next(1_000L, 5_000L, 9_000L))
    }

    @Test
    fun bBeforeAIsSwappedAndBAtAIsIgnored() {
        assertEquals(2_000L to 8_000L, AbLoop.next(8_000L, null, 2_000L))
        assertEquals(3_000L to null, AbLoop.next(3_000L, null, 3_000L))
    }

    @Test
    fun mpvLoopsOnlyOnceBothPointsAreSet() {
        assertEquals("no" to "no", AbLoop.mpvValues(null, null))
        assertEquals("no" to "no", AbLoop.mpvValues(1_000L, null))
        assertEquals("1.000" to "5.000", AbLoop.mpvValues(1_000L, 5_000L))
    }

    @Test
    fun secondsForMpv() {
        assertEquals("83.250", AbLoop.seconds(83_250L))
        assertEquals("0.000", AbLoop.seconds(0L))
    }
}
