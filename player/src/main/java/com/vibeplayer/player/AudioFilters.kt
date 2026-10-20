package com.vibeplayer.player

/** 10-band graphic equalizer (FFmpeg `equalizer` filters, one octave wide), gains in dB. */
data class Equalizer(val enabled: Boolean = false, val gains: List<Int> = FLAT) {

    /** On and not flat. */
    val isActive: Boolean get() = enabled && gains.any { it != 0 }

    fun normalized() = Equalizer(enabled, List(BANDS.size) { i -> (gains.getOrNull(i) ?: 0).coerceIn(MIN_DB, MAX_DB) })

    /**
     * The lavfi graph, or null when inactive. The loudest boost is taken off first (volume=-NdB) so
     * boosted bands do not clip; bands at 0 dB are left out.
     */
    fun lavfi(): String? {
        val n = normalized()
        if (!n.isActive) return null
        val pre = n.gains.max().coerceAtLeast(0)
        val parts = mutableListOf<String>()
        if (pre > 0) parts.add("volume=-${pre}dB")
        n.gains.forEachIndexed { i, g -> if (g != 0) parts.add("equalizer=f=${BANDS[i]}:t=o:w=1:g=$g") }
        return parts.joinToString(",")
    }

    /** The preset these gains are, or null for custom gains. */
    fun presetName(): String? = PRESETS.firstOrNull { it.second == normalized().gains }?.first

    companion object {
        val BANDS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val MIN_DB = -12
        const val MAX_DB = 12
        val FLAT = List(10) { 0 }
        val PRESETS: List<Pair<String, List<Int>>> = listOf(
            "Flat" to FLAT,
            "Bass boost" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
            "Voice" to listOf(-4, -3, -1, 1, 3, 4, 4, 2, 0, -2),
            "Treble" to listOf(0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
            "Loudness" to listOf(5, 4, 2, 0, -1, -1, 0, 2, 4, 5)
        )

        fun bandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

        /** "0,2,-3,..." (settings) to gains; anything unreadable is flat. */
        fun parseGains(s: String?): List<Int> {
            val v = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return FLAT
            return if (v.size == BANDS.size) v.map { it.coerceIn(MIN_DB, MAX_DB) } else FLAT
        }

        fun encodeGains(g: List<Int>): String = g.joinToString(",")
    }
}

/** mpv `af` chains the app sets. */
object AudioFilters {

    /**
     * Night mode: FFmpeg dynaudnorm evens out loud music and effects against quiet dialogue (short
     * frames so it reacts within a scene, gain capped at 10x so silence is not pumped up).
     */
    const val NIGHT_MODE = "@night:lavfi=[dynaudnorm=f=150:g=15:p=0.9:m=10]"

    /** The whole `af` value ("" clears it): the equalizer first, then night mode evens out the result. */
    fun chain(nightMode: Boolean, equalizer: Equalizer = Equalizer()): String {
        val parts = mutableListOf<String>()
        equalizer.lavfi()?.let { parts.add("@eq:lavfi=[$it]") }
        if (nightMode) parts.add(NIGHT_MODE)
        return parts.joinToString(",")
    }
}
