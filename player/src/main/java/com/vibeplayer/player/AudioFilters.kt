package com.vibeplayer.player

/** mpv `af` chains the app sets. */
object AudioFilters {

    /**
     * Night mode: FFmpeg dynaudnorm evens out loud music and effects against quiet dialogue (short
     * frames so it reacts within a scene, gain capped at 10x so silence is not pumped up).
     */
    const val NIGHT_MODE = "@night:lavfi=[dynaudnorm=f=150:g=15:p=0.9:m=10]"

    /** The whole `af` value for the current options ("" clears it). */
    fun chain(nightMode: Boolean): String = if (nightMode) NIGHT_MODE else ""
}
