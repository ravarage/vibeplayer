package com.vibeplayer.player

/**
 * Per-row policy of format_table.json ("operatingRate"): whether a MediaCodec-decoded file gets an
 * operating-rate hint (see [OperatingRate]).
 */
enum class OperatingRatePolicy(val token: String) {
    /** Hint about 2 x the clip's frame rate (see [OperatingRate.hint]). */
    AUTO("auto"),
    /** No hint: the codec picks its clocks itself (FFmpeg's default). */
    OFF("off");

    companion object {
        /** Unknown or missing tokens read as [AUTO], the measured default (P6-08 fix). */
        fun fromToken(token: String?): OperatingRatePolicy =
            entries.firstOrNull { it.token == token?.trim()?.lowercase() } ?: AUTO
    }
}

/**
 * What the format detection (MediaExtractor, before the open) learned about the video track.
 * [fps] is null when the container does not state a usable frame rate.
 */
data class ClipInfo(
    val format: VideoFormat,
    val fps: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 10 bits or more per sample (bit-depth key or a Main10/Profile2 codec profile). */
    val tenBit: Boolean = false,
    /** PQ or HLG transfer stated by the container/codec (KEY_COLOR_TRANSFER), null for SDR or unknown (P10-09). */
    val hdrTransfer: HdrTransfer? = null
)

/**
 * P6-08 fix: MediaCodec decoder clock hint.
 *
 * FFmpeg's MediaCodec wrapper sets neither `frame-rate` nor `operating-rate`, so the vendor codec picks
 * its own clocks; as mpv drives it, the HEVC Main10 4K 60 fps clip then decoded at ~38 fps (1027 VO drops
 * in 25 s). `vd-lavc-o=operating_rate=120` gave 0 drops (docs/BENCHMARK.md, "HEVC 10-bit 4K drops").
 * The hint is passed per file as a loadfile option, so a file without one never inherits it.
 */
object OperatingRate {
    const val MIN_RATE = 60
    const val MAX_RATE = 240
    /** Used when the frame rate is unknown but the clip is heavy (>= 4K or 10-bit HEVC). */
    const val UNKNOWN_FPS_HEAVY_RATE = 120
    /** Longest side from which a clip counts as 4K. */
    const val UHD_LONG_SIDE = 3840
    /** Container frame rates above this are timebases of variable-rate files (e.g. 1000, 90000), not fps. */
    const val MAX_PLAUSIBLE_FPS = 480.0

    private const val AV_OPTION = "vd-lavc-o"

    /** A container frame rate that can be trusted, or null. */
    fun sanitizeFps(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_PLAUSIBLE_FPS }

    /** True for the hwdec values that decode with MediaCodec (zero-copy and copy-back). */
    fun isMediaCodec(hwdec: String?): Boolean = hwdec == "mediacodec" || hwdec == "mediacodec-copy"

    /** mpv's default `hwdec-codecs` (mpv 0.41 `video/decode/vd_lavc.c`, `hwdec_conf` defaults). */
    const val MPV_DEFAULT_HWDEC_CODECS = "h264,vc1,hevc,vp8,vp9,av1,prores,prores_raw,ffv1,dpx"

    /**
     * The mpv codec name of [format] for which FFmpeg has a `<codec>_mediacodec` decoder that can decode
     * it, or null. H.264 10-bit (Hi10P) and `other` give null: Android MediaCodec has no Hi10P decoder on
     * the target SoC (format_table.json) and `other` is not one known codec.
     */
    fun mediaCodecCodec(format: VideoFormat): String? = when (format) {
        VideoFormat.H264_8BIT -> "h264"
        VideoFormat.HEVC -> "hevc"
        VideoFormat.VP9 -> "vp9"
        VideoFormat.AV1 -> "av1"
        VideoFormat.H264_10BIT, VideoFormat.OTHER -> null
    }

