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
    SOFTWARE("no")
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
    val reason: String
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
    val isQualityMode: Boolean = false
) {
    val chosenRowDescription: String
        get() = "${format.label} -> ${decodeMode.hwdec} / $renderer ($vo)"
}

/**
 * Per-format best configuration table chosen before opening (TASKS.md P6-07).
 *
 * Measured on Snapdragon 778G / Adreno 642L.
 */
object FormatProfileTable {

    val H264_8BIT_PROFILE = FormatProfile(
        format = VideoFormat.H264_8BIT,
        decodeMode = DecodeMode.MEDIACODEC_COPY,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu",
        reason = "zero-copy stalled 12/40 and 5/60 opens; copy-back 0/80 on GLES, 5/80 on Vulkan"
    )

    val H264_10BIT_PROFILE = FormatProfile(
        format = VideoFormat.H264_10BIT,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu",
        reason = "no hardware decoder on the 778G"
    )

    val HEVC_PROFILE = FormatProfile(
        format = VideoFormat.HEVC,
        decodeMode = DecodeMode.MEDIACODEC_ZERO_COPY,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "0/20 stalls zero-copy; lowest power path"
    )

    val VP9_PROFILE = FormatProfile(
        format = VideoFormat.VP9,
        decodeMode = DecodeMode.MEDIACODEC_ZERO_COPY,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "0/20 stalls"
    )

    val AV1_PROFILE = FormatProfile(
        format = VideoFormat.AV1,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu",
        reason = "fewest drops in the 4K test (359 vs 517/565)"
    )

    val OTHER_PROFILE = FormatProfile(
        format = VideoFormat.OTHER,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "safe default"
    )

    val ALL_PROFILES: Map<VideoFormat, FormatProfile> = mapOf(
        VideoFormat.H264_8BIT to H264_8BIT_PROFILE,
        VideoFormat.H264_10BIT to H264_10BIT_PROFILE,
        VideoFormat.HEVC to HEVC_PROFILE,
        VideoFormat.VP9 to VP9_PROFILE,
        VideoFormat.AV1 to AV1_PROFILE,
        VideoFormat.OTHER to OTHER_PROFILE
    )

    fun getProfile(format: VideoFormat): FormatProfile =
        ALL_PROFILES[format] ?: OTHER_PROFILE

    /**
     * Resolves the target configuration given the video format, playback mode, renderer setting,
     * and whether Vulkan is available for this format on the current driver build.
     *
     * Rules:
     * 1. Setting Renderer = GLES forces OpenGL ES.
     * 2. Setting Renderer = VULKAN forces Vulkan if available, else falls back to GLES.
     * 3. Setting Renderer = AUTO:
     *    - Quality mode overrides renderer column with gpu-next on Vulkan (if available, else GLES).
     *    - Efficient mode picks the table's preferred renderer (Vulkan if available, else GLES).
     * 4. In Quality mode, vo is "gpu-next". In Efficient mode, vo is "gpu".
     * 5. In Quality mode, decode mode uses mediacodec-copy for hardware-capable formats, and software
     *    for software-only formats.
     */
    fun resolve(
        format: VideoFormat,
        mode: PlaybackMode,
        rendererSetting: RendererSetting,
        isVulkanAvailableForFormat: Boolean
    ): ResolvedConfiguration {
        val profile = getProfile(format)

        val targetRenderer = when (rendererSetting) {
            RendererSetting.GLES -> Renderer.GLES
            RendererSetting.VULKAN -> {
                if (isVulkanAvailableForFormat) Renderer.VULKAN else Renderer.GLES
            }
            RendererSetting.AUTO -> {
                if (mode == PlaybackMode.QUALITY) {
                    if (isVulkanAvailableForFormat) Renderer.VULKAN else Renderer.GLES
                } else {
                    if (profile.preferredRenderer == Renderer.VULKAN && isVulkanAvailableForFormat) {
                        Renderer.VULKAN
                    } else {
                        Renderer.GLES
                    }
                }
            }
        }

        val targetVo = if (mode == PlaybackMode.QUALITY) {
            "gpu-next"
        } else {
            profile.vo
        }

        val targetDecode = if (mode == PlaybackMode.QUALITY) {
            if (profile.decodeMode == DecodeMode.SOFTWARE) DecodeMode.SOFTWARE else DecodeMode.MEDIACODEC_COPY
        } else {
            profile.decodeMode
        }

        return ResolvedConfiguration(
            format = format,
            profile = profile,
            decodeMode = targetDecode,
            renderer = targetRenderer,
            vo = targetVo,
            reason = profile.reason,
            isQualityMode = (mode == PlaybackMode.QUALITY)
        )
    }
}
