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

/** Text colour for plain-text subtitles (mpv `sub-color`). */
enum class SubColor(val label: String, val mpv: String) {
    WHITE("White", "#FFFFFF"),
    YELLOW("Yellow", "#FFE14D"),
    CYAN("Cyan", "#7FE8FF"),
    GREEN("Green", "#9CFF8A")
}

/** Outline thickness (mpv `sub-outline-size`); NORMAL is mpv's own default. */
enum class SubOutline(val label: String, val mpv: String) {
    NONE("None", "0"),
    THIN("Thin", "1.0"),
    NORMAL("Normal", "1.65"),
    THICK("Thick", "3.0")
}

/**
 * Global subtitle appearance (settings). [assOverride] false leaves styled ASS subtitles untouched: colour,
 * outline, box and bold then style plain-text subtitles (SRT, VTT, ...) only, like scale and position.
 */
data class SubtitleStyle(
    val scale: Double = DEFAULT_SCALE,
    val pos: Int = DEFAULT_POS,
    val assOverride: Boolean = false,
    val color: SubColor = SubColor.WHITE,
    val outline: SubOutline = SubOutline.NORMAL,
    /** A dark box behind the text (mpv `sub-border-style=background-box`, box colour `sub-back-color`). */
    val box: Boolean = false,
    val bold: Boolean = false
) {
    fun normalized() = copy(scale = normalizeScale(scale), pos = normalizePos(pos))

    /** mpv option name/value pairs, usable both as loadfile options and as live properties. */
    fun mpvOptions(): List<Pair<String, String>> {
        val s = normalized()
        return listOf(
            "sub-scale" to String.format(Locale.US, "%.2f", s.scale),
            "sub-pos" to s.pos.toString(),
            // "no": styled ASS is rendered exactly as authored (sub-scale/sub-pos then only affect plain text subs).
            // "force": the style above overrides ASS styling too.
            "sub-ass-override" to if (s.assOverride) "force" else "no",
            "sub-color" to s.color.mpv,
            "sub-outline-size" to s.outline.mpv,
            "sub-border-style" to if (s.box) "background-box" else "outline-and-shadow",
            "sub-bold" to if (s.bold) "yes" else "no"
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
