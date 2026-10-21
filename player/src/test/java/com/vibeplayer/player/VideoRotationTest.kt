package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRotationTest {
    @Test
    fun quarterTurnsSwapTheSides() {
        assertEquals(1080 to 1920, VideoRotation.displaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, VideoRotation.displaySize(1920, 1080, 270))
        assertEquals(1080 to 1920, VideoRotation.displaySize(1920, 1080, -90))
    }

    @Test
    fun halfTurnsAndNoneKeepThem() {
        assertEquals(1920 to 1080, VideoRotation.displaySize(1920, 1080, 0))
        assertEquals(1920 to 1080, VideoRotation.displaySize(1920, 1080, 180))
        assertEquals(0 to 0, VideoRotation.displaySize(0, 0, 90))
    }
}
