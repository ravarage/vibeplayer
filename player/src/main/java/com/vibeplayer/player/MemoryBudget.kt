package com.vibeplayer.player

/**
 * P7-07: mpv demuxer cache budget for LOCAL files (single place for every value).
 *
 * mpv 0.41 defaults: demuxer-max-bytes 150 MiB forward, demuxer-max-back-bytes 50 MiB back,
 * demuxer-readahead-secs 1 (only the byte limits matter with cache on), cache=auto,
 * demuxer-seekable-cache=auto, audio-buffer 0.2 s. Defaults are sized for network streams; a local
 * file can be re-read from storage in microseconds, so a large read-ahead only costs native heap.
 */
object MemoryBudget {
    private const val MIB = 1024L * 1024L

    /** Forward buffer. 32 MiB is about 13 s of a 20 Mbit/s 4K stream and minutes of 1080p H.264. */
    const val DEMUXER_MAX_BYTES = 32 * MIB

    /**
     * Back buffer for instant seek-back. 16 MiB covers a 10 s double-tap back at up to ~12 Mbit/s
     * (1080p H.264 is ~8 Mbit/s, 10 s = ~10 MiB). Beyond that mpv re-reads from local storage.
     */
    const val DEMUXER_MAX_BACK_BYTES = 16 * MIB

    /** Defaults mpv shipped with, for the debug A/B switch. */
    const val LEGACY_DEMUXER_MAX_BYTES = 150 * MIB
    const val LEGACY_DEMUXER_MAX_BACK_BYTES = 50 * MIB

    /** Thumbnail extractor (one frame, paused): a few MiB are enough to find a keyframe. */
    const val THUMB_DEMUXER_MAX_BYTES = 4 * MIB
    const val THUMB_DEMUXER_MAX_BACK_BYTES = 1 * MIB

    /** Debug builds only (intent extra `legacy_cache=1` on PlayerActivity): mpv's old defaults. */
    @Volatile
    var useLegacyCache: Boolean = false

    fun demuxerOptions(legacy: Boolean = useLegacyCache): Map<String, String> =
        if (legacy) {
            mapOf(
                "demuxer-max-bytes" to LEGACY_DEMUXER_MAX_BYTES.toString(),
                "demuxer-max-back-bytes" to LEGACY_DEMUXER_MAX_BACK_BYTES.toString()
            )
        } else {
            mapOf(
                "demuxer-max-bytes" to DEMUXER_MAX_BYTES.toString(),
                "demuxer-max-back-bytes" to DEMUXER_MAX_BACK_BYTES.toString()
            )
        }
}
