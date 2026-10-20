package com.vibeplayer.player

import kotlin.math.roundToInt

/**
 * Volume boost above 100 %: one volume scale for the swipe and the volume keys. Levels 0..systemMax
 * are the Android media volume (mpv at 100 %); levels above it keep the media volume at its maximum
 * and raise mpv's own volume up to [boostMax] %.
 */
object VolumeBoost {
    const val NONE = 100
    val CHOICES = listOf(NONE, 150, 200)

    /** mpv `volume-max`: the highest boost on offer. */
    const val MPV_VOLUME_MAX = 200

    fun normalizeMax(percent: Int): Int = if (percent in CHOICES) percent else NONE

    /** Extra levels above the media maximum, with about the same step size as the media steps. */
    fun boostSteps(systemMax: Int, boostMax: Int): Int {
        if (boostMax <= NONE || systemMax <= 0) return 0
        return (systemMax * (boostMax - NONE) / 100.0).roundToInt().coerceAtLeast(1)
    }

    fun totalLevels(systemMax: Int, boostMax: Int): Int = systemMax + boostSteps(systemMax, boostMax)

    data class Split(val systemVolume: Int, val mpvVolume: Double)

    /** Media volume and mpv volume for one [level] of the combined scale. */
    fun split(level: Int, systemMax: Int, boostMax: Int): Split {
        val steps = boostSteps(systemMax, boostMax)
        val l = level.coerceIn(0, systemMax + steps)
        if (l <= systemMax || steps == 0) return Split(l.coerceAtMost(systemMax), NONE.toDouble())
        val mpv = NONE + (l - systemMax) * (boostMax - NONE).toDouble() / steps
        return Split(systemMax, mpv)
    }

    /** The combined level for a media volume and an mpv volume (the inverse of [split]). */
    fun level(systemVolume: Int, mpvVolume: Double, systemMax: Int, boostMax: Int): Int {
        val steps = boostSteps(systemMax, boostMax)
        if (systemVolume < systemMax || steps == 0 || mpvVolume <= NONE) return systemVolume.coerceIn(0, systemMax)
        val extra = ((mpvVolume - NONE) * steps / (boostMax - NONE)).roundToInt().coerceIn(0, steps)
        return systemMax + extra
    }

    /** What the indicator shows: 0..100 for the media volume, then up to [boostMax]. */
    fun percent(level: Int, systemMax: Int, boostMax: Int): Int {
        if (systemMax <= 0) return 0
        val s = split(level, systemMax, boostMax)
        return if (s.systemVolume < systemMax) (s.systemVolume * 100.0 / systemMax).roundToInt() else s.mpvVolume.roundToInt()
    }
}
