package com.vibeplayer.playback

/**
 * Pure policy logic for PlaybackService lifecycle decisions.
 * Decoupled from Android Service classes so it can be thoroughly unit-tested.
 */
object PlaybackLifecyclePolicy {

    /** 10 minutes in milliseconds before stopping the service when paused in background. */
    const val PAUSED_STOP_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Determines whether the PlaybackService should stop itself when client binding state or playback state changes.
     *
     * Rules:
     * - If at least one client (e.g. PlayerActivity, PiP) is bound, the service must remain running.
     * - If no client is bound:
     *   - If background audio is OFF, the service must stop immediately.
     *   - If background audio is ON:
     *     - If at EOF, the service must stop itself.
     *     - If paused, the service stays alive until the 10-minute paused timeout expires (or notification dismissed).
     *     - If actively playing, the service must not stop.
     */
    fun shouldStopService(
        boundClients: Int,
        backgroundAudioEnabled: Boolean,
        isPaused: Boolean,
        isEof: Boolean,
        pausedTimeoutExpired: Boolean = false
    ): Boolean {
        if (boundClients > 0) return false
        if (!backgroundAudioEnabled) return true
        if (isEof) return true
        if (isPaused) return pausedTimeoutExpired
        return false
    }

    /**
     * Determines whether the service should remain in the foreground.
     *
     * When paused with no clients bound, the service leaves the foreground (detaching the notification
     * so it becomes swipe-dismissable), and re-enters the foreground when playback resumes.
     */
    fun shouldBeInForeground(
        boundClients: Int,
        isPaused: Boolean,
        resumeOnFocusGain: Boolean = false
    ): Boolean {
        // P10-10: a transient audio-focus loss (call, navigation prompt) keeps the foreground service, so
        // the resume on AUDIOFOCUS_GAIN still has the while-in-use capability (Android 17 background-audio
        // hardening; the timeout stops the service after PAUSED_STOP_TIMEOUT_MS).
        if (boundClients == 0 && isPaused && !resumeOnFocusGain) return false
        return true
    }

    /**
     * P10-10: audio (focus request, playback) that starts while no activity is bound needs a running
     * mediaPlayback foreground service first. True when [startForeground] must be called before resuming.
     */
    fun needsForegroundBeforeResume(boundClients: Int, isForeground: Boolean): Boolean =
        boundClients == 0 && !isForeground

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
