package com.vibeplayer.net

import java.io.IOException

/** P13: WebDAV folders; files are played straight from the server by mpv with the same sign-in header. */
class WebDavFs(private val url: String, user: String, password: String) : RemoteFs {
    private val origin: String
    override val root: String

    init {
        val u = java.net.URI(url.trim())
        origin = "${u.scheme}://${u.rawAuthority}"
        root = (u.rawPath ?: "/").ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
    }

    private val auth = listOfNotNull(Http.basicAuth(user, password))

    override fun list(path: String): List<RemoteEntry> {
        val reply = Http.propfind(origin + path, auth, WebDav.PROPFIND_BODY)
        Http.check(reply, "WebDAV")
        return MediaNames.sort(
            WebDav.parse(reply.text, path).filter { it.isDir || MediaNames.isVideo(it.name) || MediaNames.isSubtitle(it.name) }
                .map { if (it.isDir) it else it.copy(playUrl = origin + it.path, headers = auth) }
        )
    }
}

/** P13: a folder listing page served over HTTP (python http.server, nginx autoindex, Apache, a NAS share). */
class HttpIndexFs(private val url: String, user: String, password: String) : RemoteFs {
    override val root: String = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = listOfNotNull(Http.basicAuth(user, password))

    override fun list(path: String): List<RemoteEntry> {
        val reply = Http.request(path, headers = auth)
        Http.check(reply, "Web folder")
        return MediaNames.sort(HttpIndex.parse(reply.text, reply.finalUrl).map { if (it.isDir) it else it.copy(headers = auth) })
    }
}

/** P13: a DLNA / UPnP media server; [location] is its device description URL (found by [Dlna.discover]). */
class DlnaFs(private val location: String) : RemoteFs {
    override val root: String = "0"
    private val server: Dlna.Server = run {
        val reply = Http.request(location)
        Http.check(reply, "DLNA")
        Dlna.describe(reply.text, reply.finalUrl) ?: throw IOException("DLNA: $location is not a media server")
    }
    val serverName: String get() = server.name

    override fun list(path: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var start = 0
        while (true) {
            val reply = Http.request(
                server.controlUrl, method = "POST",
                headers = listOf("SOAPACTION: \"${Dlna.CONTENT_DIRECTORY}#Browse\""),
                body = Dlna.browseBody(path, start, PAGE), contentType = "text/xml; charset=\"utf-8\""
            )
            Http.check(reply, "DLNA browse")
            val page = Dlna.parseBrowse(reply.text)
            out += page.entries
            start += page.returned
            if (page.returned == 0 || start >= page.total || start >= MAX_ITEMS) break
        }
        // A server's own order is meaningful (seasons, episodes): kept, folders first.
        return out.filter { it.isDir } + out.filter { !it.isDir }
    }

    companion object {
        private const val PAGE = 200
        private const val MAX_ITEMS = 5000
    }
}

/**
 * P13: an M3U / M3U8 playlist (IPTV channels, a list of files) from a URL or a content:// file. With several
 * groups, the root lists the groups as folders (and "All channels"); an HLS media playlist is one stream.
 */
class M3uFs(private val source: String, private val readText: (String) -> String) : RemoteFs {
    override val root: String = ""
    private val entries: List<RemoteEntry> by lazy {
        val text = readText(source)
        if (M3u.isHlsMedia(text)) listOf(RemoteEntry(name = source.substringAfterLast('/').substringBefore('?'), path = source,
            isDir = false, playUrl = source, live = true))
        else M3u.parse(text, source.takeIf { it.startsWith("http") })
    }

    override fun list(path: String): List<RemoteEntry> {
        val groups = M3u.groups(entries)
        return when {
            path == ALL || groups.size < 2 -> entries
            path.startsWith(GROUP) -> entries.filter { it.group == path.removePrefix(GROUP) }
            else -> listOf(RemoteEntry(name = "All channels (${entries.size})", path = ALL, isDir = true)) +
                groups.map { g -> RemoteEntry(name = "$g (${entries.count { it.group == g }})", path = GROUP + g, isDir = true) } +
                entries.filter { it.group == null }
        }
    }

    companion object {
        const val ALL = "all:"
        const val GROUP = "group:"
    }
}
