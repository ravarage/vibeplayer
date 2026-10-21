package com.vibeplayer.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * P13: the browser's connection to one source. Listings run one at a time (JSch channels and FTP clients are
 * not thread-safe, and a cancelled coroutine's blocking call keeps running); a connection made after the
 * screen closed is closed at once; a failed one is dropped so Retry reconnects.
 */
class RemoteSession(private val repo: NetRepository, private val source: NetSource) {
    private val mutex = Mutex()
    private var fs: RemoteFs? = null
    @Volatile private var closed = false

    /** (entries, the path listed); [path] null lists the source's start folder. */
    suspend fun list(path: String?): Pair<List<RemoteEntry>, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed) throw CancellationException("closed")
            val conn = fs ?: repo.connect(source).also { fs = it }
            if (closed) {
                drop()
                throw CancellationException("closed")
            }
            val p = path ?: conn.root
            try {
                conn.list(p) to p
            } catch (e: Exception) {
                drop()
                throw e
            }
        }
    }

    private fun drop() {
        fs?.let { runCatching { it.close() } }
        fs = null
    }

    /** Closes the connection once no listing is running (off the main thread). */
    fun close() {
        closed = true
        Thread { runBlocking { mutex.withLock { drop() } } }.start()
    }
}
