package com.vibeplayer.net

/**
 * P13: M3U / M3U8 playlists, as IPTV providers and media servers write them:
 * `#EXTM3U`, `#EXTINF:-1 tvg-logo="..." group-title="News",Channel name`, `#EXTGRP:`, `#EXTVLCOPT:http-user-agent=`
 * and `http-referrer=`, then the URL. An HLS media playlist (`#EXT-X-...` tags, segments) is not a channel list:
 * [isHlsMedia] tells them apart so it is played as one stream instead.
 */
object M3u {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun isHlsMedia(text: String): Boolean =
        text.lineSequence().any { it.startsWith("#EXT-X-TARGETDURATION") || it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-MEDIA-SEQUENCE") }

    /** Channels in file order. Relative URLs are resolved against [baseUrl] when given. */
    fun parse(text: String, baseUrl: String? = null): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var title: String? = null
        var entryGroup: String? = null
        var extGroup: String? = null
        var logo: String? = null
        var ua: String? = null
        var referrer: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // The name is after the last comma that is outside quotes.
                    title = nameAfterComma(line) ?: attrs["tvg-name"]
                    entryGroup = attrs["group-title"]?.ifBlank { null }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    attrs["http-user-agent"]?.let { ua = it }
                    attrs["http-referrer"]?.let { referrer = it }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim().ifBlank { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> referrer = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(line, baseUrl)
                    val name = title?.ifBlank { null } ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                    out += RemoteEntry(
                        name = name, path = url, isDir = false, playUrl = url, userAgent = ua, referrer = referrer,
                        group = entryGroup ?: extGroup, logo = logo, live = isLive(url)
                    )
                    title = null; logo = null; ua = null; referrer = null; entryGroup = null
                    // group-title is per entry; #EXTGRP carries on until the next one
                }
            }
        }
        return out
    }

    private val VOD = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "wmv", "mpg", "mpeg", "ogv", "flv")

    /** IPTV entries (.ts, .m3u8, no extension) are live; a file with a VOD container extension is not. */
    fun isLive(url: String): Boolean = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase() !in VOD

    private fun nameAfterComma(line: String): String? {
        var inQuote = false
        var last = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) last = i
            }
        }
        return if (last < 0) null else line.substring(last + 1).trim().ifBlank { null }
    }

    fun resolve(url: String, base: String?): String {
        if (base == null || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) return url
        return try {
            java.net.URI(base).resolve(url.replace(" ", "%20")).toString()
        } catch (e: Exception) {
            url
        }
    }

    /** Group names in first-seen order, for the channel list's tabs. */
    fun groups(entries: List<RemoteEntry>): List<String> = entries.mapNotNull { it.group }.distinct()
}
