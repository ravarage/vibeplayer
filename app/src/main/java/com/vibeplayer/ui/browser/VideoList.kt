package com.vibeplayer.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.vibeplayer.data.media.VideoItem
import com.vibeplayer.data.media.VideoSearch
import com.vibeplayer.data.prefs.VideoSortOrder
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    folderName: String,
    videos: List<VideoItem>,
    isLoading: Boolean,
    sortOrder: VideoSortOrder = VideoSortOrder.DATE_DESC,
    historyMap: Map<String, com.vibeplayer.data.db.FileSettings> = emptyMap(),
    onSortOrderChange: (VideoSortOrder) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailCache: com.vibeplayer.data.thumbs.ThumbnailCache? = null,
    thumbnailContent: (@Composable (VideoItem) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val sortedVideos = remember(videos, sortOrder) {
        when (sortOrder) {
            VideoSortOrder.NAME_ASC -> videos.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            VideoSortOrder.NAME_DESC -> videos.sortedByDescending { it.displayName.lowercase(Locale.ROOT) }
            VideoSortOrder.DATE_DESC -> videos.sortedByDescending { it.dateModified }
            VideoSortOrder.DATE_ASC -> videos.sortedBy { it.dateModified }
            VideoSortOrder.SIZE_DESC -> videos.sortedByDescending { it.sizeBytes }
            VideoSortOrder.SIZE_ASC -> videos.sortedBy { it.sizeBytes }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    val searchIndex = remember(sortedVideos) { VideoSearch.index(sortedVideos) }
    val shownVideos = remember(searchIndex, query) { VideoSearch.filter(searchIndex, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query = query, onQueryChange = { query = it }, placeholder = "Search in $folderName")
                    } else {
                        Column {
                            Text(
                                text = folderName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${sortedVideos.size} videos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(
                            onClick = { searching = true },
                            modifier = Modifier.semantics {
                                contentDescription = "search"
                                testTag = "search"
                            }
                        ) {
                            Text(text = "🔍", fontSize = 18.sp)
                        }
                    }
                    if (onRefresh != null && !searching) {
                        IconButton(onClick = onRefresh) {
                            Text(text = "🔄", fontSize = 18.sp)
                        }
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Text(
                            text = "⇅",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        VideoSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (order == sortOrder) {
                                            Text(
                                                text = "✓ ",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Text(text = "    ")
                                        }
                                        Text(text = order.displayName)
                                    }
                                },
                                onClick = {
                                    menuExpanded = false
                                    onSortOrderChange(order)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                shownVideos.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (sortedVideos.isEmpty()) "No videos in this folder" else "No videos match \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = shownVideos,
                            key = { it.id }
                        ) { video ->
                            val setting = historyMap["${video.path}:${video.sizeBytes}"]
                                ?: historyMap[video.path]

                            VideoItemRow(
                                video = video,
                                fileSettings = setting,
                                onClick = { onPlayVideo(video) },
                                thumbnailCache = thumbnailCache,
                                thumbnailContent = thumbnailContent
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 124.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoItemRow(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fileSettings: com.vibeplayer.data.db.FileSettings? = null,
    thumbnailCache: com.vibeplayer.data.thumbs.ThumbnailCache? = null,
    thumbnailContent: (@Composable (VideoItem) -> Unit)? = null,
    /** Shown in the detail line (search results across folders). */
    folderName: String? = null
) {
    val watched = com.vibeplayer.data.db.WatchProgress.isWatched(fileSettings)
    val progress = if (watched) 0f else if (fileSettings != null && fileSettings.durationMs > 0 && fileSettings.positionMs > 0) {
        (fileSettings.positionMs.toFloat() / fileSettings.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "video_${video.displayName}"
                testTag = "video_${video.displayName}"
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or placeholder with bottom progress bar
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics {
                    contentDescription = "thumb_${video.displayName}"
                    testTag = "thumb_${video.displayName}"
                },
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailCache != null) {
                com.vibeplayer.data.thumbs.AsyncThumbnail(
                    path = video.path,
                    sizeBytes = video.sizeBytes,
                    mtime = video.dateModified,
                    cache = thumbnailCache
                ) {
                    Text(
                        text = "🎬",
                        fontSize = 20.sp
                    )
                }
            } else if (thumbnailContent != null) {
                thumbnailContent(video)
            } else {
                Text(
                    text = "🎬",
                    fontSize = 20.sp
                )
            }

            // Duration badge at bottom right (placed slightly above progress bar)
            if (video.durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = if (progress > 0f) 5.dp else 3.dp)
                        .background(
                            Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = formatDuration(video.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Watched: a ✓ badge instead of a full progress bar
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .semantics { contentDescription = "watched_${video.displayName}" }
                ) {
                    Text(text = "✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Progress bar along the bottom edge of thumbnail
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // Watched files are dimmed a little, like MX Player.
                color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            val metaDetails = buildList {
                if (!folderName.isNullOrEmpty()) add("📁 $folderName")
                if (video.width > 0 && video.height > 0) {
                    add("${video.width}×${video.height}")
                }
                if (video.sizeBytes > 0) {
                    add(formatFileSize(video.sizeBytes))
                }
            }.joinToString(" • ")

            if (metaDetails.isNotEmpty()) {
                Text(
                    text = metaDetails,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0

    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
