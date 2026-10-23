package com.vibeplayer.player

/**
 * One parsed format_table.json (see tools/bench/make_table.py): rows per Vulkan driver hash plus a
 * default used when the phone's driver matches none of them.
 */
class FormatTableFile(
    val tables: Map<String, Map<VideoFormat, FormatProfile>>,
    val default: Map<VideoFormat, FormatProfile>
) {
    /** Rows for [driverHash] (missing formats filled from the default), or the default. */
    fun select(driverHash: String?): FormatTableSelection {
        val matched = driverHash?.let { tables[it.lowercase()] }
        return if (matched != null) {
            FormatTableSelection(default + matched, driverHash.lowercase())
        } else {
            FormatTableSelection(default, FormatProfileTable.SOURCE_DEFAULT)
        }
    }
}

data class FormatTableSelection(val rows: Map<VideoFormat, FormatProfile>, val source: String)

/**
 * Per-format best configuration chosen before opening (TASKS.md P6-06 / P6-07).
 *
 * The rows are data: player/src/main/assets/format_table.json, generated from the benchmark results by
 * tools/bench/make_table.py and keyed by the Vulkan driver hash. The app installs the asset at start
 * ([install]); the constants below are only the fallback until then or if the asset cannot be read,
 * and mirror the table shipped with this build (P6-08, driver c0b92d25178c8b21).
 */
object FormatProfileTable {

    const val SOURCE_DEFAULT = "default"
    const val SOURCE_BUILT_IN = "built-in"
    const val ASSET_NAME = "format_table.json"

    val H264_8BIT_PROFILE = FormatProfile(
        format = VideoFormat.H264_8BIT,
        decodeMode = DecodeMode.MEDIACODEC_COPY,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu-next",
        reason = "P6-08: 0 drops, 549 mA (GLES zero-copy 637 mA, which also stalls at start, B-08)",
        glesDecodeMode = DecodeMode.MEDIACODEC_COPY,
        glesVo = "gpu",
        glesReason = "P6-08 GLES: copy-back 0 drops 720 mA; zero-copy stalls at start (B-08)"
    )

    val H264_10BIT_PROFILE = FormatProfile(
        format = VideoFormat.H264_10BIT,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu",
        reason = "no hardware decoder on the 778G",
        glesDecodeMode = DecodeMode.SOFTWARE,
        glesVo = "gpu",
        glesReason = "no hardware decoder on the 778G",
        operatingRate = OperatingRatePolicy.OFF
    )

    val HEVC_PROFILE = FormatProfile(
        format = VideoFormat.HEVC,
        decodeMode = DecodeMode.MEDIACODEC_ZERO_COPY,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "P6-08: 1241 drops (Vulkan 1207, equal within 5%), 604 mA vs 1254 mA"
    )

    val VP9_PROFILE = FormatProfile(
        format = VideoFormat.VP9,
        decodeMode = DecodeMode.MEDIACODEC_ZERO_COPY,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "P6-08: 0 drops, 339 mA vs 794-1026 mA"
    )

    val AV1_PROFILE = FormatProfile(
        format = VideoFormat.AV1,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.VULKAN,
        vo = "gpu",
        reason = "P6-08: fewest drops, 362 vs 385 gpu-next and 918 GLES",
        glesDecodeMode = DecodeMode.SOFTWARE,
        glesVo = "gpu",
        glesReason = "P6-08 GLES: 918 drops",
        operatingRate = OperatingRatePolicy.OFF
    )

    val OTHER_PROFILE = FormatProfile(
        format = VideoFormat.OTHER,
        decodeMode = DecodeMode.SOFTWARE,
        preferredRenderer = Renderer.GLES,
        vo = "gpu",
        reason = "safe default",
        operatingRate = OperatingRatePolicy.OFF
    )

    val BUILT_IN_PROFILES: Map<VideoFormat, FormatProfile> = mapOf(
        VideoFormat.H264_8BIT to H264_8BIT_PROFILE,
        VideoFormat.H264_10BIT to H264_10BIT_PROFILE,
        VideoFormat.HEVC to HEVC_PROFILE,
        VideoFormat.VP9 to VP9_PROFILE,
        VideoFormat.AV1 to AV1_PROFILE,
        VideoFormat.OTHER to OTHER_PROFILE
    )

    @Volatile
    private var active = FormatTableSelection(BUILT_IN_PROFILES, SOURCE_BUILT_IN)

    /** Rows in use and where they came from (a driver hash, "default" or "built-in"). */
    val activeSource: String get() = active.source
    val isInstalled: Boolean get() = active.source != SOURCE_BUILT_IN

    fun getProfile(format: VideoFormat): FormatProfile =
        active.rows[format] ?: BUILT_IN_PROFILES[format] ?: OTHER_PROFILE

    /** Installs the rows for [driverHash] from [json]; returns the selected source. Throws on bad JSON. */
    fun install(json: String, driverHash: String?): String {
        val selection = parse(json).select(driverHash)
        active = selection.copy(rows = BUILT_IN_PROFILES + selection.rows)
        return selection.source
    }

    /** Back to the built-in rows (tests). */
    fun resetToBuiltIn() {
        active = FormatTableSelection(BUILT_IN_PROFILES, SOURCE_BUILT_IN)
    }

    fun parse(json: String): FormatTableFile {
        val root = JsonLite.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("table is not an object")
        val version = (root["version"] as? Double)?.toInt()
        require(version == 1) { "unsupported table version $version" }
        val default = parseRows((root["default"] as? Map<*, *>)?.get("rows"))
        require(default.isNotEmpty()) { "table has no default rows" }
        val tables = LinkedHashMap<String, Map<VideoFormat, FormatProfile>>()
        (root["tables"] as? List<*>)?.forEach { t ->
            val obj = t as? Map<*, *> ?: return@forEach
            val hash = (obj["driverHash"] as? String)?.lowercase() ?: return@forEach
            tables[hash] = parseRows(obj["rows"])
        }
        return FormatTableFile(tables, default)
    }

