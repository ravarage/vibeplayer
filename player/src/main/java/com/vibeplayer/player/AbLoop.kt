package com.vibeplayer.player

/** A-B repeat steps: set A, then B, then clear. */
object AbLoop {

    /**
     * The next (A, B) pair after a tap at [posMs]. B before A swaps them, so the loop is always A < B;
     * B at A (a double tap) is ignored and waits for a later position.
     */
    fun next(a: Long?, b: Long?, posMs: Long): Pair<Long?, Long?> = when {
        a == null -> posMs to null
        b == null && posMs == a -> a to null
        b == null -> minOf(a, posMs) to maxOf(a, posMs)
        else -> null to null
    }

    /** mpv seconds with millisecond precision. */
    fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms / 1000.0)
}
