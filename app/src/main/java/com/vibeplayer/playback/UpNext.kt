package com.vibeplayer.playback

/** The next file the "Up next" card counts down to (P12-02). */
data class UpNext(
    /** The file that ended; the countdown is dropped if another file opens meanwhile. */
    val endedPath: String,
    val path: String,
    val title: String,
    /** SystemClock.elapsedRealtime() when the next file starts. */
    val endsAtElapsedMs: Long
) {
    /** Whole seconds left at [nowElapsedMs], rounded up (5, 4, ... 1, then 0). */
    fun secondsLeft(nowElapsedMs: Long): Int =
        ((endsAtElapsedMs - nowElapsedMs).coerceAtLeast(0L) + 999L).div(1000L).toInt()
}

/** A file that opened at its saved position (P12-01), for the "Start over" chip. */
data class ResumeInfo(val path: String, val positionMs: Long, val generation: Long)

/** Speed and auto-skip saved for the current file's folder (P12-04); nulls follow the app. */
data class FolderShow(val folderPath: String? = null, val speed: Float? = null, val autoSkipChapters: Boolean? = null)
