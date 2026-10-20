package com.vibeplayer.player

/**
 * mpv's video equalizer: brightness, contrast, saturation and gamma, each -100..100 (0 = unchanged).
 * Applied by the GPU renderer (vo=gpu / gpu-next); the direct MediaCodec path (debug path A) ignores it.
 */
data class PictureAdjust(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0
) {
    val isDefault: Boolean get() = this == PictureAdjust()

    fun normalized() = PictureAdjust(clamp(brightness), clamp(contrast), clamp(saturation), clamp(gamma))

    /** mpv property name to value. */
    fun mpvProperties(): List<Pair<String, String>> = normalized().let {
        listOf(
            "brightness" to it.brightness.toString(),
            "contrast" to it.contrast.toString(),
            "saturation" to it.saturation.toString(),
            "gamma" to it.gamma.toString()
        )
    }

    companion object {
        fun clamp(v: Int) = v.coerceIn(-100, 100)
    }
}
