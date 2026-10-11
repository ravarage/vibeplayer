package com.vibeplayer.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun queryVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = it.getColumnIndex(MediaStore.Video.Media.DATA)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketIdCol = it.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val durationCol = it.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = it.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateModifiedCol = it.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val widthCol = it.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = it.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val path = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                    val displayName = it.getString(nameCol) ?: ""
                    val bucketId = if (bucketIdCol >= 0) it.getString(bucketIdCol) ?: "unknown" else "unknown"
                    val bucketName = if (bucketNameCol >= 0) it.getString(bucketNameCol) ?: "Unknown" else "Unknown"
                    val duration = if (durationCol >= 0) it.getLong(durationCol) else 0L
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val dateModified = if (dateModifiedCol >= 0) it.getLong(dateModifiedCol) else 0L
                    val width = if (widthCol >= 0) it.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) it.getInt(heightCol) else 0

                    videos.add(
                        VideoItem(
                            id = id,
                            uri = uri,
                            path = path,
                            displayName = displayName,
                            bucketId = bucketId,
                            bucketName = bucketName,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModified = dateModified,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("VP", "Error querying MediaStore videos", e)
        }

        videos
    }

    suspend fun getFolders(): List<VideoFolder> = withContext(Dispatchers.IO) {
        val videos = queryVideos()
        val grouped = videos.groupBy { it.bucketId }
        val folders = grouped.map { (bucketId, list) ->
            VideoFolder(
                bucketId = bucketId,
                name = list.firstOrNull()?.bucketName ?: "Unknown",
                videoCount = list.size,
                firstVideoPath = list.firstOrNull()?.path
            )
        }.sortedBy { it.name.lowercase() }

        Log.d("VP", "Discovered ${folders.size} video folders in MediaStore:")
        for (folder in folders) {
            Log.d("VP", "Folder '${folder.name}' (id=${folder.bucketId}): ${folder.videoCount} videos")
        }

        folders
    }

    suspend fun getVideosInBucket(bucketId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val all = queryVideos()
        all.filter { it.bucketId == bucketId }
    }
}
