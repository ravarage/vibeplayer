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
    private val client = connect()

    private fun connect(): FTPClient = FTPClient().apply {
        connectTimeout = Http.TIMEOUT_MS
        setDataTimeout(java.time.Duration.ofMillis(Http.TIMEOUT_MS.toLong()))
        controlEncoding = "UTF-8"
        connect(host, if (port > 0) port else 21)
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            disconnect()
            throw IOException("FTP: the server refused the connection ($replyCode)")
        }
        val ok = if (user.isEmpty()) login("anonymous", "vibeplayer@") else login(user, password)
        if (!ok) {
            disconnect()
            throw IOException("FTP: sign-in refused; check the user and password")
        }
        enterLocalPassiveMode()
        setFileType(FTP.BINARY_FILE_TYPE)
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = path.ifBlank { "/" }
        val out = ArrayList<RemoteEntry>()
        for (f in client.listFiles(dir) ?: emptyArray()) {
            val name = f.name ?: continue
            if (name == "." || name == ".." || name.startsWith(".")) continue
            if (!f.isDirectory && !MediaNames.isVideo(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = dir.trimEnd('/') + "/" + name, isDir = f.isDirectory, size = f.size)
        }
        return MediaNames.sort(out)
    }

    override fun open(path: String): RandomReader {
        val c = connect()
        val total = c.mlistFile(path)?.size?.takeIf { it >= 0 }
            ?: c.getSize(path)?.trim()?.toLongOrNull()
            ?: throw IOException("FTP: the server does not tell the size of $path")
        return object : ReopeningReader() {
            override val size: Long = total

            override fun openAt(offset: Long): java.io.InputStream {
                c.restartOffset = offset
                return c.retrieveFileStream(path) ?: throw IOException("FTP: cannot read $path (${c.replyString?.trim()})")
            }

            override fun closeStream(s: java.io.InputStream) {
                s.close()
                // The aborted transfer ends with an error reply; read it so the next command lines up.
                runCatching { c.completePendingCommand() }
            }

            override fun close() {
                super.close()
                runCatching { c.logout() }
                runCatching { c.disconnect() }
            }
        }
    }

    override fun close() {
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}
