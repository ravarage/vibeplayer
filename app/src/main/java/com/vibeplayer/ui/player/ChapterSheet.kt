package com.vibeplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.Chapter
import com.vibeplayer.player.Chapters

/** P4-15: list of the file's chapters; tapping one seeks to its start and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully: in landscape the half-expanded sheet shows only the first row.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Chapters",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