    private fun parseRows(rows: Any?): Map<VideoFormat, FormatProfile> {
        val obj = rows as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<VideoFormat, FormatProfile>()
        for ((key, value) in obj) {
            val format = VideoFormat.entries.firstOrNull { it.formatKey == key } ?: continue
            val row = value as? Map<*, *> ?: continue
            val decode = (row["hwdec"] as? String)?.let(DecodeMode::fromHwdec) ?: continue
            val renderer = when ((row["renderer"] as? String)?.lowercase()) {
                "vulkan" -> Renderer.VULKAN
                "gles", "opengl" -> Renderer.GLES
                else -> continue
            }
            val vo = (row["vo"] as? String)?.takeIf { it == "gpu" || it == "gpu-next" } ?: continue
            val gles = row["gles"] as? Map<*, *>
            out[format] = FormatProfile(
                format = format,
                decodeMode = decode,
                preferredRenderer = renderer,
                vo = vo,
                reason = row["reason"] as? String ?: "",
                glesDecodeMode = (gles?.get("hwdec") as? String)?.let(DecodeMode::fromHwdec),
                glesVo = (gles?.get("vo") as? String)?.takeIf { it == "gpu" || it == "gpu-next" },
                glesReason = gles?.get("reason") as? String,
                operatingRate = OperatingRatePolicy.fromToken(row["operatingRate"] as? String)
            )
        }
        return out
    }

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
     * 4. Quality mode: vo is "gpu-next"; decode is mediacodec-copy for hardware formats, software stays.
     * 5. Efficient mode: the row's vo and decode. A Vulkan row that lands on OpenGL ES uses its "gles"
     *    values (measured separately; gpu-next is never carried over to GLES unmeasured).
     * 6. Zero-copy (hwdec=mediacodec) needs OpenGL ES: mpv's MediaCodec interop (hwdec_aimagereader)
     *    refuses a non-GL renderer, so on Vulkan it becomes mediacodec-copy.
     * 7. Renderer AUTO or GLES, copy-back decode of a clip above 1440p ([clip] size known): zero-copy on
     *    OpenGL ES instead (gpu-next in Quality mode). Copy-back cannot carry 4K: hevc10_4k (4K 60 fps 10-bit)
     *    dropped 48 of 60 frames/s on Vulkan and 25 on GLES, 0 with zero-copy (2026-10-23). The upscaling
     *    shaders that need Vulkan have nothing to upscale on a source larger than the screen.
     */
    fun resolve(
        format: VideoFormat,
        mode: PlaybackMode,
        rendererSetting: RendererSetting,
        isVulkanAvailableForFormat: Boolean,
        clip: ClipInfo? = null
    ): ResolvedConfiguration {
        val selection = active
        val profile = selection.rows[format] ?: getProfile(format)
        val quality = mode == PlaybackMode.QUALITY

        val targetRenderer = when (rendererSetting) {
            RendererSetting.GLES -> Renderer.GLES
            RendererSetting.VULKAN -> if (isVulkanAvailableForFormat) Renderer.VULKAN else Renderer.GLES
            RendererSetting.AUTO -> when {
                quality -> if (isVulkanAvailableForFormat) Renderer.VULKAN else Renderer.GLES
                profile.preferredRenderer == Renderer.VULKAN && isVulkanAvailableForFormat -> Renderer.VULKAN
                else -> Renderer.GLES
            }
        }

        val glesFallback = !quality && targetRenderer == Renderer.GLES && profile.preferredRenderer == Renderer.VULKAN

        val targetVo = when {
            quality -> "gpu-next"
            glesFallback -> profile.glesVo ?: "gpu"
            targetRenderer == Renderer.GLES && profile.vo == "gpu-next" -> "gpu"
            else -> profile.vo
        }

        var targetDecode = when {
            quality -> if (profile.decodeMode == DecodeMode.SOFTWARE) DecodeMode.SOFTWARE else DecodeMode.MEDIACODEC_COPY
            glesFallback -> profile.glesDecodeMode ?: profile.decodeMode
            else -> profile.decodeMode
        }
        if (targetRenderer == Renderer.VULKAN && targetDecode == DecodeMode.MEDIACODEC_ZERO_COPY) {
            targetDecode = DecodeMode.MEDIACODEC_COPY
        }
        if (targetDecode == DecodeMode.MEDIACODEC_COPY && rendererSetting != RendererSetting.VULKAN &&
            isAboveCopyBackLimit(clip)
        ) {
            return ResolvedConfiguration(
                format = format,
                profile = profile,
                decodeMode = DecodeMode.MEDIACODEC_ZERO_COPY,
                renderer = Renderer.GLES,
                vo = if (quality) "gpu-next" else profile.glesVo ?: "gpu",
                reason = "${clip?.width}x${clip?.height} is too large for copy-back, zero-copy on OpenGL ES",
                isQualityMode = quality,
                tableSource = selection.source
            )
        }

        return ResolvedConfiguration(
            format = format,
            profile = profile,
            decodeMode = targetDecode,
            renderer = targetRenderer,
            vo = targetVo,
            reason = if (glesFallback) profile.glesReason ?: profile.reason else profile.reason,
            isQualityMode = quality,
            tableSource = selection.source
        )
    }

    /** Above 2560x1440 pixels: copy-back drops frames there (rule 7). */
    internal fun isAboveCopyBackLimit(clip: ClipInfo?): Boolean {
        val w = clip?.width ?: return false
        val h = clip.height ?: return false
        return w.toLong() * h > 2560L * 1440L
    }
}
