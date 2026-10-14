package com.vibeplayer.playback

/**
 * Pure policy logic for PlaybackService lifecycle decisions.
 * Decoupled from Android Service classes so it can be thoroughly unit-tested.
 */
object PlaybackLifecyclePolicy {

    /**
     * Determines whether the PlaybackService should stop itself when client binding state or playback state changes.
     *
     * Rules:
     * - If at least one client (e.g. PlayerActivity, PiP) is bound, the service must remain running.
     * - If no client is bound:
     *   - If background audio is OFF, the service must stop immediately.
     *   - If background audio is ON, the service continues running ONLY if active playback is in progress
     *     (not paused and not at EOF). If paused or at EOF, the service must stop itself.
     */
    fun shouldStopService(
        boundClients: Int,
        backgroundAudioEnabled: Boolean,
        isPaused: Boolean,
        isEof: Boolean
    ): Boolean {
        if (boundClients > 0) return false
        if (!backgroundAudioEnabled) return true
        return isPaused || isEof
    }

    /**
     * Determines whether playback should continue when the app task is removed from recents (onTaskRemoved).
     *
     * If background audio is enabled and playback is actively running, playback continues with foreground notification.
     * Otherwise, the service stops immediately.
     */
    fun shouldKeepPlayingOnTaskRemoved(
        backgroundAudioEnabled: Boolean,
        isPaused: Boolean,
        isEof: Boolean
    ): Boolean {
        return backgroundAudioEnabled && !isPaused && !isEof
    }
}
