package com.vibeplayer.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection calls for listings and playlists (P13); blocking, IO thread only. */
object Http {
    const val TIMEOUT_MS = 15_000
    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024

    data class Reply(val code: Int, val text: String, val finalUrl: String)

    fun basicAuth(user: String, password: String): String? =
        if (user.isEmpty()) null
        else "Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    /** [headers] are "Name: value" lines. */
    fun request(
        url: String,
        method: String = "GET",
        headers: List<String> = emptyList(),
        body: String? = null,
        contentType: String? = null
    ): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "VibePlayer")
            headers.forEach { h -> conn.setRequestProperty(h.substringBefore(':').trim(), h.substringAfter(':').trim()) }
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { s -> readLimited(s) } ?: ByteArray(0)
            return Reply(code, String(bytes, charset(conn.contentType)), conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(s: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_TEXT_BYTES) throw IOException("reply larger than ${MAX_TEXT_BYTES / 1024 / 1024} MB")
        }
        return out.toByteArray()
    }

    private fun charset(contentType: String?): java.nio.charset.Charset =
        contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.ifEmpty { null }
            ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8

    /**
     * WebDAV PROPFIND: HttpURLConnection refuses methods outside the HTTP/1.1 set, so this one request is
     * written by hand over a (TLS) socket: HTTP/1.1, Connection: close, chunked or sized body.
     */
    fun propfind(url: String, headers: List<String>, body: String): Reply {
        val u = java.net.URI(url)
        val tls = u.scheme.equals("https", ignoreCase = true)
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val socket: java.net.Socket = if (tls) {
            (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).also { s ->
                s.connect(java.net.InetSocketAddress(u.host, port), TIMEOUT_MS)
                // SNI and hostname check, as HttpsURLConnection does
                val params = s.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                params.serverNames = listOf(javax.net.ssl.SNIHostName(u.host))
                s.sslParameters = params
                s.startHandshake()
            }
        } else {
            java.net.Socket().also { it.connect(java.net.InetSocketAddress(u.host, port), TIMEOUT_MS) }
        }
        socket.use { sock ->
            sock.soTimeout = TIMEOUT_MS
            val payload = body.toByteArray(Charsets.UTF_8)
            val path = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = if (u.port > 0) "${u.host}:${u.port}" else u.host
            val req = buildString {
                append("PROPFIND $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("User-Agent: VibePlayer\r\n")
                append("Depth: 1\r\n")
                append("Content-Type: application/xml; charset=utf-8\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            val out = sock.getOutputStream()
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.write(payload)
            out.flush()
            return readRawReply(java.io.BufferedInputStream(sock.getInputStream()), url)
        }
    }

    internal fun readRawReply(input: java.io.InputStream, url: String): Reply {
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val status = line()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP reply")
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val body = java.io.ByteArrayOutputStream()
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            while (true) {
                val size = line().substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break
                val chunk = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(chunk, read, size - read)
                    if (n < 0) break
                    read += n
                }
                body.write(chunk, 0, read)
                line()
                if (body.size() > MAX_TEXT_BYTES) throw IOException("reply too large")
            }
        } else {
            body.write(readLimited(input))
        }
        return Reply(code, String(body.toByteArray(), charset(headers["content-type"])), url)
    }

    fun check(reply: Reply, what: String) {
        if (reply.code == 401 || reply.code == 403) throw IOException("$what: sign-in refused (${reply.code}); check the user and password")
        if (reply.code !in 200..299 && reply.code != 207) throw IOException("$what: the server answered ${reply.code}")
    }
}
