package com.vibeplayer.ui.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.media.MediaRepository
import com.vibeplayer.data.media.VideoItem
import com.vibeplayer.data.prefs.BrowserPreferences
import com.vibeplayer.data.thumbs.ThumbnailCache
import com.vibeplayer.data.thumbs.ThumbnailGenerator
import com.vibeplayer.ui.BrowserNavGraph
import kotlinx.coroutines.launch

@Composable
fun BrowserRoot(
    modifier: Modifier = Modifier,
    onPlayVideo: ((VideoItem) -> Unit)? = null,
    thumbnailContent: (@Composable (VideoItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val effectiveOnPlayVideo: (VideoItem) -> Unit = onPlayVideo ?: { item ->
        val uri = android.net.Uri.fromFile(java.io.File(item.path))
        Log.i("VP", "Starting ACTION_VIEW intent for Uri: $uri")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setClassName(context.packageName, "com.vibeplayer.ui.player.PlayerActivity")
            if (context !is android.app.Activity) {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("VP", "PlayerActivity not found or failed to start for $uri: ${e.message}")
            android.widget.Toast.makeText(context, "Player not available: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { MediaRepository(context.applicationContext) }
    val preferences = remember { BrowserPreferences(context.applicationContext) }
    val db = remember { AppDatabase.getInstance(context.applicationContext) }
    val thumbnailGenerator = remember { ThumbnailGenerator() }
    val thumbnailCache = remember { ThumbnailCache(context.applicationContext, thumbnailGenerator) }
    val navController = rememberNavController()

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

    BrowserNavGraph(
        repository = repository,
        preferences = preferences,
        thumbnailCache = thumbnailCache,
        onPlayVideo = effectiveOnPlayVideo,
        modifier = modifier,
        navController = navController,
        thumbnailContent = thumbnailContent
    )
}
