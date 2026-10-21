package com.vibeplayer.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * P13: the network clients against real servers (OpenSSH, Samba, pyftpdlib, WsgiDAV, tools/net_test_server.py)
 * started by tools/net_it/start_servers.sh. Skipped unless VP_NET_IT=1 (CI and normal test runs skip them).
 */
class NetIntegrationTest {
    private val user = "vptest"
    private val pass = "vppass"
    private val movies = File(System.getenv("VP_NET_IT_DIR") ?: "/srv/vptest/Movies")
    private val ep01 = File(movies, "ep01.mkv")

    @Before
    fun servers() {
        assumeTrue("set VP_NET_IT=1 with tools/net_it/start_servers.sh running", System.getenv("VP_NET_IT") == "1")
    }

    private fun knownHosts() = File.createTempFile("known_hosts", "").also { it.delete() }

    /** The listing of the Movies folder, the same on every server. */
    private fun checkListing(entries: List<RemoteEntry>) {
        val names = entries.map { it.name }
        assertTrue("ep01.mkv in $names", "ep01.mkv" in names)
        assertTrue("ep02.mkv in $names", "ep02.mkv" in names)
        assertTrue("ep01.srt in $names", "ep01.srt" in names)
        assertTrue("Season1 in $names", entries.any { it.name == "Season1" && it.isDir })
        assertFalse("notes.txt hidden", "notes.txt" in names)
        assertFalse(".hidden hidden", ".hidden" in names)
        assertEquals("folders first", "Season1", entries.first().name)
    }

    /** Random reads (forward, backward, at the end) give the file's bytes. */
    private fun checkReader(r: RandomReader) {
        r.use {
            assertEquals(ep01.length(), r.size)
            val local = RandomAccessFile(ep01, "r")
            for (offset in listOf(0L, 300_000L, 100L, 300_100L, r.size - 1000)) {
                val want = ByteArray(1000).also { local.seek(offset); local.readFully(it) }
                val got = ByteArray(1000)
                var n = 0
                while (n < got.size) {
                    val k = r.read(offset + n, got, n, got.size - n)
                    if (k <= 0) break
                    n += k
                }
                assertEquals("bytes read at $offset", 1000, n)
                assertArrayEquals("content at $offset", want, got)
            }
            assertEquals(-1, r.read(r.size, ByteArray(10), 0, 10))
            local.close()
        }
    }

    @Test
    fun sftpListsReadsAndPinsTheHostKey() {
        val kh = knownHosts()
        SftpFs("127.0.0.1", 2222, user, pass, kh, "/srv/vptest/Movies").use { fs ->
            checkListing(fs.list(fs.root))
            assertEquals(listOf("Ep 1 (spaced).mkv"), fs.list("/srv/vptest/Movies/Season1").map { it.name })
            checkReader(fs.open("/srv/vptest/Movies/ep01.mkv"))
        }
        assertTrue("first connection saved the host key", kh.readText().contains("[127.0.0.1]:2222"))
        // a different key for the same host must be refused
        val line = kh.readLines().first { it.startsWith("[127.0.0.1]:2222") }
        val parts = line.split(' ')
        val forged = parts[2].toCharArray().also { it[30] = if (it[30] == 'A') 'B' else 'A' }.concatToString()
        kh.writeText("${parts[0]} ${parts[1]} $forged\n")
        try {
            SftpFs("127.0.0.1", 2222, user, pass, kh, "/srv/vptest/Movies").use { it.list(it.root) }
            fail("a changed host key was accepted")
        } catch (e: Exception) {
            assertTrue(SftpFs.explain(e), SftpFs.explain(e).contains("key changed") || SftpFs.explain(e).contains("not accepted"))
        }
    }

    @Test
    fun sftpWrongPasswordIsExplained() {
        try {
            SftpFs("127.0.0.1", 2222, user, "wrong", knownHosts(), "/").use { it.list(it.root) }
            fail("signed in with a wrong password")
        } catch (e: Exception) {
            assertTrue(SftpFs.explain(e), SftpFs.explain(e).contains("sign-in refused"))
        }
    }

