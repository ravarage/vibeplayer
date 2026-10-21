package com.vibeplayer.net

/**
 * One row of a network listing (P13): a folder to open, or something to play. [path] is the source's own
 * path (SFTP/SMB/FTP path, WebDAV href, DLNA object id, M3U group). [playUrl] is set when mpv can open the
 * entry directly (http, https, HLS, rtsp, udp ...); otherwise the file is read through [NetFileProvider].
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = -1L,
    val playUrl: String? = null,
    val headers: List<String> = emptyList(),
    val userAgent: String? = null,
    val referrer: String? = null,
    /** M3U group-title, for the channel list. */
    val group: String? = null,
    val logo: String? = null,
    /** A live stream (IPTV): no resume position, no Up next. */
    val live: Boolean = false
)

/** Which file names are worth listing in a network folder. */
object MediaNames {
    private val VIDEO = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "flv", "ts", "m2ts", "mts", "wmv", "mpg", "mpeg", "3gp", "ogv",
        "vob", "rmvb", "m3u8", "mpd"
    )
    private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt", "sub", "sup")
    private val PLAYLIST = setOf("m3u", "m3u8")

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = ext(name) in VIDEO
    fun isSubtitle(name: String): Boolean = ext(name) in SUBTITLE
    fun isPlaylist(name: String): Boolean = ext(name) in PLAYLIST

    /** Folders first, then by natural name order ("ep2" before "ep10"). */
    fun sort(entries: List<RemoteEntry>): List<RemoteEntry> =
        entries.sortedWith(compareBy<RemoteEntry> { !it.isDir }.thenComparator { a, b -> natural(a.name, b.name) })

    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val n = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                if (n != 0) n else x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }
}
