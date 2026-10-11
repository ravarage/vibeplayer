package com.vibeplayer.ui

import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibeplayer.data.media.MediaRepository
import com.vibeplayer.data.media.VideoItem
import com.vibeplayer.data.prefs.BrowserPreferences
import com.vibeplayer.data.prefs.VideoSortOrder
import com.vibeplayer.ui.browser.FolderListScreen
import com.vibeplayer.ui.browser.VideoListScreen
import kotlinx.coroutines.launch
import java.io.File

object NavRoutes {
    const val FOLDERS = "folders"
    const val VIDEOS = "videos/{bucketId}/{bucketName}"
    const val PLAYER = "player/{videoPath}"
    const val SETTINGS = "settings"

    fun videos(bucketId: String, bucketName: String): String {
        val encodedName = Uri.encode(bucketName)
        return "videos/$bucketId/$encodedName"
    }

    fun player(videoPath: String): String {
        val encodedPath = Uri.encode(videoPath)
        return "player/$encodedPath"
    }
}

@Composable
fun BrowserNavGraph(
    repository: MediaRepository,
    preferences: BrowserPreferences,
    settingsRepository: com.vibeplayer.data.prefs.AppSettingsRepository,
    db: com.vibeplayer.data.db.AppDatabase,
    thumbnailCache: com.vibeplayer.data.thumbs.ThumbnailCache,
    onPlayVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    thumbnailContent: (@Composable (VideoItem) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val historyList by db.fileSettingsDao().observeHistory().collectAsState(initial = emptyList())
    val allVideos by repository.observeAllVideos().collectAsState(initial = emptyList())

    fun normalizePath(p: String): String =
        p.replace("/sdcard/", "/storage/emulated/0/")

    val historyMap = androidx.compose.runtime.remember(historyList) {
        val map = mutableMapOf<String, com.vibeplayer.data.db.FileSettings>()
        for (item in historyList) {
            map[item.key] = item
            val p = item.key.substringBeforeLast(":")
            map[p] = item
            map[normalizePath(p)] = item
        }
        map
    }

    val continueWatchingList = androidx.compose.runtime.remember(historyList, allVideos) {
        historyList.filter { it.positionMs > 0 && it.durationMs > 0 }.mapNotNull { setting ->
            val path = setting.key.substringBeforeLast(":")
            val pathNorm = normalizePath(path)
            val fileName = java.io.File(path).name
            val video = allVideos.find {
                normalizePath(it.path) == pathNorm || it.displayName == fileName
            } ?: run {
                val file = java.io.File(path)
                if (file.exists()) {
                    VideoItem(
                        id = -1L,
                        uri = android.net.Uri.fromFile(file),
                        path = path,
                        displayName = file.name,
                        bucketId = "unknown",
                        bucketName = "Unknown",
                        durationMs = setting.durationMs,
                        sizeBytes = setting.key.substringAfterLast(":").toLongOrNull() ?: file.length(),
                        dateModified = file.lastModified() / 1000,
                        width = 0,
                        height = 0
                    )
                } else null
            }
            video?.let { com.vibeplayer.ui.browser.ContinueWatchingItem(it, setting) }
        }
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.FOLDERS,
        modifier = modifier
    ) {
        composable(NavRoutes.FOLDERS) {
            val folders by repository.observeFolders().collectAsState(initial = emptyList())

            FolderListScreen(
                folders = folders,
                isLoading = false,
                continueWatchingItems = continueWatchingList,
                thumbnailCache = thumbnailCache,
                onFolderClick = { folder ->
                    Log.d("VP", "Navigating to folder ${folder.name} (${folder.bucketId})")
                    navController.navigate(NavRoutes.videos(folder.bucketId, folder.name))
                },
                onPlayVideo = onPlayVideo,
                onSettingsClick = {
                    Log.d("VP", "Navigating to settings")
                    navController.navigate(NavRoutes.SETTINGS)
                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            val currentSettings by settingsRepository.appSettingsFlow.collectAsState(
                initial = com.vibeplayer.data.prefs.AppSettings()
            )

            com.vibeplayer.ui.settings.SettingsScreen(
                settings = currentSettings,
                onUpdateSettings = { newSettings ->
                    coroutineScope.launch {
                        Log.d("VP", "Updating app settings: $newSettings")
                        settingsRepository.updateSettings(newSettings)
                    }
                },
                onBack = {
                    Log.d("VP", "Navigating back from settings")
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = NavRoutes.VIDEOS,
            arguments = listOf(
                navArgument("bucketId") { type = NavType.StringType },
                navArgument("bucketName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getString("bucketId") ?: ""
            val encodedName = backStackEntry.arguments?.getString("bucketName") ?: ""
            val folderName = Uri.decode(encodedName)

            val videos by repository.observeVideosInBucket(bucketId).collectAsState(initial = emptyList())
            val sortOrder by preferences.sortOrderFlow.collectAsState(initial = VideoSortOrder.DATE_DESC)

            VideoListScreen(
                folderName = folderName,
                videos = videos,
                isLoading = false,
                sortOrder = sortOrder,
                historyMap = historyMap,
                onSortOrderChange = { newOrder ->
                    coroutineScope.launch {
                        Log.d("VP", "Setting sort order: $newOrder")
                        preferences.setSortOrder(newOrder)
                    }
                },
                onRefresh = {
                    coroutineScope.launch {
                        Log.d("VP", "Manual refresh requested for $folderName")
                        val testDir = File(Environment.getExternalStorageDirectory(), "Movies/vibeplayer-test")
                        repository.scanFolder(testDir.absolutePath)
                    }
                },
                onBack = {
                    Log.d("VP", "Navigating back to folders")
                    navController.popBackStack()
                },
                onPlayVideo = { video ->
                    onPlayVideo(video)
                },
                thumbnailCache = thumbnailCache,
                thumbnailContent = thumbnailContent
            )
        }
    }
}
