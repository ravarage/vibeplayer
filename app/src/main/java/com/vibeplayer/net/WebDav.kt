package com.vibeplayer.net

/** P13: WebDAV PROPFIND (Depth: 1) replies, as Nextcloud, Apache mod_dav, nginx-dav and rclone serve write them. */
object WebDav {
    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

    /**
     * Entries of the folder [folderHref] (absolute path on the server, e.g. "/remote.php/dav/files/me/Movies/"),
     * without the folder itself. Each entry's path is its href as the server wrote it (still %-encoded, so it
     * can be requested again); the name is decoded.
     */
    fun parse(xml: String, folderHref: String): List<RemoteEntry> {
        val root = Xml.parse(xml) ?: return emptyList()
        val self = normalize(folderHref)
        val out = ArrayList<RemoteEntry>()
        for (resp in Xml.all(root, "response")) {
            val hrefRaw = Xml.text(resp, "href") ?: continue
            val href = decodePath(hrefRaw)
            if (normalize(href) == self) continue
            val rawPath = if (hrefRaw.contains("://")) "/" + hrefRaw.substringAfter("://").substringAfter('/', "") else hrefRaw
            val prop = Xml.all(resp, "prop").firstOrNull()
            val isDir = prop?.let { Xml.all(it, "collection").isNotEmpty() } ?: hrefRaw.endsWith("/")
            val size = prop?.let { Xml.text(it, "getcontentlength")?.toLongOrNull() } ?: -1L
            val name = prop?.let { Xml.text(it, "displayname") }?.ifBlank { null }
                ?: href.trimEnd('/').substringAfterLast('/')
            out += RemoteEntry(name = name, path = rawPath, isDir = isDir, size = size)
        }
        return out
    }

    private fun normalize(p: String): String = decodePath(p).trimEnd('/').ifEmpty { "/" }

    /** The path part of an href, %-decoded (hrefs may be absolute URLs). */
    fun decodePath(href: String): String {
        val path = if (href.contains("://")) href.substringAfter("://").let { "/" + it.substringAfter('/', "") } else href
        return try { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { path }
    }
}
