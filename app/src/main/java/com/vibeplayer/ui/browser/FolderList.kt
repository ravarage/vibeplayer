package com.vibeplayer.ui.browser

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.data.media.VideoFolder
import com.vibeplayer.data.thumbs.AsyncThumbnail
import com.vibeplayer.data.thumbs.ThumbnailCache

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    folders: List<VideoFolder>,
    isLoading: Boolean,
    onFolderClick: (VideoFolder) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    thumbnailCache: ThumbnailCache? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Folders (${folders.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
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
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                folders.isEmpty() -> {
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = folders,
                            key = { it.bucketId }
                        ) { folder ->
                            FolderItemRow(
                                folder = folder,
                                onClick = { onFolderClick(folder) },
                                thumbnailCache = thumbnailCache
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
    thumbnailCache: ThumbnailCache? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
