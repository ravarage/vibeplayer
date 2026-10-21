package com.vibeplayer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    private val iptv = """
        #EXTM3U x-tvg-url="http://epg"
        #EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" tvg-logo="http://logo/bbc.png" group-title="UK, News",BBC One HD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11; Linux, x86_64)
        #EXTVLCOPT:http-referrer=http://portal/
        http://host:8080/live/u/p/101.ts
        #EXTINF:-1,Plain channel
        http://host/stream/index.m3u8?token=abc
        #EXTGRP:Movies
        #EXTINF:0,Film
        movies/film one.mkv
    """.trimIndent()

    @Test
    fun readsChannelsWithTheirOptions() {
        val e = M3u.parse(iptv, baseUrl = "http://host/lists/all.m3u")
        assertEquals(3, e.size)
        assertEquals("BBC One HD", e[0].name)
        assertEquals("UK, News", e[0].group)
        assertEquals("http://logo/bbc.png", e[0].logo)
        assertEquals("Mozilla/5.0 (X11; Linux, x86_64)", e[0].userAgent)
        assertEquals("http://portal/", e[0].referrer)
        assertTrue(e[0].live)
        // options and group-title do not leak into the next entry
        assertEquals("Plain channel", e[1].name)
        assertNull(e[1].group)
        assertNull(e[1].userAgent)
        assertTrue(e[1].live)
        assertEquals("Movies", e[2].group)
        assertEquals("http://host/lists/movies/film%20one.mkv", e[2].playUrl)
        assertFalse(e[2].live)
        assertEquals(listOf("UK, News", "Movies"), M3u.groups(e))
    }

    @Test
    fun tellsAnHlsMediaPlaylistFromAChannelList() {
        assertTrue(M3u.isHlsMedia("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg0.ts\n"))
        assertTrue(M3u.isHlsMedia("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertFalse(M3u.isHlsMedia(iptv))
    }
}

class WebDavTest {
    private val reply = """<?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response><d:href>/remote.php/dav/files/me/Movies/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/me/Movies/Season%201/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>https://cloud.example/remote.php/dav/files/me/Movies/Ep%2002%2B.mkv</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

    @Test
    fun listsChildrenWithoutTheFolderItself() {
        val e = WebDav.parse(reply, "/remote.php/dav/files/me/Movies/")
        assertEquals(2, e.size)
        assertEquals("Season 1", e[0].name)
        assertTrue(e[0].isDir)
        assertEquals("/remote.php/dav/files/me/Movies/Season%201/", e[0].path)
        assertEquals("/remote.php/dav/files/me/Movies/Ep%2002%2B.mkv", e[1].path)
        assertEquals("Ep 02+.mkv", e[1].name)
        assertEquals(1234L, e[1].size)
        assertFalse(e[1].isDir)
    }
}

class DlnaTest {
    @Test
    fun readsTheLocationOfAnSsdpReply() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.5:8096/dlna/desc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.5:8096/dlna/desc.xml", Dlna.location(reply))
    }

    @Test
    fun findsTheContentDirectory() {
        val desc = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>Jellyfin</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/dlna/cd/control</controlURL></service>
            </serviceList></device></root>"""
        val s = Dlna.describe(desc, "http://192.168.1.5:8096/dlna/desc.xml")!!
        assertEquals("Jellyfin", s.name)
        assertEquals("http://192.168.1.5:8096/dlna/cd/control", s.controlUrl)
    }

    @Test
    fun readsContainersAndVideoItems() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="10" parentID="0"><dc:title>Movies</dc:title></container>
            <item id="11"><dc:title>Film</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>
              <res protocolInfo="http-get:*:image/jpeg:*">http://x/thumb.jpg</res>
              <res protocolInfo="http-get:*:video/x-matroska:*" size="99">http://x/film.mkv</res></item>
            <item id="12"><dc:title>Song</dc:title><upnp:class>object.item.audioItem</upnp:class>
              <res protocolInfo="http-get:*:audio/mpeg:*">http://x/song.mp3</res></item>
            </DIDL-Lite>"""
        val soap = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>${didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</Result>
            <NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
        val page = Dlna.parseBrowse(soap)
        assertEquals(3, page.returned)
        assertEquals(2, page.entries.size)
        assertEquals("Movies", page.entries[0].name)
        assertTrue(page.entries[0].isDir)
        assertEquals("http://x/film.mkv", page.entries[1].playUrl)
        assertEquals(99L, page.entries[1].size)
    }
}

class HttpIndexTest {
    @Test
    fun keepsSubfoldersAndVideosOfThisFolder() {
        val html = """<html><body><h1>Directory listing for /movies/</h1><ul>
            <li><a href="../">Parent</a></li>
            <li><a href="?C=N;O=D">Name</a></li>
            <li><a href="Season%201/">Season 1/</a></li>
            <li><a href="ep%2001.mkv">ep 01.mkv</a></li>
            <li><a href="notes.txt">notes.txt</a></li>
            <li><a href="ep%2001.srt">ep 01.srt</a></li>
            <li><a href="/other/x.mkv">elsewhere</a></li>
            <li><a href="http://evil/x.mkv">other host</a></li>
            </ul></body></html>"""
        val e = HttpIndex.parse(html, "http://127.0.0.1:8765/movies/")
        assertEquals(listOf("Season 1", "ep 01.mkv", "ep 01.srt"), e.map { it.name })
        assertTrue(e[0].isDir)
        assertEquals("http://127.0.0.1:8765/movies/ep%2001.mkv", e[1].playUrl)
    }
}

class MediaNamesTest {
    @Test
    fun sortsNaturallyWithFoldersFirst() {
        val e = listOf(
            RemoteEntry("ep10.mkv", "a", false), RemoteEntry("ep2.mkv", "b", false), RemoteEntry("Extras", "c", true)
        )
        assertEquals(listOf("Extras", "ep2.mkv", "ep10.mkv"), MediaNames.sort(e).map { it.name })
    }
}
