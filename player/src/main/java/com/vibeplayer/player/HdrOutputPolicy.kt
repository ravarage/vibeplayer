package com.vibeplayer.player

/** HDR transfer function of the video content (P10-09). */
enum class HdrTransfer(val label: String) { PQ("PQ"), HLG("HLG") }

/**
 * Result of [HdrOutputPolicy.decide]: [active] says whether HDR output is requested, [reason] is the
 * short text for the debug overlay ("HDR: on (PQ)" / "HDR: off (setting off)").
 */
data class HdrDecision(val active: Boolean, val reason: String, val transfer: HdrTransfer? = null) {
    val overlayText: String get() = if (active) "on ($reason)" else "off ($reason)"

    companion object {
        val OFF = HdrDecision(false, "setting off")
    }
}

/**
 * P10-09: pure decision logic for HDR output (docs/HDR.md). HDR output is only possible on Vulkan with
 * vo=gpu-next (libplacebo picks an HDR swapchain colour space from the target colour space hint);
 * GLES and vo=gpu cannot do it, so there nothing changes and mpv tone-maps to SDR as before.
 */
object HdrOutputPolicy {
    // MediaFormat.COLOR_TRANSFER_ST2084 / COLOR_TRANSFER_HLG (constants inlined: pure JVM tests).
    const val MEDIA_TRANSFER_ST2084 = 6
    const val MEDIA_TRANSFER_HLG = 7

    /** MediaExtractor's KEY_COLOR_TRANSFER value to [HdrTransfer]; null for SDR, other or missing. */
    fun transferFromMediaFormat(colorTransfer: Int?): HdrTransfer? = when (colorTransfer) {
        MEDIA_TRANSFER_ST2084 -> HdrTransfer.PQ
        MEDIA_TRANSFER_HLG -> HdrTransfer.HLG
        else -> null
    }

    /** mpv `video-params/gamma` ("pq", "hlg", "bt.1886", "srgb", ...) to [HdrTransfer]; null otherwise. */
    fun transferFromMpvGamma(gamma: String?): HdrTransfer? = when (gamma?.trim()?.lowercase()) {
        "pq" -> HdrTransfer.PQ
        "hlg" -> HdrTransfer.HLG
        else -> null
    }

    /** The effective config can output HDR: Vulkan renderer and vo=gpu-next. */
    fun isCapableConfig(renderer: Renderer, vo: String): Boolean =
        renderer == Renderer.VULKAN && vo == "gpu-next"

    fun decide(settingEnabled: Boolean, transfer: HdrTransfer?, renderer: Renderer, vo: String): HdrDecision = when {
        !settingEnabled -> HdrDecision.OFF
        transfer == null -> HdrDecision(false, "SDR content")
        renderer != Renderer.VULKAN -> HdrDecision(false, "renderer ${renderer.name}", transfer)
        vo != "gpu-next" -> HdrDecision(false, "vo $vo", transfer)
        else -> HdrDecision(true, transfer.label, transfer)
    }

    /** Headroom (peak / SDR white) asked from the display for HDR content; 0 restores the default. */
    const val DESIRED_HEADROOM = 5.0f
}
