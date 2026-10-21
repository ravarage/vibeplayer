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

    /** Replaces a dropped connection (idle timeout, Wi-Fi back); called once before a retry. */
    protected open fun reconnect() {}

    @Synchronized
    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset >= size) return -1
        return try {
            readOnce(offset, buf, bufOffset, len).also { if (it < 0) throw java.io.EOFException("stream ended at $offset of $size") }
        } catch (e: java.io.IOException) {
            // A server idle timeout or a dropped connection: reconnect once, then give up (mpv sees EIO).
            dropStream()
            reconnect()
            readOnce(offset, buf, bufOffset, len)
        }
    }

    private fun readOnce(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (stream == null || offset != position) {
            dropStream()
            stream = openAt(offset)
            position = offset
        }
        val n = stream!!.read(buf, bufOffset, len)
        if (n > 0) position += n
        return n
    }

    private fun dropStream() {
        stream?.let { runCatching { closeStream(it) } }
        stream = null
        position = -1L
    }

    @Synchronized
    override fun close() {
        dropStream()
    }
}
