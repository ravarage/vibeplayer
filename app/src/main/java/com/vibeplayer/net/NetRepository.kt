package com.vibeplayer.net

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import com.vibeplayer.data.db.AppDatabase
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** P13: saved sources, connecting to them, and DLNA discovery. Blocking calls run on IO. */
class NetRepository(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).netSourceDao()

    fun observe() = dao.observeAll()

    suspend fun get(id: Long): NetSource? = dao.get(id)

    /** Saves [source] with [password] encrypted; returns its id. */
    suspend fun save(source: NetSource, password: String?): Long {
        val withSecret = if (password == null) source else source.copy(secret = Secrets.encrypt(password))
        return if (withSecret.id == 0L) dao.insert(withSecret) else withSecret.id.also { dao.update(withSecret) }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Connects (blocking, network). The caller closes it. */
    fun connect(s: NetSource): RemoteFs {
        val pw = Secrets.decrypt(s.secret)
        return when (s.netType) {
            NetType.SFTP -> SftpFs(s.host, s.port, s.username, pw, File(context.filesDir, "net/known_hosts"), s.path)
            NetType.SMB -> SmbFs(s.host, s.port, s.username, pw, s.path)
            NetType.FTP -> FtpFs(s.host, s.port, s.username, pw, s.path)
            NetType.WEBDAV -> WebDavFs(s.host, s.username, pw)
            NetType.HTTP -> HttpIndexFs(s.host, s.username, pw)
            NetType.DLNA -> DlnaFs(s.host)
            NetType.M3U -> M3uFs(s.host) { readText(it, s.username, pw) }
            null -> throw IOException("unknown source type ${s.type}")
        }
    }

    /** A playlist's text from http(s) or a content:// file chosen with the system picker. */
    fun readText(location: String, user: String = "", password: String = ""): String =
        if (location.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(location))?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: throw IOException("cannot read $location")
        } else {
            val reply = Http.request(location, headers = listOfNotNull(Http.basicAuth(user, password)))
            Http.check(reply, "Playlist")
            reply.text
        }

    /**
     * DLNA media servers answering an SSDP search on the Wi-Fi within [timeoutMs]. Needs the multicast lock
     * (CHANGE_WIFI_MULTICAST_STATE) and, on Android 17, the local network permission.
     */
    fun discoverDlna(timeoutMs: Int = 3000): List<Dlna.Server> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("vibeplayer-ssdp")?.apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val req = Dlna.searchRequest().toByteArray()
                val group = InetAddress.getByName(Dlna.SSDP_HOST)
                repeat(2) { socket.send(DatagramPacket(req, req.size, group, Dlna.SSDP_PORT)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    Dlna.location(String(p.data, 0, p.length))?.let { locations += it }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        return locations.mapNotNull { loc ->
            try {
                val reply = Http.request(loc)
                if (reply.code in 200..299) Dlna.describe(reply.text, reply.finalUrl) else null
            } catch (e: Exception) {
                Log.i("VP-Net", "DLNA description $loc: ${e.message}")
                null
            }
        }.distinctBy { it.controlUrl }
    }

    companion object {
        /** A friendly one-line reason for a failed connection or listing. */
        fun explain(e: Throwable): String = when {
            e is java.net.UnknownHostException -> "Server not found: ${e.message}"
            e is java.net.ConnectException -> "Cannot connect: ${e.message} (is the server on and the port right?)"
            e is java.net.SocketTimeoutException -> "The server did not answer in time"
            e is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
            e.javaClass.name.startsWith("com.jcraft") -> SftpFs.explain(e)
            e.javaClass.name.startsWith("com.hierynomus") ->
                if (e.message?.contains("STATUS_LOGON_FAILURE") == true) "SMB: sign-in refused; check the user and password"
                else if (e.message?.contains("STATUS_BAD_NETWORK_NAME") == true) "SMB: no share with that name; the path starts with the share"
                else "SMB: ${e.message}"
            e is SecurityException -> "Local network access is not allowed: Settings > Apps > Vibe Player > Permissions > Nearby devices"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
