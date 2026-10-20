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

    /**
     * The mpv `ab-loop-a` / `ab-loop-b` values: both "no" until B is set too. mpv loops from A to the end
     * of the file when only A is set, which would keep the file from ever ending while B is awaited.
     */
    fun mpvValues(a: Long?, b: Long?): Pair<String, String> =
        if (a != null && b != null) seconds(a) to seconds(b) else "no" to "no"

    /** mpv seconds with millisecond precision. */
    fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms / 1000.0)
}
