package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFrameRateHelperTest {

    @Test
    fun `isValidFps accepts standard and high frame rates`() {
        assertTrue(SurfaceFrameRateHelper.isValidFps(23.976))
        assertTrue(SurfaceFrameRateHelper.isValidFps(24.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(25.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(29.97))
        assertTrue(SurfaceFrameRateHelper.isValidFps(30.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(50.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(59.94))
        assertTrue(SurfaceFrameRateHelper.isValidFps(60.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(120.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(144.0))
        assertTrue(SurfaceFrameRateHelper.isValidFps(240.0))
    }

    @Test
    fun `isValidFps rejects invalid, non-positive, NaN, infinite and excessive fps`() {
        assertFalse(SurfaceFrameRateHelper.isValidFps(null as Double?))
        assertFalse(SurfaceFrameRateHelper.isValidFps(0.0))
        assertFalse(SurfaceFrameRateHelper.isValidFps(-24.0))
        assertFalse(SurfaceFrameRateHelper.isValidFps(Double.NaN))
        assertFalse(SurfaceFrameRateHelper.isValidFps(Double.POSITIVE_INFINITY))
        assertFalse(SurfaceFrameRateHelper.isValidFps(Double.NEGATIVE_INFINITY))
        assertFalse(SurfaceFrameRateHelper.isValidFps(240.1))
        assertFalse(SurfaceFrameRateHelper.isValidFps(1000.0)) // VFR timebase
    }

    @Test
    fun `decideFrameRate votes exact fps when active and playing`() {
        val vote = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = true,
            containerFps = 23.976,
            isPaused = false,
            isSurfaceAlive = true,
            isPlayerActive = true
        )
        assertEquals(23.976f, vote, 0.0001f)
    }

    @Test
    fun `decideFrameRate votes 0 when matchDisplayRefreshRate setting is disabled`() {
        val vote = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = false,
            containerFps = 24.0,
            isPaused = false,
            isSurfaceAlive = true,
            isPlayerActive = true
        )
        assertEquals(0f, vote, 0.0f)
    }

    @Test
    fun `decideFrameRate votes 0 when paused`() {
        val vote = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = true,
            containerFps = 60.0,
            isPaused = true,
            isSurfaceAlive = true,
            isPlayerActive = true
        )
        assertEquals(0f, vote, 0.0f)
    }

    @Test
    fun `decideFrameRate votes 0 when surface is destroyed or inactive`() {
        val voteSurfaceDead = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = true,
            containerFps = 60.0,
            isPaused = false,
            isSurfaceAlive = false,
            isPlayerActive = true
        )
        assertEquals(0f, voteSurfaceDead, 0.0f)

        val votePlayerInactive = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = true,
            containerFps = 60.0,
            isPaused = false,
            isSurfaceAlive = true,
            isPlayerActive = false
        )
        assertEquals(0f, votePlayerInactive, 0.0f)
    }

    @Test
    fun `decideFrameRate votes 0 when fps is invalid`() {
        assertEquals(0f, SurfaceFrameRateHelper.decideFrameRate(true, null, isPaused = false), 0.0f)
        assertEquals(0f, SurfaceFrameRateHelper.decideFrameRate(true, 0.0, isPaused = false), 0.0f)
        assertEquals(0f, SurfaceFrameRateHelper.decideFrameRate(true, Double.NaN, isPaused = false), 0.0f)
        assertEquals(0f, SurfaceFrameRateHelper.decideFrameRate(true, 480.0, isPaused = false), 0.0f)
    }

    @Test
    fun `shouldUpdateFrameRate avoids duplicate calls once voted`() {
        // Initial state, no vote yet: target 23.976f requires call
        assertTrue(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = null, targetFps = 23.976f))

        // Initial state, target 0f: no need to call setFrameRate(0f) if nothing was set
        assertFalse(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = null, targetFps = 0f))

        // Playing steadily: target remains 23.976f -> NO call (once per file, not per frame!)
        assertFalse(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = 23.976f, targetFps = 23.976f))

        // Paused: target changes from 23.976f to 0f -> requires call
        assertTrue(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = 23.976f, targetFps = 0f))

        // Paused steadily: target remains 0f -> NO call
        assertFalse(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = 0f, targetFps = 0f))

        // Resumed: target changes from 0f to 23.976f -> requires call
        assertTrue(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = 0f, targetFps = 23.976f))

        // New file with different fps: 23.976f -> 60.0f -> requires call
        assertTrue(SurfaceFrameRateHelper.shouldUpdateFrameRate(currentVotedFps = 23.976f, targetFps = 60.0f))
    }
}
