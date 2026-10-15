package com.vibeplayer.player

/**
 * Pure helper for the display refresh rate matching decision (Surface.setFrameRate).
 *
 * Android 11+ (Surface.setFrameRate, API 30/31) allows video surfaces to vote for their content
 * frame rate so the display refresh rate can seamlessly switch (e.g. 24 Hz, 48 Hz, 60 Hz, 120 Hz).
 */
object SurfaceFrameRateHelper {
    const val MAX_VALID_FPS = 240.0f
    const val CLEAR_FPS = 0.0f

    /**
     * Checks if [fps] is a valid video frame rate: not null, finite, positive, and <= 240 fps.
     * Guards against 0, negative, NaN, infinity, and bogus VFR timebases (> 240).
     */
    fun isValidFps(fps: Double?): Boolean {
        if (fps == null) return false
        if (fps.isNaN() || fps.isInfinite()) return false
        return fps > 0.0 && fps <= MAX_VALID_FPS
    }

    fun isValidFps(fps: Float?): Boolean {
        if (fps == null) return false
        if (fps.isNaN() || fps.isInfinite()) return false
        return fps > 0.0f && fps <= MAX_VALID_FPS
    }

    /**
     * Pure function to decide the frame rate (in fps) to vote to [Surface.setFrameRate].
     *
     * Returns 0.0f (clear vote) when:
     * - [matchDisplayRefreshRate] setting is disabled;
     * - [isSurfaceAlive] is false;
     * - [isPlayerActive] is false (stopped, exiting);
     * - [isPaused] is true;
     * - [containerFps] is invalid (null, 0, NaN, >240).
     *
     * Returns the exact container fps as Float when playing with a valid fps and setting enabled.
     */
    fun decideFrameRate(
        matchDisplayRefreshRate: Boolean,
        containerFps: Double?,
        isPaused: Boolean,
        isSurfaceAlive: Boolean = true,
        isPlayerActive: Boolean = true
    ): Float {
        if (!matchDisplayRefreshRate || !isSurfaceAlive || !isPlayerActive || isPaused) {
            return CLEAR_FPS
        }
        if (!isValidFps(containerFps)) {
            return CLEAR_FPS
        }
        return containerFps!!.toFloat()
    }

    /**
     * Pure function to determine if a call to [Surface.setFrameRate] is needed.
     * Ensures setFrameRate is called once per change (not per frame).
     */
    fun shouldUpdateFrameRate(
        currentVotedFps: Float?,
        targetFps: Float
    ): Boolean {
        if (currentVotedFps == null) {
            // New surface or first vote: only vote if target > 0f (no need to vote 0f if never voted)
            return targetFps > 0f
        }
        return currentVotedFps != targetFps
    }
}
