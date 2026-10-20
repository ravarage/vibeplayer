package com.vibeplayer.ui.player

import android.content.pm.ActivityInfo

/** The player's orientation: from the video's shape (Auto, P1-09) or fixed by the Rotate button. */
enum class RotationMode(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait");

    fun next(): RotationMode = entries[(ordinal + 1) % entries.size]

    /** The requested orientation for a video of [width] x [height]; null while the size is unknown in Auto. */
    fun orientationFor(width: Int, height: Int): Int? = when (this) {
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        AUTO -> when {
            width <= 0 || height <= 0 -> null
            width >= height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
}
