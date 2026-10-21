package com.vibeplayer.net

/**
 * P13: UPnP / DLNA media servers (Jellyfin, Plex, Emby, MiniDLNA, Windows media sharing, Kodi, routers).
 * Discovery is an SSDP M-SEARCH; browsing is the ContentDirectory Browse action, whose reply holds a DIDL-Lite
 * document of containers (folders) and items (a res URL each).
 */
object Dlna {
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

    fun searchRequest(mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $MEDIA_SERVER\r\n\r\n"

    /** The LOCATION (device description URL) of an SSDP reply, or null. */
    fun location(reply: String): String? = reply.lineSequence()
        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.ifBlank { null }

    data class Server(val name: String, val location: String, val controlUrl: String)

    /** Name and ContentDirectory control URL from a device description. */
    fun describe(xml: String, location: String): Server? {
        val root = Xml.parse(xml) ?: return null
        val base = Xml.text(root, "URLBase")?.ifBlank { null } ?: location
        val name = Xml.all(root, "friendlyName").firstOrNull()?.textContent?.trim() ?: return null
        val service = Xml.all(root, "service").firstOrNull { Xml.text(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:ContentDirectory:") == true }
            ?: return null
        val control = Xml.text(service, "controlURL") ?: return null
        return Server(name, location, M3u.resolve(control, base))
    }

    fun browseBody(objectId: String, start: Int, count: Int): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="$CONTENT_DIRECTORY"><ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""

    data class Page(val entries: List<RemoteEntry>, val returned: Int, val total: Int)

    /** One Browse reply: the SOAP envelope with the DIDL-Lite result as escaped text. */
    fun parseBrowse(soap: String): Page {
        val env = Xml.parse(soap) ?: return Page(emptyList(), 0, 0)
        val result = Xml.all(env, "Result").firstOrNull()?.textContent ?: return Page(emptyList(), 0, 0)
        val returned = Xml.all(env, "NumberReturned").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
        val total = Xml.all(env, "TotalMatches").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
        return Page(parseDidl(result), returned, total)
    }

    fun parseDidl(didl: String): List<RemoteEntry> {
        val root = Xml.parse(didl) ?: return emptyList()
        val out = ArrayList<RemoteEntry>()
        for (c in Xml.children(root, "container")) {
            val id = c.getAttribute("id")
            val title = Xml.text(c, "title") ?: id
            out += RemoteEntry(name = title, path = id, isDir = true)
        }
        for (item in Xml.children(root, "item")) {
            val title = Xml.text(item, "title") ?: item.getAttribute("id")
            val upnpClass = Xml.text(item, "class") ?: ""
            val res = Xml.children(item, "res")
            // The first res that is video (protocolInfo "http-get:*:video/...:*"), else the first one of a video item.
            val chosen = res.firstOrNull { it.getAttribute("protocolInfo").split(':').getOrNull(2)?.startsWith("video/") == true }
                ?: res.firstOrNull()?.takeIf { upnpClass.startsWith("object.item.videoItem") }
                ?: continue
            val url = chosen.textContent.trim()
            if (url.isEmpty()) continue
            val size = chosen.getAttribute("size").toLongOrNull() ?: -1L
            out += RemoteEntry(name = title, path = item.getAttribute("id"), isDir = false, size = size, playUrl = url)
        }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
