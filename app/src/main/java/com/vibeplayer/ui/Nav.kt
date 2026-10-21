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
    const val NETWORK = "network"
    const val REMOTE = "remote/{sourceId}"
    /** An unsaved URL source (an opened playlist link, a discovered DLNA server), handed over in memory. */
    const val REMOTE_TEMP = "remote_temp"

    fun remote(sourceId: Long) = "remote/$sourceId"

    /** The unsaved source the next REMOTE_TEMP screen shows (a URL survives no round trip through a route). */
    @Volatile var tempSource: com.vibeplayer.net.NetSource? = null

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
    val allVideosRaw by allVideosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // P12-06: hidden folders are left out of the folder list, search and Continue Watching.
    val hiddenFolders by preferences.hiddenFoldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val hiddenIds = remember(hiddenFolders) { hiddenFolders.map { it.bucketId }.toSet() }
    val allVideos = remember(allVideosRaw, hiddenIds) {
        if (hiddenIds.isEmpty()) allVideosRaw else allVideosRaw.filterNot { it.bucketId in hiddenIds }
    }
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
    val continueWatchingAll by continueWatchingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWatchingList = remember(continueWatchingAll, hiddenIds) {
        if (hiddenIds.isEmpty()) continueWatchingAll else continueWatchingAll.filterNot { it.video.bucketId in hiddenIds }
    }

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
            val allFolders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val folders = remember(allFolders, hiddenIds) { allFolders.filterNot { it.bucketId in hiddenIds } }

            FolderListScreen(
                folders = folders,
                db = db,
                onHideFolder = { folder ->
                    coroutineScope.launch {
                        Log.i("VP", "Hiding folder ${folder.name} (${folder.bucketId})")
                        preferences.setFolderHidden(com.vibeplayer.data.prefs.HiddenFolder(folder.bucketId, folder.name), hidden = true)
                    }
                },
                isLoading = false,
                continueWatchingItems = continueWatchingList,
                thumbnailCache = thumbnailCache,
                onFolderClick = { folder ->
                    Log.d("VP", "Navigating to folder ${folder.name} (${folder.bucketId})")
                    navController.navigate(NavRoutes.videos(folder.bucketId, folder.name))
                },
                onPlayVideo = onPlayVideo,
                allVideos = allVideos,
                historyMap = historyMap,
                onSettingsClick = {
                    Log.d("VP", "Navigating to settings")
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNetworkClick = { navController.navigate(NavRoutes.NETWORK) }
            )
        }

        composable(NavRoutes.NETWORK) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val netRepo = remember { com.vibeplayer.net.NetRepository(context.applicationContext) }
            com.vibeplayer.ui.net.NetworkScreen(
                repo = netRepo,
                preferences = preferences,
                onBack = { navController.popBackStack() },
                onBrowse = { s ->
                    if (s.id != 0L) navController.navigate(NavRoutes.remote(s.id))
                    else {
                        NavRoutes.tempSource = s
                        navController.navigate(NavRoutes.REMOTE_TEMP)
                    }
                }
            )
        }

        composable(
            route = NavRoutes.REMOTE,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType })
        ) { entry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val netRepo = remember { com.vibeplayer.net.NetRepository(context.applicationContext) }
            val id = entry.arguments?.getLong("sourceId") ?: 0L
            val source by androidx.compose.runtime.produceState<com.vibeplayer.net.NetSource?>(null, id) { value = netRepo.get(id) }
            source?.let { s -> com.vibeplayer.ui.net.RemoteBrowserScreen(netRepo, s, onBack = { navController.popBackStack() }) }
        }

        composable(NavRoutes.REMOTE_TEMP) { entry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val netRepo = remember { com.vibeplayer.net.NetRepository(context.applicationContext) }
            val s = remember(entry) { NavRoutes.tempSource }
            if (s != null) com.vibeplayer.ui.net.RemoteBrowserScreen(netRepo, s, onBack = { navController.popBackStack() })
            else androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
        }

        composable(NavRoutes.SETTINGS) {
            val currentSettings by settingsRepository.appSettingsFlow.collectAsStateWithLifecycle(
                initialValue = com.vibeplayer.data.prefs.AppSettings()
            )

            com.vibeplayer.ui.settings.SettingsScreen(
                settings = currentSettings,
                hiddenFolders = hiddenFolders,
                onUnhideFolder = { folder ->
                    coroutineScope.launch {
                        Log.i("VP", "Showing folder ${folder.name} again")
                        preferences.setFolderHidden(folder, hidden = false)
                    }
                },
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
                thumbnailContent = thumbnailContent,
                db = db
            )
        }
    }
}
