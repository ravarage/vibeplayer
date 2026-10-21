package com.vibeplayer.player

/** P13: how a network file is opened: sign-in and IPTV headers, and whether it is a live stream. */
data class NetHints(
    val headers: List<String> = emptyList(),
    val userAgent: String? = null,
    val referrer: String? = null,
    val live: Boolean = false
)

/**
 * P13: per-file mpv options (loadfile) for network playback. Values are written in mpv's %length% quoting,
 * so commas and spaces in a user agent or header do not split the option list.
 */
object NetOptions {
    /** Schemes FFmpeg opens over the network in this build (mbedtls for https). */
    private val URL_SCHEMES = setOf("http", "https", "ftp", "ftps", "rtsp", "rtsps", "rtmp", "rtmps", "rtp", "udp", "srt", "mms", "mmsh", "tcp", "hls")

    fun isUrl(path: String): Boolean = path.substringBefore("://", "").lowercase() in URL_SCHEMES

    /** [path] for the log: a user:password@ in a URL is replaced. */
    fun redact(path: String): String = path.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@"), "$1***@")

    /** key=%N%value, N being the value's length in bytes. */
    fun quoted(key: String, value: String): String = "$key=%${value.toByteArray(Charsets.UTF_8).size}%$value"

    /**
     * Options for one load. [url]: mpv opens the address itself; [proxied]: a network file read through the
     * provider (an fd that mpv does not know is remote).
     */
    fun build(url: Boolean, proxied: Boolean, hints: NetHints?): List<String> {
        if (!url && !proxied && hints == null) return emptyList()
        val out = ArrayList<String>()
        // A read-ahead cache for anything on the network (mpv enables it on its own only for URLs it opens).
        out += "cache=yes"
        if (url) {
            out += "network-timeout=30"
            // FFmpeg's http: reconnect on a dropped connection (live IPTV, a NAS going to sleep).
            out += quoted("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5")
        }
        hints?.let { h ->
            if (h.headers.isNotEmpty()) out += quoted("http-header-fields", h.headers.joinToString(","))
            h.userAgent?.takeIf { it.isNotBlank() }?.let { out += quoted("user-agent", it) }
            h.referrer?.takeIf { it.isNotBlank() }?.let { out += quoted("referrer", it) }
        }
        return out
    }
}
