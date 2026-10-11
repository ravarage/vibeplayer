package com.vibeplayer.ui.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.media.MediaRepository
import com.vibeplayer.data.media.VideoFolder
import com.vibeplayer.data.media.VideoItem
import kotlinx.coroutines.launch

@Composable
fun BrowserRoot(
    modifier: Modifier = Modifier,
    onPlayVideo: (VideoItem) -> Unit = { item ->
        Log.d("VP", "Play video requested: ${item.displayName} (${item.path})")
    }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { MediaRepository(context.applicationContext) }
    val db = remember { AppDatabase.getInstance(context.applicationContext) }

    var folders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var isLoadingFolders by remember { mutableStateOf(true) }

    var selectedFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var folderVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingVideos by remember { mutableStateOf(false) }

    // Verify Room round-trip on startup
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val dao = db.fileSettingsDao()
                val testKey = "/sdcard/Movies/vibeplayer-test/ichiban.mp4:10485760"
                val testItem = FileSettings(
                    key = testKey,
                    positionMs = 45000L,
                    durationMs = 120000L,
                    audioTrackId = 1,
                    subTrackId = 2,
                    subEnabled = true,
                    speed = 1.25f,
                    aspect = "fit",
                    lastPlayedAt = System.currentTimeMillis()
                )
                dao.insert(testItem)
                val readBack = dao.getByKey(testKey)
                if (readBack != null && readBack.key == testKey && readBack.positionMs == 45000L) {
                    Log.d("VP", "Room round trip success: key=${readBack.key}, pos=${readBack.positionMs}ms, dur=${readBack.durationMs}ms, speed=${readBack.speed}, aspect=${readBack.aspect}")
                } else {
                    Log.e("VP", "Room round trip failed: readBack=$readBack")
                }
            } catch (e: Exception) {
                Log.e("VP", "Room test failed with exception", e)
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoadingFolders = true
        folders = repository.getFolders()
        isLoadingFolders = false
    }

    LaunchedEffect(selectedFolder) {
        val folder = selectedFolder
        if (folder != null) {
            isLoadingVideos = true
            folderVideos = repository.getVideosInBucket(folder.bucketId)
            isLoadingVideos = false
        } else {
            folderVideos = emptyList()
        }
    }

    val currentFolder = selectedFolder
    if (currentFolder == null) {
        FolderListScreen(
            folders = folders,
            isLoading = isLoadingFolders,
            onFolderClick = { folder ->
                Log.d("VP", "Selected folder: ${folder.name} (${folder.videoCount} videos)")
                selectedFolder = folder
            },
            modifier = modifier
        )
    } else {
        VideoListScreen(
            folder = currentFolder,
            videos = folderVideos,
            isLoading = isLoadingVideos,
            onBack = {
                Log.d("VP", "Back to folder list from ${currentFolder.name}")
                selectedFolder = null
            },
            onPlayVideo = onPlayVideo,
            modifier = modifier
        )
    }
}
