package com.vibeplayer.net

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.io.IOException

/**
 * P13: SFTP over SSH (JSch). Password sign-in; the server's host key is remembered on first use
 * (known_hosts in the app's files) and a changed key is refused, as OpenSSH does with accept-new.
 */
class SftpFs(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val knownHosts: File,
    startPath: String
) : ReadableFs {
    override val root: String = startPath.ifBlank { "." }

    private val session: Session = connect()
    private val channel: ChannelSftp = (session.openChannel("sftp") as ChannelSftp).also { it.connect(Http.TIMEOUT_MS) }

    private fun connect(): Session {
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.path)
        return jsch.getSession(user, host, if (port > 0) port else 22).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "accept-new")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setUserInfo(PasswordInfo(password))
            connect(Http.TIMEOUT_MS)
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = if (path.isBlank()) channel.pwd() else path
        val out = ArrayList<RemoteEntry>()
        @Suppress("UNCHECKED_CAST")
        val items = channel.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
        for (e in items) {
            val name = e.filename
            if (name == "." || name == ".." || name.startsWith(".")) continue
            val full = dir.trimEnd('/') + "/" + name
            val isDir = e.attrs.isDir || (e.attrs.isLink && runCatching { channel.stat(full).isDir }.getOrDefault(false))
            if (!isDir && !MediaNames.isVideo(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = full, isDir = isDir, size = e.attrs.size)
        }
        return MediaNames.sort(out)
    }

    override fun open(path: String): RandomReader = SftpReader(connect(), path)

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    /** Each opened file gets its own session, so playback does not share a channel with browsing. */
    private class SftpReader(private val session: Session, private val path: String) : ReopeningReader() {
        private val ch = (session.openChannel("sftp") as ChannelSftp).also { it.connect(Http.TIMEOUT_MS) }
        override val size: Long = ch.stat(path).size

        override fun openAt(offset: Long): java.io.InputStream = ch.get(path, null, offset)

        override fun close() {
            super.close()
            runCatching { ch.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private class PasswordInfo(private val pw: String) : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { pw }
    }

    companion object {
        /** A friendlier message for the common failures. */
        fun explain(e: Throwable): String = when {
            e.message?.contains("Auth fail", ignoreCase = true) == true -> "SFTP: sign-in refused; check the user and password"
            e.message?.contains("HostKey has been changed", ignoreCase = true) == true ->
                "SFTP: the server's key changed since the first connection; if that is expected, remove and add the source again"
            e is IOException -> "SFTP: ${e.message}"
            else -> "SFTP: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
