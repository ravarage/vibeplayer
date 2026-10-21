package com.vibeplayer.net

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * P13: SFTP, SMB and FTP files as seekable file descriptors, so mpv plays them like any content:// file
 * (fdclose://N) with the password kept out of URLs and logs. Not exported: only this app opens these URIs.
 * content://<app>.netfiles/<source id>/<encoded remote path>
 */
class NetFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        val c = MatrixCursor(cols)
        val name = parse(uri)?.second?.trimEnd('/')?.substringAfterLast('/') ?: uri.lastPathSegment
        c.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) name else null })
        return c
    }

    override fun getType(uri: Uri): String? {
        val ext = parse(uri)?.second?.substringAfterLast('.', "")?.lowercase() ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val (id, path) = parse(uri) ?: throw FileNotFoundException("bad uri $uri")
        val ctx = context ?: throw FileNotFoundException("no context")
        val repo = NetRepository(ctx)
        val source = runBlocking { repo.get(id) } ?: throw FileNotFoundException("network source $id was removed")
        val reader = try {
            val fs = repo.connect(source) as? ReadableFs ?: throw FileNotFoundException("${source.type} files are streamed by URL")
            try { fs.open(path) } finally { runCatching { fs.close() } }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            Log.w("VP-Net", "Opening $path on ${source.name} failed: ${e.message}")
            throw FileNotFoundException(NetRepository.explain(e))
        }
        val thread = HandlerThread("net-file-$id").apply { start() }
        val sm = ctx.getSystemService(StorageManager::class.java)
        Log.i("VP-Net", "Opened ${source.type} file $path (${reader.size} bytes)")
        return try {
            sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ReaderCallback(reader, thread), android.os.Handler(thread.looper))
        } catch (e: Exception) {
            runCatching { reader.close() }
            thread.quitSafely()
            throw FileNotFoundException("cannot hand out $path: ${e.message}")
        }
    }

    private class ReaderCallback(private val reader: RandomReader, private val thread: HandlerThread) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var total = 0
                while (total < size) {
                    val n = reader.read(offset + total, data, total, size - total)
                    if (n <= 0) break
                    total += n
                }
                return total
            } catch (e: Exception) {
                Log.w("VP-Net", "Read at $offset failed: ${e.message}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun authority(packageName: String) = "$packageName.netfiles"

        fun uri(packageName: String, sourceId: Long, path: String): Uri =
            Uri.Builder().scheme("content").authority(authority(packageName)).appendPath(sourceId.toString()).appendPath(path).build()

        /** (source id, remote path) of one of our URIs. */
        fun parse(uri: Uri): Pair<Long, String>? {
            val seg = uri.pathSegments
            if (seg.size != 2) return null
            val id = seg[0].toLongOrNull() ?: return null
            return id to seg[1]
        }
    }
}
