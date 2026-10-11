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
    thumbnailCache: com.vibeplayer.data.thumbs.ThumbnailCache,
    onPlayVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    thumbnailContent: (@Composable (VideoItem) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

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
                thumbnailCache = thumbnailCache,
                onFolderClick = { folder ->
                    Log.d("VP", "Navigating to folder ${folder.name} (${folder.bucketId})")
                    navController.navigate(NavRoutes.videos(folder.bucketId, folder.name))
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
