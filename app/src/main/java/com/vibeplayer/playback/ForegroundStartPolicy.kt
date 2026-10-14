package com.vibeplayer.playback

/** Which notification the service posts when it calls startForeground (or none: stay in background). */
enum class ForegroundNotice {
    NONE,

    /** Benchmark run: a minimal notification on a silent channel, only because Android requires one. */
    SILENT,

    /** The normal media notification. */
    MEDIA
}

/**
 * When PlaybackService must call startForeground (P6-03 fix, ANR 2026-10-14 21:44: "Context.startForeground
 * Service() did not then call Service.startForeground(): PlaybackService" during benchmark runs).
 *
 * Android requires startForeground within a few seconds of every startForegroundService, and also kills the
 * app if the service stops before that. The only startForegroundService in the app is PlayerViewModel's,
 * which sends an intent without an action. The benchmark flag is process-wide and set by PlayerActivity, so
 * it can change between that call and onStartCommand (a benchmark activity started meanwhile, or a stale flag
 * from an earlier run); the decision for a required start must not depend on it, only the notification does.
 * Intents with an action come from the notification's PendingIntent.getService (plain startService): those
 * never require foreground.
 */
object ForegroundStartPolicy {

    /** onStartCommand: [action] null = PlayerViewModel's startForegroundService, which must be answered. */
    fun onStartCommand(action: String?, benchmarkRun: Boolean, isForeground: Boolean): ForegroundNotice = when {
        isForeground -> ForegroundNotice.NONE
        action == null -> if (benchmarkRun) ForegroundNotice.SILENT else ForegroundNotice.MEDIA
        action == PlaybackService.ACTION_STOP || action == PlaybackService.ACTION_DISMISS -> ForegroundNotice.NONE
        else -> opportunistic(benchmarkRun)
    }

    /** onCreate and playback resuming: foreground is wanted for normal playback, never for a benchmark. */
    fun opportunistic(benchmarkRun: Boolean): ForegroundNotice =
        if (benchmarkRun) ForegroundNotice.NONE else ForegroundNotice.MEDIA

    /** PlayerViewModel: a benchmark run only binds (no notification wanted); the service copes either way. */
    fun shouldStartForegroundService(benchmarkRun: Boolean): Boolean = !benchmarkRun
}
