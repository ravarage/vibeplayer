package com.vibeplayer.data.subs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * External subtitle files: opening one for mpv (UTF-8 as a descriptor, anything else converted to a
 * UTF-8 copy in the cache) and finding the ones next to a video in a folder the user granted.
 * IO only.
 */
class SubtitleFiles(private val context: Context) {

    /** Code page for files that are not UTF-8 (from the settings). */
    @Volatile var encoding: String = SubtitleCharset.DEFAULT

    data class Found(val uri: Uri, val name: String)

    /** A path for `sub-add`: `fdclose://N` for a UTF-8 file, else a converted copy; null if unreadable. */
    fun openForMpv(uri: Uri, name: String? = null): String? {
        val resolver = context.contentResolver
        return try {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buf = input.readNBytes(MAX_BYTES + 1)
                if (buf.size > MAX_BYTES) null else buf
            }
            val converted = bytes?.let { SubtitleCharset.toUtf8OrNull(it, encoding) }
            if (converted == null) {
                val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
                "fdclose://${pfd.detachFd()}"
            } else {
                val ext = (name ?: displayName(uri)).substringAfterLast('.', "srt").lowercase()
                val dir = File(context.cacheDir, "subs").apply { mkdirs() }
                val out = File(dir, "sub_${uri.toString().hashCode().toUInt()}.$ext")
                out.writeBytes(converted)
                Log.i("VP", "Subtitle $uri is not UTF-8: converted from $encoding to ${out.name}")
                out.absolutePath
            }
        } catch (e: Exception) {
            Log.w("VP", "Opening subtitle $uri failed: ${e.message}")
            null
        }
    }

    /** Subtitle files next to [videoPath] in the first granted folder that holds it. */
    fun findNextTo(videoPath: String, treeUris: List<String>): List<Found> {
        val videoName = videoPath.substringAfterLast('/')
        for (tree in treeUris) {
            try {
                val treeUri = Uri.parse(tree)
                val folderId = SubtitleMatcher.folderDocumentId(DocumentsContract.getTreeDocumentId(treeUri), videoPath)
                    ?: continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
                val byName = LinkedHashMap<String, String>()
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        byName[name] = id
                    }
                }
                val names = SubtitleMatcher.select(videoName, byName.keys.toList())
                if (names.isNotEmpty()) {
                    return names.map { Found(DocumentsContract.buildDocumentUriUsingTree(treeUri, byName.getValue(it)), it) }
                }
            } catch (e: Exception) {
                Log.w("VP", "Listing subtitles in $tree failed: ${e.message}")
            }
        }
        return emptyList()
    }

    fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (_: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    companion object {
        /** Bigger files are handed to mpv unread (no text subtitle is this large). */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
