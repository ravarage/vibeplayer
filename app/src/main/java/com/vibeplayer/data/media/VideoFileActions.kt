package com.vibeplayer.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.ui.normalizeMediaPath
import java.io.File

/**
 * P12-05: rename and delete a video from the browser. The app only reads media (READ_MEDIA_VIDEO), so both
 * go through the system consent dialog (MediaStore write / delete requests); the watch history, position
 * and bookmarks follow the file.
 */
object VideoFileActions {

    fun deleteRequest(resolver: ContentResolver, uri: Uri): IntentSender =
        MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender

    fun writeRequest(resolver: ContentResolver, uri: Uri): IntentSender =
        MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender

    /** Renames after the write request was granted; true when MediaStore took the new name. */
    fun rename(resolver: ContentResolver, uri: Uri, newName: String): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        resolver.update(uri, values, null, null) > 0
    } catch (e: Exception) {
        Log.w("VP", "Rename of $uri to $newName failed: ${e.message}")
        false
    }

    /** The name MediaStore holds for [uri] now, or null. */
    fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The file name to use for [input], or null when it cannot be a name or does not change anything. The
     * extension is kept when the input leaves it out ("Episode 1" for "ep01.mkv" -> "Episode 1.mkv").
     */
    fun renamedName(current: String, input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name == current || name.contains('/') || name == "." || name == "..") return null
        val ext = current.substringAfterLast('.', "")
        val result = if (ext.isNotEmpty() && !name.contains('.')) "$name.$ext" else name
        return result.takeIf { it != current }
    }

    /** Every key form the history may hold for a file (path + size, path, and the /storage/emulated/0 form). */
    fun historyKeys(path: String, sizeBytes: Long): List<String> {
        val normalized = normalizeMediaPath(path)
        return buildList {
            if (sizeBytes > 0L) {
                add("$normalized:$sizeBytes")
                add("$path:$sizeBytes")
            }
            add(normalized)
            add(path)
        }.distinct()
    }

    /** Moves position, tracks and bookmarks to the renamed file. */
    suspend fun moveHistory(db: AppDatabase, oldPath: String, newName: String, sizeBytes: Long) {
        val newPath = File(File(oldPath).parentFile, newName).path
        val newKey = historyKeys(newPath, sizeBytes).first()
        val files = db.fileSettingsDao()
        for (oldKey in historyKeys(oldPath, sizeBytes)) {
            files.getByKey(oldKey)?.let { row ->
                if (files.getByKey(newKey) == null) files.upsert(row.copy(key = newKey))
                files.deleteByKey(oldKey)
            }
            db.bookmarkDao().moveKey(oldKey, newKey)
        }
        Log.i("VP", "History moved: $oldPath -> $newPath")
    }

    /** Forgets position, tracks and bookmarks of a deleted file. */
    suspend fun deleteHistory(db: AppDatabase, path: String, sizeBytes: Long) {
        for (key in historyKeys(path, sizeBytes)) {
            db.fileSettingsDao().deleteByKey(key)
            db.bookmarkDao().deleteForKey(key)
        }
        Log.i("VP", "History deleted for $path")
    }
}
