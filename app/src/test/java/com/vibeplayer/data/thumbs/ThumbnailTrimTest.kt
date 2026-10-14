package com.vibeplayer.data.thumbs

import android.content.ComponentCallbacks2 as CC
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailTrimTest {
    @Test
    fun trimTargets() {
        assertEquals(500, ThumbnailCache.trimTargetKb(CC.TRIM_MEMORY_UI_HIDDEN, 1000))
        assertEquals(0, ThumbnailCache.trimTargetKb(CC.TRIM_MEMORY_BACKGROUND, 1000))
        assertEquals(0, ThumbnailCache.trimTargetKb(CC.TRIM_MEMORY_RUNNING_CRITICAL, 1000))
        assertEquals(1000, ThumbnailCache.trimTargetKb(5, 1000))
    }

    @Test
    fun budgetHoldsAboutSeventyThumbnails() {
        val thumbKb = 320 * 180 * 4 / 1024
        assertEquals(true, ThumbnailCache.MEMORY_BUDGET_KB / thumbKb in 60..80)
    }
}
