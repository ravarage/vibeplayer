package com.vibeplayer.net

/**
 * P13: directory listings served over plain HTTP (python -m http.server, nginx autoindex, Apache, lighttpd,
 * miniserve, a NAS web share): the links of the page that stay inside the folder.
 */
object HttpIndex {
    private fun port(u: java.net.URI): Int = if (u.port > 0) u.port else if (u.scheme.equals("https", true)) 443 else 80

    fun sameOrigin(a: java.net.URI, b: java.net.URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)

    private val HREF = Regex("""<a\s[^>]*href\s*=\s*["']([^"'#?]+)["'][^>]*>""", RegexOption.IGNORE_CASE)

    /** Entries under [folderUrl] (ending in /): sub-folders, videos, playlists and subtitles; parent and sort links dropped. */
    fun parse(html: String, folderUrl: String): List<RemoteEntry> {
        val base = if (folderUrl.endsWith("/")) folderUrl else "$folderUrl/"
        val baseUri = java.net.URI(base)
        val basePath = baseUri.rawPath ?: "/"
        val seen = HashSet<String>()
        val out = ArrayList<RemoteEntry>()
        for (m in HREF.findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("..") || href.startsWith("mailto:") || href.startsWith("javascript:")) continue
            val abs = M3u.resolve(href, base)
            val uri = try { java.net.URI(abs) } catch (e: Exception) { continue }
            val path = uri.rawPath ?: continue
            // Only direct children of this folder on the same server (scheme, host and port): a sign-in
            // header attached to the entries must never reach another host.
            if (!sameOrigin(uri, baseUri) || !path.startsWith(basePath) || path == basePath) continue
            val rest = path.removePrefix(basePath)
            if (rest.trimEnd('/').contains('/')) continue
            if (!seen.add(path)) continue
            val isDir = path.endsWith("/")
            val name = java.net.URLDecoder.decode(rest.trimEnd('/').replace("+", "%2B"), "UTF-8")
            if (!isDir && !MediaNames.isVideo(name) && !MediaNames.isPlaylist(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = abs, isDir = isDir, playUrl = if (isDir) null else abs)
        }
        return out
    }
}
