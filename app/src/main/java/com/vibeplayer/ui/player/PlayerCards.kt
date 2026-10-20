package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.playback.ResumeInfo
import com.vibeplayer.playback.UpNext
import kotlinx.coroutines.delay

/** How long the resume chip stays after a file opened at its saved position. */
private const val RESUME_CHIP_SHOW_MS = 8_000L

private val CardShape = RoundedCornerShape(10.dp)
private val CardBackground = Color(0xCC000000)
private val CardBorder = Color(0x4DFFFFFF)

/** "Resumed at 12:30 · Start over" for a few seconds after a file opened at its saved position (P12-01). */
@Composable
fun ResumeChip(info: ResumeInfo?, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(info) {
        visible = info != null
        if (info != null) {
            delay(RESUME_CHIP_SHOW_MS)
            visible = false
        }
    }
    AnimatedVisibility(visible = visible && info != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(CardShape)
                .background(CardBackground)
                .border(1.dp, CardBorder, CardShape)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Resumed at ${formatTime(info?.positionMs ?: 0L)}",
                color = Color.White,
                fontSize = 13.sp
            )
            CardButton(text = "⟲ Start over", tag = "start_over", accent = true) {
                visible = false
                onStartOver()
            }
        }
    }
}

/** "Up next" with a countdown, Play now and Cancel, while the ended file waits for the next one (P12-02). */
@Composable
fun UpNextCard(upNext: UpNext?, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var secondsLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(upNext) {
        while (upNext != null) {
            secondsLeft = upNext.secondsLeft(android.os.SystemClock.elapsedRealtime())
            if (secondsLeft <= 0) break
            delay(250L)
        }
    }
    AnimatedVisibility(visible = upNext != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(CardShape)
                .background(CardBackground)
                .border(1.dp, CardBorder, CardShape)
                .semantics { contentDescription = "up_next"; testTag = "up_next" }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (secondsLeft > 0) "Up next in $secondsLeft s" else "Up next",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            Text(
                text = upNext?.title ?: "",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(text = "▶ Play now", tag = "up_next_play", accent = true, onClick = onPlayNow)
                CardButton(text = "Cancel", tag = "up_next_cancel", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun CardButton(text: String, tag: String, accent: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (accent) MaterialTheme.colorScheme.onPrimary else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = tag; testTag = tag }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
