package com.vibeplayer.data.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaRepository(private val context: Context) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allVideoSnapshots by lazy {
        observeQuery(null).shareIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)
    }

    suspend fun scanFolder(folderPath: String) = withContext(Dispatchers.IO) {
        val dir = File(folderPath)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles { file ->
                file.isFile && (file.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi", "mov", "ts", "flv"))
            } ?: emptyArray()

            val paths = files.map { it.absolutePath }.toTypedArray()
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, paths, null) { path, uri ->
                    Log.d("VP", "Scanned file: $path -> $uri")
                }
            }
        }
    }

    suspend fun queryVideos(bucketId: String? = null): List<VideoItem> = withContext(Dispatchers.IO) {
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
                bucketId?.let { "${MediaStore.Video.Media.BUCKET_ID} = ?" },
                bucketId?.let { arrayOf(it) },
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
        foldersFromVideos(queryVideos())
    }

    private fun foldersFromVideos(videos: List<VideoItem>): List<VideoFolder> {
        val grouped = videos.groupBy { it.bucketId }
        val folders = grouped.map { (bucketId, list) ->
            val first = list.firstOrNull()
            VideoFolder(
                bucketId = bucketId,
                name = first?.bucketName ?: "Unknown",
                videoCount = list.size,
                firstVideoPath = first?.path,
                firstVideoSize = first?.sizeBytes ?: 0L,
                firstVideoMtime = first?.dateModified ?: 0L
            )
        }.sortedBy { it.name.lowercase() }

        Log.d("VP", "Discovered ${folders.size} video folders in MediaStore:")
        for (folder in folders) {
            Log.d("VP", "Folder '${folder.name}' (id=${folder.bucketId}): ${folder.videoCount} videos")
        }

        return folders
    }

    suspend fun getVideosInBucket(bucketId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        queryVideos(bucketId)
    }

    fun observeFolders(): Flow<List<VideoFolder>> = allVideoSnapshots
        .map { foldersFromVideos(it) }
        .flowOn(Dispatchers.IO)

    fun observeVideosInBucket(bucketId: String): Flow<List<VideoItem>> = observeQuery(bucketId)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeQuery(bucketId: String?): Flow<List<VideoItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d("VP", "MediaStore ContentObserver onChange: refreshing bucket=$bucketId (uri=$uri)")
                trySend(Unit)
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        trySend(Unit)

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.conflate()
        .debounce(250L)
        .mapLatest { queryVideos(bucketId) }
        .flowOn(Dispatchers.IO)

    fun observeAllVideos(): Flow<List<VideoItem>> = allVideoSnapshots
}
