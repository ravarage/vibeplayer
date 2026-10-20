package com.vibeplayer.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    @Test
    fun lastFivePercentIsWatched() {
        assertTrue(WatchProgress.isWatched(95_000L, 100_000L))
        assertTrue(WatchProgress.isWatched(100_000L, 100_000L))
        assertFalse(WatchProgress.isWatched(94_999L, 100_000L))
        assertFalse(WatchProgress.isWatched(50_000L, 0L))
        assertFalse(WatchProgress.isWatched(null))
        assertTrue(WatchProgress.isWatched(FileSettings(key = "k", positionMs = 1_400_000L, durationMs = 1_420_000L)))
    }
}
