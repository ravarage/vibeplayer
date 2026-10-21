package com.vibeplayer.net

import java.io.Closeable

/** A connected network source that lists folders (P13). Blocking; IO thread only. */
interface RemoteFs : Closeable {
    /** Where browsing starts. */
    val root: String

    fun list(path: String): List<RemoteEntry>

    override fun close() {}
}

/** Random-access reading of one remote file, for [NetFileProvider] (SFTP, SMB, FTP). */
interface RandomReader : Closeable {
    val size: Long

    /** Reads up to [len] bytes at [offset] into [buf] from [bufOffset]; returns the count, or -1 at the end. */
    fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int
}

/** Sources whose files are read through the provider (no URL mpv could open with the credentials hidden). */
interface ReadableFs : RemoteFs {
    fun open(path: String): RandomReader
}

/**
 * A reader over a sequential stream that can only start at an offset (SFTP get with skip, FTP REST):
 * keeps the stream while reads continue where the last one ended, reopens it after a seek.
 */
abstract class ReopeningReader : RandomReader {
    private var stream: java.io.InputStream? = null
    private var position = -1L

    protected abstract fun openAt(offset: Long): java.io.InputStream
    protected open fun closeStream(s: java.io.InputStream) = s.close()

    @Synchronized
    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset >= size) return -1
        if (stream == null || offset != position) {
            stream?.let { runCatching { closeStream(it) } }
            stream = openAt(offset)
            position = offset
        }
        val n = stream!!.read(buf, bufOffset, len)
        if (n > 0) position += n
        return n
    }

    @Synchronized
    override fun close() {
        stream?.let { runCatching { closeStream(it) } }
        stream = null
    }
}
