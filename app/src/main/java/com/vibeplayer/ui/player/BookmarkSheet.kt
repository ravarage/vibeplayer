package com.vibeplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.data.db.Bookmark

/** P12-03: the file's bookmarks; tap one to jump there, ✕ to delete, "+ Here" to add one at the current spot. */
@Composable
fun BookmarkSheet(
    bookmarks: List<Bookmark>,
    onAdd: () -> Unit,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    TrackRecomposition("BookmarkSheet")
    PlayerPanel(
        title = "Bookmarks",
        onDismiss = onDismiss,
        headerActions = { PanelChip(text = "+ Here", onClick = onAdd, tag = "bookmark_add") }
    ) {
        if (bookmarks.isEmpty()) {
            item { PanelSectionLabel("No bookmarks yet: tap + Here to save this spot") }
        }
        items(bookmarks, key = { it.id }) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackRow(
                    title = formatTime(b.positionMs),
                    subtitle = b.label ?: "",
                    isSelected = false,
                    onClick = {
                        onJump(b)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "bookmark_${b.id}"
                            testTag = "bookmark_${b.id}"
                        }
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onDelete(b) }
                        .semantics { contentDescription = "bookmark_delete_${b.id}" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", fontSize = 13.sp, color = PanelColors.secondaryText)
                }
            }
        }
    }
}
