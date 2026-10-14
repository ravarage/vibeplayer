package com.vibeplayer.ui

import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

internal fun normalizeMediaPath(path: String): String =
    path.replace("/sdcard/", "/storage/emulated/0/")
        .replace("/storage/self/primary/", "/storage/emulated/0/")

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
    val historyFlow = remember(db) { db.fileSettingsDao().observeHistory() }
    val allVideosFlow = remember(repository) { repository.observeAllVideos() }
    val historyList by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWatchingFlow = remember(historyFlow, allVideosFlow) {
        combine(historyFlow, allVideosFlow) { history, videos -> history to videos }
            .map { (history, videos) ->
                val videosByPath = videos.associateBy { normalizeMediaPath(it.path) }
                history.asSequence()
                    .filter { it.positionMs > 0L && it.durationMs > 0L }
                    .distinctBy { normalizeMediaPath(it.key.substringBeforeLast(":")) }
                    .mapNotNull { setting ->
                        val path = setting.key.substringBeforeLast(":")
                        val normalizedPath = normalizeMediaPath(path)
                        val video = videosByPath[normalizedPath]
                            ?: File(path).takeIf { it.exists() }?.let { file ->
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
                            }
                        video?.let { com.vibeplayer.ui.browser.ContinueWatchingItem(it, setting) }
                    }.toList()
            }
            .flowOn(Dispatchers.IO)
    }
    val continueWatchingList by continueWatchingFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val historyMap = androidx.compose.runtime.remember(historyList) {
        val map = mutableMapOf<String, com.vibeplayer.data.db.FileSettings>()
        for (item in historyList) {
            map.putIfAbsent(item.key, item)
            val p = item.key.substringBeforeLast(":")
            map.putIfAbsent(p, item)
            val normP = normalizeMediaPath(p)
            map.putIfAbsent(normP, item)
            val size = item.key.substringAfterLast(":")
            if (size != item.key) {
                map.putIfAbsent("$normP:$size", item)
            }
        }
        map
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.FOLDERS,
        modifier = modifier
    ) {
        composable(NavRoutes.FOLDERS) {
            val foldersFlow = remember(repository) { repository.observeFolders() }
            val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())

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
            val currentSettings by settingsRepository.appSettingsFlow.collectAsStateWithLifecycle(
                initialValue = com.vibeplayer.data.prefs.AppSettings()
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

            val videosFlow = remember(repository, bucketId) { repository.observeVideosInBucket(bucketId) }
            val videos by videosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val sortOrder by preferences.sortOrderFlow.collectAsStateWithLifecycle(initialValue = VideoSortOrder.DATE_DESC)

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
