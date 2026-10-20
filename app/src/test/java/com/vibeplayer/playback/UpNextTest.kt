package com.vibeplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextTest {
    private val upNext = UpNext(endedPath = "/a/01.mkv", path = "/a/02.mkv", title = "02.mkv", endsAtElapsedMs = 10_000L)

    @Test
    fun secondsLeftRoundsUp() {
        assertEquals(5, upNext.secondsLeft(5_000L))
        assertEquals(5, upNext.secondsLeft(5_001L))
        assertEquals(1, upNext.secondsLeft(9_999L))
        assertEquals(0, upNext.secondsLeft(10_000L))
        assertEquals(0, upNext.secondsLeft(12_000L))
    }
}
