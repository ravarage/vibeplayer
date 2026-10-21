package com.vibeplayer.net

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.io.IOException

/**
 * P13: SFTP over SSH (JSch). Password sign-in. A server's host key is trusted on first use and remembered
 * (known_hosts in the app's files); a changed key is refused, as OpenSSH does.
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

    // Connected on first listing only: opening a file for playback makes its own session.
    private var session: Session? = null
    private var channel: ChannelSftp? = null

    @Synchronized
    private fun browseChannel(): ChannelSftp {
        channel?.takeIf { it.isConnected }?.let { return it }
        val s = connect().also { session = it }
        return (s.openChannel("sftp") as ChannelSftp).also { it.connect(Http.TIMEOUT_MS); channel = it }
    }

    private fun connect(): Session {
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.path)
        return jsch.getSession(user, host, if (port > 0) port else 22).apply {
            setPassword(password)
            // "ask" + HostKeyPrompt: a new server is accepted and saved, a changed key is refused.
            setConfig("StrictHostKeyChecking", "ask")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            userInfo = HostKeyPrompt(password)
            // A read that hangs (Wi-Fi gone) fails after this instead of blocking playback forever.
            timeout = Http.TIMEOUT_MS
            setServerAliveInterval(Http.TIMEOUT_MS)
            setServerAliveCountMax(2)
            connect(Http.TIMEOUT_MS)
        }
    }

    @Synchronized
    override fun list(path: String): List<RemoteEntry> {
        val ch = browseChannel()
        val dir = if (path.isBlank() || path == ".") ch.pwd() else path
        val out = ArrayList<RemoteEntry>()
        @Suppress("UNCHECKED_CAST")
        val items = ch.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
        for (e in items) {
            val name = e.filename
            if (name == "." || name == ".." || name.startsWith(".")) continue
            val full = dir.trimEnd('/') + "/" + name
            val isDir = e.attrs.isDir || (e.attrs.isLink && runCatching { ch.stat(full).isDir }.getOrDefault(false))
            if (!isDir && !MediaNames.isVideo(name) && !MediaNames.isSubtitle(name)) continue
            out += RemoteEntry(name = name, path = full, isDir = isDir, size = e.attrs.size)
        }
        return MediaNames.sort(out)
    }

    override fun open(path: String): RandomReader = SftpReader(path)

    @Synchronized
    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    /** Its own session per opened file, so playback does not share a channel with browsing. */
    private inner class SftpReader(private val path: String) : ReopeningReader() {
        private var s: Session? = null
        private var ch: ChannelSftp? = null
        override val size: Long

        init {
            try {
                size = reconnectChannel().stat(path).size
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        private fun reconnectChannel(): ChannelSftp {
            runCatching { ch?.disconnect() }
            runCatching { s?.disconnect() }
            val session = connect().also { s = it }
            return (session.openChannel("sftp") as ChannelSftp).also { it.connect(Http.TIMEOUT_MS); ch = it }
        }

        override fun openAt(offset: Long): java.io.InputStream = (ch ?: reconnectChannel()).get(path, null, offset)

        override fun reconnect() {
            reconnectChannel()
        }

        override fun close() {
            super.close()
            runCatching { ch?.disconnect() }
            runCatching { s?.disconnect() }
        }
    }

    private class HostKeyPrompt(private val pw: String) : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false

        /** Yes only to "authenticity of host ... can't be established" (first use); no to a changed key. */
        override fun promptYesNo(message: String?): Boolean =
            message?.contains("can't be established", ignoreCase = true) == true &&
                message.contains("CHANGED", ignoreCase = false).not()

        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { pw }
    }

    companion object {
        /** A friendlier message for the common failures. */
        fun explain(e: Throwable): String = when {
            e.message?.contains("Auth fail", ignoreCase = true) == true -> "SFTP: sign-in refused; check the user and password"
            e.javaClass.simpleName == "JSchChangedHostKeyException" || e.message?.contains("HostKey has been changed", ignoreCase = true) == true ->
                "SFTP: the server's key changed since the first connection; if that is expected, remove and add the source again"
            e.message?.contains("reject HostKey", ignoreCase = true) == true -> "SFTP: the server's key was not accepted"
            e is IOException -> "SFTP: ${e.message}"
            else -> "SFTP: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
