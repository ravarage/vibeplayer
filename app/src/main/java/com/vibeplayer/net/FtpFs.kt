package com.vibeplayer.net

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException

/** P13: FTP (passive, binary) with Apache Commons Net; anonymous when no user is given. */
class FtpFs(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    startPath: String
) : ReadableFs {
    override val root: String = startPath.ifBlank { "/" }

    // Connected on first listing only: opening a file for playback makes its own connection.
    private var client: FTPClient? = null

    private fun connect(): FTPClient {
        val c = FTPClient()
        try {
            c.connectTimeout = Http.TIMEOUT_MS
            c.defaultTimeout = Http.TIMEOUT_MS
            c.setDataTimeout(java.time.Duration.ofMillis(Http.TIMEOUT_MS.toLong()))
            c.controlEncoding = "UTF-8"
            c.connect(host, if (port > 0) port else 21)
            c.soTimeout = Http.TIMEOUT_MS
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("FTP: the server refused the connection (${c.replyCode})")
            val ok = if (user.isEmpty()) c.login("anonymous", "vibeplayer@") else c.login(user, password)
            if (!ok) throw IOException("FTP: sign-in refused; check the user and password")
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            return c
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw e
        }
    }

    @Synchronized
    override fun list(path: String): List<RemoteEntry> {
        val c = client?.takeIf { it.isConnected } ?: connect().also { client = it }
        val dir = path.ifBlank { "/" }
        val out = ArrayList<RemoteEntry>()
        for (f in c.listFiles(dir) ?: emptyArray()) {
            val name = f.name ?: continue
            if (name == "." || name == ".." || name.startsWith(".")) continue
            if (!f.isDirectory && !MediaNames.isVideo(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = dir.trimEnd('/') + "/" + name, isDir = f.isDirectory, size = f.size)
        }
        return MediaNames.sort(out)
    }

    override fun open(path: String): RandomReader = FtpReader(path)

    private inner class FtpReader(private val path: String) : ReopeningReader() {
        private var c: FTPClient = connect()
        override val size: Long = try {
            c.mlistFile(path)?.size?.takeIf { it >= 0 }
                ?: c.getSize(path)?.trim()?.toLongOrNull()
                ?: throw IOException("FTP: the server does not tell the size of $path")
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw e
        }

        override fun openAt(offset: Long): java.io.InputStream {
            c.restartOffset = offset
            return c.retrieveFileStream(path) ?: throw IOException("FTP: cannot read $path (${c.replyString?.trim()})")
        }

        override fun closeStream(s: java.io.InputStream) {
            s.close()
            // The aborted transfer ends with an error reply; read it (bounded by the timeouts) so the next
            // command lines up.
            runCatching { c.completePendingCommand() }
        }

        override fun reconnect() {
            runCatching { c.disconnect() }
            c = connect()
        }

        override fun close() {
            super.close()
            runCatching { c.logout() }
            runCatching { c.disconnect() }
        }
    }

    @Synchronized
    override fun close() {
        client?.let { c ->
            runCatching { c.logout() }
            runCatching { c.disconnect() }
        }
        client = null
    }
}
