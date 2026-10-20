package com.vibeplayer.player

data class Track(
    val id: Int,
    val type: String, // "video", "audio", "sub"
    val title: String? = null,
    val lang: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val external: Boolean = false,
    val selected: Boolean = false,
    val forced: Boolean = false
)

data class PlayerStats(
    val vo: String = "",
    val hwdecCurrent: String = "",
    val mode: String = "",
    val gpuApi: String = "",
    val gpuContext: String = "",
    val rendererInUse: String = "",
    val lastVulkanError: String? = null,
    val videoW: Int = 0,
    val videoH: Int = 0,
    val fps: Double = 0.0,
    val frameDropCount: Long = 0L,
    val decoderFrameDropCount: Long = 0L,
    val formatProfileRow: String? = null,
    val formatProfileReason: String? = null,
    val detectedFormat: VideoFormat? = null,
    val containerFps: Double? = null,
    val thermalStatus: String? = null,
    /** P10-06 ADPF hint session status for the overlay ("off", "on (n threads)", ...). */
    val adpfStatus: String? = null,
    /** P10-09 HDR output decision for the overlay ("off (setting off)", "on (PQ)"). */
    val hdrStatus: String? = null,
    /** P12-09: codec, pixel format (hardware / software), colour primaries and transfer, shaders, A/V sync, cache. */
    val videoCodec: String? = null,
    val pixelFormat: String? = null,
    val colors: String? = null,
    val shaders: String? = null,
    val avsyncSec: Double? = null,
    val cacheSec: Double? = null
)

/** P12-08: the shader preset in use and whether the picture is shown without it (compare). */
data class ShaderUi(val preset: ShaderPreset = ShaderPreset.NONE, val showingOriginal: Boolean = false)

/** Overlay texts (P12-09). */
object StatsText {
    fun shaders(preset: ShaderPreset, passes: Int, original: Boolean): String = when {
        preset == ShaderPreset.NONE -> "off"
        original -> "${preset.displayName}: bypassed (compare)"
        passes == 0 -> "${preset.displayName}: not active (Efficient mode or OpenGL ES)"
        else -> "${preset.displayName} ($passes ${if (passes == 1) "pass" else "passes"})"
    }

    fun seconds(value: Double?): String = if (value == null) "n/a" else String.format(java.util.Locale.US, "%.3f s", value)
}

data class PlayerState(
    val paused: Boolean = true,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val speed: Double = 1.0,
    val hwdecCurrent: String = "no",
    val buffering: Boolean = false,
    val error: String? = null,
    val eof: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val selectedAudioTrackId: Int? = null,
    val selectedSubTrackId: Int? = null,
    val videoCodec: String? = null,
    val fallback: Boolean = false,
    val aspect: String? = null,
    val subDelayMs: Int = 0,
    val audioDelayMs: Int = 0,
    val rendererInUse: String = "GLES",
    val lastVulkanError: String? = null,
    val formatProfileRow: String? = null,
    val formatProfileReason: String? = null,
    val detectedFormat: VideoFormat? = null,
    val containerFps: Double? = null,
    val thermalStatus: String? = null,
    /** P4-15: chapters of the current file in mpv order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
    /** P4-15: mpv `chapter` (index into mpv's chapter-list), -1 before the first chapter or without chapters. */
    val currentChapter: Int = -1,
    /** A-B repeat points in ms (null: not set); kept for the file, cleared by a new file. */
    val abLoopAMs: Long? = null,
    val abLoopBMs: Long? = null,
    /** Repeat the current file (mpv loop-file); cleared by a new file. */
    val loopFile: Boolean = false
)



