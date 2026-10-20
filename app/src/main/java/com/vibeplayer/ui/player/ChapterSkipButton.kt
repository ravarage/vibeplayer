package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.Chapter
import com.vibeplayer.player.ChapterSkip
import kotlinx.coroutines.delay

/** How long the skip button stays up on its own after a skippable chapter starts (controls hidden). */
private const val SKIP_BUTTON_SHOW_MS = 10_000L

/**
 * "Skip opening" / "Skip ending" while the current chapter is one: shown for the first seconds of the
 * chapter, and again whenever the controls are visible.
 */
@Composable
fun ChapterSkipButton(
    chapters: List<Chapter>,
    currentChapter: Int,
    controlsVisible: Boolean,
    onSkip: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val chapter = remember(chapters, currentChapter) { ChapterSkip.skippableCurrent(chapters, currentChapter) }
    var recentlyStarted by remember { mutableStateOf(false) }
    LaunchedEffect(chapter?.index) {
        recentlyStarted = chapter != null
        if (chapter != null) {
            delay(SKIP_BUTTON_SHOW_MS)
            recentlyStarted = false
        }
    }
    AnimatedVisibility(
        visible = chapter != null && (recentlyStarted || controlsVisible),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val label = chapter?.let { ChapterSkip.kindOf(it.title).skipLabel } ?: ""
        Text(
            text = "$label  ⏭",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB3000000))
                .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(8.dp))
                .clickable { chapter?.let { onSkip(it.index) } }
                .semantics {
                    contentDescription = "skip_chapter"
                    testTag = "skip_chapter"
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
