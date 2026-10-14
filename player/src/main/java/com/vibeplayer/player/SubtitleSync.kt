package com.vibeplayer.player

import java.util.Locale

/** P4-13: pure logic for subtitle/audio delay stepping and the global subtitle style. */
object SyncDelay {
    const val STEP_MS = 50
    const val MAX_MS = 30_000

    fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS)

    /** One -/+ press: [direction] is -1 or +1; result stays a multiple of the step when [current] is. */
    fun step(current: Int, direction: Int): Int = clamp(current + STEP_MS * direction.coerceIn(-1, 1))

    /** mpv expects seconds. */
    fun toSeconds(ms: Int): String = String.format(Locale.US, "%.3f", clamp(ms) / 1000.0)

    fun format(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"

    /** loadfile options for a non-zero delay; empty for 0 so mpv defaults stay untouched. */
    fun loadOptions(subDelayMs: Int, audioDelayMs: Int): List<String> = buildList {
        if (subDelayMs != 0) add("sub-delay=${toSeconds(subDelayMs)}")
        if (audioDelayMs != 0) add("audio-delay=${toSeconds(audioDelayMs)}")
    }
}

/** Global subtitle appearance (settings). [assOverride] false leaves styled ASS subtitles untouched. */
data class SubtitleStyle(
    val scale: Double = DEFAULT_SCALE,
    val pos: Int = DEFAULT_POS,
    val assOverride: Boolean = false
) {
    fun normalized() = SubtitleStyle(normalizeScale(scale), normalizePos(pos), assOverride)

    /** mpv option name/value pairs, usable both as loadfile options and as live properties. */
    fun mpvOptions(): List<Pair<String, String>> {
        val s = normalized()
        return listOf(
            "sub-scale" to String.format(Locale.US, "%.2f", s.scale),
            "sub-pos" to s.pos.toString(),
            // "no": styled ASS is rendered exactly as authored (sub-scale/sub-pos then only affect plain text subs).
            // "force": the style above overrides ASS styling too.
            "sub-ass-override" to if (s.assOverride) "force" else "no"
        )
    }

    companion object {
        const val MIN_SCALE = 0.6
        const val MAX_SCALE = 2.0
        const val SCALE_STEP = 0.1
        const val DEFAULT_SCALE = 1.0
        const val DEFAULT_POS = 100

        fun normalizeScale(v: Double): Double {
            val c = if (v.isNaN()) DEFAULT_SCALE else v.coerceIn(MIN_SCALE, MAX_SCALE)
            return Math.round(c * 20.0) / 20.0 // 0.05 grid
        }

        fun normalizePos(v: Int): Int = v.coerceIn(0, 100)
    }
}
