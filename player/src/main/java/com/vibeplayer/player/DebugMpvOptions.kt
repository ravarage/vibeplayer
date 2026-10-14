package com.vibeplayer.player

/**
 * Debug builds only: extra mpv options for device A/B runs (intent extra `mpv_opts`, also forwarded by
 * the benchmark receiver), e.g. `vd-queue-enable=yes;hwdec-extra-frames=12;vd-lavc-o=operating_rate=120`.
 * Entries are separated by ';' (values such as vd-lavc-o contain ',' and '='); the first '=' splits
 * name and value. Only names checked against mpv 0.41 / FFmpeg sources in native/ are accepted, so a typo
 * is dropped (and reported) instead of failing mpv initialisation. Applied last in the option table.
 * Nothing here is used in release builds or changes a default.
 */
object DebugMpvOptions {

    val ALLOWED: Set<String> = setOf(
        "vd-queue-enable", "vd-queue-max-samples", "vd-queue-max-bytes", // f_decoder_wrapper.c
        "hwdec-extra-frames", "vd-lavc-o", "vd-lavc-dr", "vd-lavc-threads", // vd_lavc.c
        "video-sync", "framedrop", "display-fps-override", "video-latency-hacks", "video-timing-offset",
        "untimed", "hr-seek-framedrop", // options.c
        "interpolation", "opengl-pbo", // video/out/gpu/video.c
        "profile", "hwdec"
    )

    data class Parsed(val options: Map<String, String>, val rejected: List<String>)

    fun parse(text: String?): Parsed {
        if (text.isNullOrBlank()) return Parsed(emptyMap(), emptyList())
        val options = LinkedHashMap<String, String>()
        val rejected = ArrayList<String>()
        for (entry in text.split(';')) {
            val e = entry.trim()
            if (e.isEmpty()) continue
            val eq = e.indexOf('=')
            val name = if (eq > 0) e.substring(0, eq).trim() else ""
            val value = if (eq > 0) e.substring(eq + 1).trim() else ""
            if (name in ALLOWED && value.isNotEmpty()) options[name] = value else rejected.add(e)
        }
        return Parsed(options, rejected)
    }

    /** For CSV/log labels: no ',' (the benchmark CSV is not quoted). */
    fun label(options: Map<String, String>): String =
        options.entries.joinToString(";") { "${it.key}=${it.value}" }.replace(',', '|')
}
