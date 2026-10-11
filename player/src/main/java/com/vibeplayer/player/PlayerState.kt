package com.vibeplayer.player

data class Track(
    val id: Int,
    val type: String, // "video", "audio", "sub"
    val title: String? = null,
    val lang: String? = null,
    val codec: String? = null,
    val selected: Boolean = false
)

data class PlayerStats(
    val vo: String = "",
    val hwdecCurrent: String = "",
    val mode: String = "",
    val videoW: Int = 0,
    val videoH: Int = 0,
    val fps: Double = 0.0,
    val frameDropCount: Long = 0L,
    val decoderFrameDropCount: Long = 0L
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
    val fallback: Boolean = false
)