    /** mpv's `hwdec_codec_allowed` (vd_lavc.c): [codec] is in the comma list [hwdecCodecs], or it says "all". */
    fun hwdecCodecAllowed(hwdecCodecs: String?, codec: String): Boolean =
        (hwdecCodecs ?: MPV_DEFAULT_HWDEC_CODECS).split(',').any { it == "all" || it == codec }

    /**
     * True when mpv will open a `<codec>_mediacodec` decoder for [format] with the handle's [hwdec],
     * [hwdecCodecs] and [gpuApi] (the values set on the mpv handle, not the requested profile).
     *
     * mpv applies `vd-lavc-o` to every AVCodecContext it opens (vd_lavc.c `init_avctx`), but only FFmpeg's
     * MediaCodec decoders have the `operating_rate` AVOption (mediacodecdec.c); any other decoder logs
     * `AVOption 'operating_rate' not found` (non-fatal). So the hint is only set when mpv's own selection
     * (vd_lavc.c `select_and_set_hwdec`) picks MediaCodec first:
     * - hwdec is `mediacodec` or `mediacodec-copy`;
     * - the codec is on `hwdec-codecs` (otherwise mpv goes straight to software decoding);
     * - zero-copy `mediacodec` is not on Vulkan: its device comes from the VO's AImageReader interop, which
     *   only the GL renderer offers, so mpv logs "Could not create device." and decodes in software.
     */
    fun mediaCodecOpens(hwdec: String?, format: VideoFormat, hwdecCodecs: String?, gpuApi: String?): Boolean {
        if (!isMediaCodec(hwdec)) return false
        val codec = mediaCodecCodec(format) ?: return false
        if (!hwdecCodecAllowed(hwdecCodecs, codec)) return false
        if (hwdec == "mediacodec" && gpuApi == "vulkan") return false
        return true
    }

    /**
     * The operating rate to request for [clip] decoded with [hwdec], or null for no hint.
     *
     * - only when mpv opens a MediaCodec decoder for it ([mediaCodecOpens], with the handle's
     *   [hwdecCodecs] and [gpuApi]) and policy [OperatingRatePolicy.AUTO];
     * - known fps: 2 x fps rounded up, clamped to [MIN_RATE]..[MAX_RATE] (24 -> 60, 30 -> 60, 60 -> 120);
     * - unknown fps: [UNKNOWN_FPS_HEAVY_RATE] for >= 4K or 10-bit HEVC, otherwise no hint.
     */
    fun hint(
        policy: OperatingRatePolicy,
        hwdec: String?,
        clip: ClipInfo?,
        hwdecCodecs: String? = null,
        gpuApi: String? = null
    ): Int? {
        if (policy != OperatingRatePolicy.AUTO || clip == null) return null
        if (!mediaCodecOpens(hwdec, clip.format, hwdecCodecs, gpuApi)) return null
        val fps = sanitizeFps(clip.fps)
        if (fps != null) {
            return kotlin.math.ceil(fps * 2.0 - 1e-6).toInt().coerceIn(MIN_RATE, MAX_RATE)
        }
        val longSide = maxOf(clip.width ?: 0, clip.height ?: 0)
        val heavy = longSide >= UHD_LONG_SIDE || (clip.format == VideoFormat.HEVC && clip.tenBit)
        return if (heavy) UNKNOWN_FPS_HEAVY_RATE else null
    }

    /**
     * The loadfile option for [rate], or null. Null as well when the debug `mpv_opts` already set
     * vd-lavc-o ([debugOptions]): a file-local value would silently replace it, and device A/B runs use
     * `mpv_opts 'vd-lavc-o=operating_rate=0'` (FFmpeg: 0 = unspecified) as the baseline without the hint.
     */
    fun loadOption(rate: Int?, debugOptions: Map<String, String> = emptyMap()): String? {
        if (rate == null || rate <= 0 || debugOptions.containsKey(AV_OPTION)) return null
        return "$AV_OPTION=operating_rate=$rate"
    }
}
