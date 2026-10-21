package com.vibeplayer.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * P13: SMB 2/3 shares (Windows, Samba, NAS) with smbj. Paths are "/Share/folder/file": the first part is the
 * share, since smbj cannot list the shares of a server. A user "DOMAIN\\user" signs in to that domain; an
 * empty user signs in as guest.
 */
class SmbFs(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    startPath: String
) : ReadableFs {
    override val root: String = "/" + startPath.trim('/')

    private val client = SMBClient(
        SmbConfig.builder().withTimeout(Http.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(Http.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).build()
    )
    private val connection: Connection = client.connect(host, if (port > 0) port else 445)
    private val session: Session = connection.authenticate(auth())
    private val shares = HashMap<String, DiskShare>()

    private fun auth(): AuthenticationContext {
        if (user.isEmpty()) return AuthenticationContext.guest()
        val domain = if (user.contains('\\')) user.substringBefore('\\') else ""
        val name = user.substringAfter('\\')
        return AuthenticationContext(name, password.toCharArray(), domain)
    }

    private fun split(path: String): Pair<String, String> {
        val parts = path.trim('/').split('/')
        val share = parts.firstOrNull()?.ifBlank { null }
            ?: throw IOException("SMB: put the share name first in the path, e.g. /Movies")
        return share to parts.drop(1).joinToString("\\")
    }

    private fun share(name: String): DiskShare = synchronized(shares) {
        shares.getOrPut(name) { session.connectShare(name) as DiskShare }
    }

    override fun list(path: String): List<RemoteEntry> {
        val (shareName, dir) = split(path)
        val share = share(shareName)
        val out = ArrayList<RemoteEntry>()
        for (f in share.list(dir)) {
            val name = f.fileName
            if (name == "." || name == ".." || name.startsWith(".")) continue
            val isDir = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            if (!isDir && !MediaNames.isVideo(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = "/" + path.trim('/') + "/" + name, isDir = isDir, size = f.endOfFile)
        }
        return MediaNames.sort(out)
    }

    override fun open(path: String): RandomReader {
        val (shareName, file) = split(path)
        val fs = SmbFs(host, port, user, password, "/$shareName")
        val f = fs.share(shareName).openFile(
            file, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null
        )
        return object : RandomReader {
            override val size: Long = f.fileInformation.standardInformation.endOfFile

            override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
                if (offset >= size) return -1
                return f.read(buf, offset, bufOffset, len)
            }

            override fun close() {
                runCatching { f.close() }
                fs.close()
            }
        }
    }

    override fun close() {
        synchronized(shares) { shares.values.forEach { runCatching { it.close() } } }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
