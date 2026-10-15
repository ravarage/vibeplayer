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
    val containerFps: Double? = null
)

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
    val containerFps: Double? = null
)


