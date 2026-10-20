package com.vibeplayer.data.db

/** When a file counts as watched: shared by resume (restart from 0) and the browser's ✓ mark. */
object WatchProgress {
    /** The last 5 % of a file counts as watched. */
    const val WATCHED_FRACTION = 0.95

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= durationMs * WATCHED_FRACTION

    fun isWatched(s: FileSettings?): Boolean = s != null && isWatched(s.positionMs, s.durationMs)
}
