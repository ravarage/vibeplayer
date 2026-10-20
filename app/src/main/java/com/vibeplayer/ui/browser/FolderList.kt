package com.vibeplayer.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vibeplayer.data.media.VideoSearch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.vibeplayer.data.media.VideoFolder
import com.vibeplayer.data.thumbs.AsyncThumbnail
import com.vibeplayer.data.thumbs.ThumbnailCache

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import com.vibeplayer.data.media.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    folders: List<VideoFolder>,
    isLoading: Boolean,
    continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    onFolderClick: (VideoFolder) -> Unit = {},
    onPlayVideo: ((VideoItem) -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    thumbnailCache: ThumbnailCache? = null,
    /** Every video, for the search across folders. */
    allVideos: List<VideoItem> = emptyList(),
    historyMap: Map<String, com.vibeplayer.data.db.FileSettings> = emptyMap(),
    /** Long-press on a folder hides it (P12-06). */
    onHideFolder: ((VideoFolder) -> Unit)? = null,
    /** For rename and delete in search results (P12-05). */
    db: com.vibeplayer.data.db.AppDatabase? = null
) {
    var actionTarget by remember { mutableStateOf<VideoItem?>(null) }
    if (db != null) VideoActionsHost(target = actionTarget, db = db, onDone = { actionTarget = null })
    var folderTarget by remember { mutableStateOf<VideoFolder?>(null) }
    folderTarget?.let { target ->
        FolderActionsDialog(folder = target, onHide = { onHideFolder?.invoke(target) }, onDismiss = { folderTarget = null })
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    // Built only while searching, and once per video list rather than on every keystroke.
    val searchIndex = remember(allVideos, searching) {
        if (searching) VideoSearch.index(allVideos.sortedBy { it.displayName.lowercase() }) else null
    }
    val results = remember(searchIndex, query) {
        if (query.isBlank() || searchIndex == null) emptyList() else VideoSearch.filter(searchIndex, query)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query = query, onQueryChange = { query = it }, placeholder = "Search all videos")
                    } else {
                        Text(
                            text = "Vibe Player",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
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
                    IconButton(onClick = onSettingsClick) {
                        Text(
                            text = "⚙",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                searching && query.isNotBlank() -> {
                    if (results.isEmpty()) {
                        Text(
                            text = "No videos match \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(items = results, key = { it.id }) { video ->
                                VideoItemRow(
                                    video = video,
                                    fileSettings = historyMap["${video.path}:${video.sizeBytes}"] ?: historyMap[video.path],
                                    onClick = { onPlayVideo?.invoke(video) },
                                    thumbnailCache = thumbnailCache,
                                    folderName = video.bucketName,
                                    onLongClick = if (db != null) ({ actionTarget = video }) else null
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
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                folders.isEmpty() && continueWatchingItems.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No video folders found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    // The history arrives after the folder list; the list keeps the first folder row
                    // anchored, which left "Continue Watching" scrolled off the top. Show it when the
                    // user has not scrolled down.
                    val hasContinueWatching = continueWatchingItems.isNotEmpty()
                    LaunchedEffect(hasContinueWatching) {
                        if (hasContinueWatching && listState.firstVisibleItemIndex <= 1) {
                            listState.scrollToItem(0)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (continueWatchingItems.isNotEmpty()) {
                            item(key = "continue_watching_section") {
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(
                                        text = "Continue Watching",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(
                                            items = continueWatchingItems,
                                            key = { it.settings.key }
                                        ) { item ->
                                            ContinueWatchingCard(
                                                item = item,
                                                onClick = { onPlayVideo?.invoke(item.video) },
                                                thumbnailCache = thumbnailCache
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }

                        items(
                            items = folders,
                            key = { it.bucketId }
                        ) { folder ->
                            FolderItemRow(
                                folder = folder,
                                onClick = { onFolderClick(folder) },
                                thumbnailCache = thumbnailCache,
                                onLongClick = if (onHideFolder != null) ({ folderTarget = folder }) else null
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderItemRow(
    folder: VideoFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailCache: ThumbnailCache? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = "folder_${folder.name}"
                testTag = "folder_${folder.name}"
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder thumbnail or icon container
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (thumbnailCache != null && !folder.firstVideoPath.isNullOrEmpty()) {
                    AsyncThumbnail(
                        path = folder.firstVideoPath,
                        sizeBytes = folder.firstVideoSize,
                        mtime = folder.firstVideoMtime,
                        cache = thumbnailCache
                    ) {
                        Text(
                            text = "📁",
                            fontSize = 24.sp
                        )
                    }
                } else {
                    Text(
                        text = "📁",
                        fontSize = 24.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val countText = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos"
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
