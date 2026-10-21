package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetOptionsTest {
    @Test
    fun recognisesStreamAddresses() {
        assertTrue(NetOptions.isUrl("https://cdn/x.m3u8"))
        assertTrue(NetOptions.isUrl("udp://@239.0.0.1:1234"))
        assertTrue(NetOptions.isUrl("RTSP://cam/live"))
        assertFalse(NetOptions.isUrl("/sdcard/Movies/a.mkv"))
        assertFalse(NetOptions.isUrl("fdclose://12"))
        assertFalse(NetOptions.isUrl("content://x/y"))
    }

    @Test
    fun quotesByByteLength() {
        assertEquals("user-agent=%10%a, b (c,d)", NetOptions.quoted("user-agent", "a, b (c,d)"))
        assertEquals("k=%3%é1", NetOptions.quoted("k", "é1"))
    }

    @Test
    fun hidesCredentialsInTheLog() {
        assertEquals("ftp://***@nas/a.mkv", NetOptions.redact("ftp://me:secret@nas/a.mkv"))
        assertEquals("http://host/a.mkv", NetOptions.redact("http://host/a.mkv"))
    }

    @Test
    fun localFilesGetNothing() {
        assertEquals(emptyList<String>(), NetOptions.build(url = false, proxied = false, hints = null))
    }

    @Test
    fun urlsGetCacheReconnectAndHeaders() {
        val o = NetOptions.build(url = true, proxied = false, hints = NetHints(listOf("Authorization: Basic eA=="), "VLC/3.0", "http://p/"))
        assertTrue("cache=yes" in o)
        assertTrue(o.any { it.startsWith("stream-lavf-o=%") && it.contains("reconnect=1") })
        assertTrue("http-header-fields=%25%Authorization: Basic eA==" in o)
        assertTrue("user-agent=%7%VLC/3.0" in o)
        assertTrue("referrer=%9%http://p/" in o)
    }

    @Test
    fun providerFilesGetTheCacheOnly() {
        assertEquals(listOf("cache=yes"), NetOptions.build(url = false, proxied = true, hints = null))
    }
}