    @Test
    fun smbListsAndReads() {
        SmbFs("127.0.0.1", 4450, user, pass, "/Movies").use { fs ->
            checkListing(fs.list(fs.root))
            assertEquals(listOf("Ep 1 (spaced).mkv"), fs.list("/Movies/Season1").map { it.name })
            checkReader(fs.open("/Movies/ep01.mkv"))
        }
    }

    @Test
    fun ftpWithUserAndAnonymous() {
        FtpFs("127.0.0.1", 2121, user, pass, "/Movies").use { fs ->
            checkListing(fs.list(fs.root))
            checkReader(fs.open("/Movies/ep01.mkv"))
        }
        FtpFs("127.0.0.1", 2121, "", "", "/").use { fs -> checkListing(fs.list("/")) }
    }

    @Test
    fun webDavListsAndPlaysWithTheSignInHeader() {
        val fs = WebDavFs("http://127.0.0.1:8081/dav/Movies/", user, pass)
        val entries = fs.list(fs.root)
        checkListing(entries)
        val season = entries.first { it.isDir }
        assertEquals(listOf("Ep 1 (spaced).mkv"), fs.list(season.path).map { it.name })
        val ep = entries.first { it.name == "ep01.mkv" }
        assertTrue(ep.headers.single().startsWith("Authorization: Basic "))
        assertEquals(ep01.length(), rangeLength(ep.playUrl!!, ep.headers, "bytes=0-"))
    }

    @Test
    fun httpFolderWithBasicAuth() {
        val fs = HttpIndexFs("http://127.0.0.1:8765/", user, pass)
        val entries = fs.list(fs.root)
        checkListing(entries)
        val ep = entries.first { it.name == "ep01.mkv" }
        assertEquals(100L, rangeLength(ep.playUrl!!, ep.headers, "bytes=0-99"))
        assertEquals(listOf("Ep 1 (spaced).mkv"), fs.list(entries.first { it.isDir }.path).map { it.name })
        try {
            HttpIndexFs("http://127.0.0.1:8765/", user, "wrong").list("http://127.0.0.1:8765/")
            fail("listed with a wrong password")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("sign-in refused"))
        }
    }

    @Test
    fun dlnaBrowsesToAVideoAndItsStreamPlays() {
        val fs = DlnaFs("http://127.0.0.1:8200/rootDesc.xml")
        assertEquals("VibeTest DLNA", fs.serverName)
        // breadth-first through the server's folders (Video > Folders > Movies ...) to ep01.mkv
        var queue = listOf(fs.root)
        var found: RemoteEntry? = null
        repeat(5) {
            if (found != null) return@repeat
            val next = ArrayList<String>()
            for (id in queue) {
                val entries = fs.list(id)
                found = found ?: entries.firstOrNull { !it.isDir && it.name.startsWith("ep01") }
                next += entries.filter { it.isDir }.map { it.path }
            }
            queue = next.take(40)
        }
        val ep = found ?: throw AssertionError("ep01 not found on the DLNA server")
        assertTrue(ep.playUrl!!.startsWith("http://127.0.0.1:8200/"))
        assertEquals(1000L, rangeLength(ep.playUrl!!, emptyList(), "bytes=0-999"))
    }

    @Test
    fun m3uFromTheServer() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"A\",One\nep01.mkv\n#EXTINF:-1 group-title=\"B\",Two\nep02.mkv\n"
        val fs = M3uFs("http://127.0.0.1:8765/list.m3u") { text }
        val root = fs.list(fs.root)
        assertEquals(listOf("All channels (2)", "A (1)", "B (1)"), root.map { it.name })
        val all = fs.list(root.first().path)
        assertEquals("http://127.0.0.1:8765/ep01.mkv", all.first().playUrl)
    }

    private fun rangeLength(url: String, headers: List<String>, range: String): Long {
        val c = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        headers.forEach { c.setRequestProperty(it.substringBefore(':'), it.substringAfter(':').trim()) }
        c.setRequestProperty("Range", range)
        try {
            assertTrue("status ${c.responseCode}", c.responseCode == 206 || c.responseCode == 200)
            return c.inputStream.use { it.readBytes().size.toLong() }
        } finally {
            c.disconnect()
        }
    }
}
