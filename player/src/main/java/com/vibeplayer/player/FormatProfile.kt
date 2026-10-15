package com.vibeplayer.player

/**
 * Recognized video formats for per-format configuration selection.
 */
enum class VideoFormat(val label: String, val formatKey: String) {
    H264_8BIT("H.264 8-bit", "h264_8bit"),
    H264_10BIT("H.264 10-bit (Hi10P)", "h264_10bit"),
    HEVC("HEVC 8/10-bit", "hevc"),
    VP9("VP9", "vp9"),
    AV1("AV1 (any)", "av1"),
    OTHER("other", "other")
}

/**
 * Decoding modes for video decoding in mpv.
 */
enum class DecodeMode(val hwdec: String) {
    MEDIACODEC_COPY("mediacodec-copy"),
    MEDIACODEC_ZERO_COPY("mediacodec"),
    SOFTWARE("no");

    companion object {
        fun fromHwdec(value: String): DecodeMode? = entries.firstOrNull { it.hwdec == value }
    }
}

/**
 * User-selected renderer setting.
 */
enum class RendererSetting {
    AUTO,
    VULKAN,
    GLES
}

/**
 * A per-format profile entry defining the measured best configuration on this phone.
 */
data class FormatProfile(
    val format: VideoFormat,
    val decodeMode: DecodeMode,
    val preferredRenderer: Renderer,
    val vo: String,
    val reason: String,
    /** Used instead of [decodeMode]/[vo]/[reason] when the row prefers Vulkan but runs on OpenGL ES. */
    val glesDecodeMode: DecodeMode? = null,
    val glesVo: String? = null,
    val glesReason: String? = null,
    /** MediaCodec clock hint policy (format_table.json "operatingRate", see [OperatingRate]). */
    val operatingRate: OperatingRatePolicy = OperatingRatePolicy.AUTO
)

/**
 * The final configuration picked before opening the video.
 */
data class ResolvedConfiguration(
    val format: VideoFormat,
    val profile: FormatProfile,
    val decodeMode: DecodeMode,
    val renderer: Renderer,
    val vo: String,
    val reason: String,
    val isQualityMode: Boolean = false,
    /** Which table the row came from: a driver hash, "default" or "built-in". */
    val tableSource: String = ""
) {
    val chosenRowDescription: String
        get() = "${format.label} -> ${decodeMode.hwdec} / $renderer ($vo)" +
            if (tableSource.isNotEmpty()) " [table ${tableSource.take(8)}]" else ""
}

