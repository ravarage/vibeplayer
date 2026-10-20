package com.vibeplayer.ui.player

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.vibeplayer.player.Chapter
import com.vibeplayer.player.Chapters

/** P4-15: list of the file's chapters; tapping one seeks to its start and closes the panel. */
@Composable
fun ChapterSheet(
    chapters: List<Chapter>,
    currentChapter: Int,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    TrackRecomposition("ChapterSheet")
    // Open on the current chapter so long lists don't start at the top.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = chapters.indexOfFirst { it.index == currentChapter }.coerceAtLeast(0)
    )
    PlayerPanel(title = "Chapters", onDismiss = onDismiss, listState = listState) {
        items(chapters, key = { it.index }) { chapter ->
            TrackRow(
                title = Chapters.displayTitle(chapter),
                subtitle = formatTime(chapter.startMs),
                isSelected = chapter.index == currentChapter,
                onClick = {
                    onSelectChapter(chapter.index)
                    onDismiss()
                },
                modifier = Modifier.semantics {
                    contentDescription = "chapter_${chapter.index}"
                    testTag = "chapter_${chapter.index}"
                }
            )
        }
    }
}
