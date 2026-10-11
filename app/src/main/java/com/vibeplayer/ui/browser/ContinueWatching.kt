package com.vibeplayer.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.media.VideoItem
import com.vibeplayer.data.thumbs.AsyncThumbnail
import com.vibeplayer.data.thumbs.ThumbnailCache

data class ContinueWatchingItem(
    val video: VideoItem,
    val settings: FileSettings
)

@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    thumbnailCache: ThumbnailCache?,
    modifier: Modifier = Modifier
) {
    val progress = if (item.settings.durationMs > 0) {
        (item.settings.positionMs.toFloat() / item.settings.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        // Thumbnail with duration badge and bottom progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailCache != null) {
                AsyncThumbnail(
                    path = item.video.path,
                    sizeBytes = item.video.sizeBytes,
                    mtime = item.video.dateModified,
                    cache = thumbnailCache
                ) {
                    Text(text = "🎬", fontSize = 24.sp)
                }
            } else {
                Text(text = "🎬", fontSize = 24.sp)
            }

            // Duration badge at bottom right
            if (item.settings.durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 6.dp)
                        .background(
                            Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = formatDuration(item.settings.durationMs),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Progress bar along the bottom edge of thumbnail
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress)
                            .height(3.5.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.video.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val progressPercent = (progress * 100).toInt()
        Text(
            text = "${formatDuration(item.settings.positionMs)} ($progressPercent%)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
