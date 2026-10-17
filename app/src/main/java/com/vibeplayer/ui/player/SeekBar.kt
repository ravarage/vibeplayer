package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.Chapter
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VideoSeekBar(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    chapters: List<Chapter> = emptyList(),
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val positionMs by positionFlow.collectAsState()
    VideoSeekBar(
        positionMs = positionMs,
        durationMs = durationMs,
        chapters = chapters,
        onSeekProgress = onSeekProgress,
        onSeekFinished = onSeekFinished,
        modifier = modifier
    )
}

@Composable
fun VideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter> = emptyList(),
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var labelWidthPx by remember { mutableIntStateOf(0) }
    var lastEndHit by remember { mutableStateOf<Int?>(null) }

    val currentProgress = if (durationMs > 0) {
        if (isDragging) dragProgress else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val scrubPositionMs = if (durationMs > 0) {
        (currentProgress * durationMs).toLong()
    } else {
        0L
    }

    val scrubChapter = remember(chapters, scrubPositionMs) {
        if (chapters.isEmpty()) null
        else chapters.filter { it.startMs <= scrubPositionMs }.maxByOrNull { it.startMs }
    }

    Box(modifier = modifier.fillMaxWidth().onSizeChanged { containerWidthPx = it.width }) {
        val density = LocalDensity.current

        Column(modifier = Modifier.fillMaxWidth()) {
            // Position label that follows the thumb while dragging
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isDragging,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.offset {
                        val thumbPaddingPx = with(density) { 10.dp.roundToPx() }
                        val widthPx = containerWidthPx
                        val currentLabelWidthPx = if (labelWidthPx > 0) labelWidthPx else with(density) { 64.dp.roundToPx() }
                        val thumbX = thumbPaddingPx + currentProgress * (widthPx - 2 * thumbPaddingPx).coerceAtLeast(0)
                        val maxX = (widthPx - currentLabelWidthPx).coerceAtLeast(0)
                        IntOffset((thumbX - currentLabelWidthPx / 2).toInt().coerceIn(0, maxX), 0)
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 64.dp, max = 240.dp)
                            .onSizeChanged { labelWidthPx = it.width }
                            .background(
                                color = Color(0xDD222222),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatTime(scrubPositionMs),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            if (scrubChapter != null) {
                                Text(
                                    text = scrubChapter.title,
                                    color = Color(0xFFDDDDDD),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = currentProgress,
                    onValueChange = { progress ->
                        isDragging = true
                        dragProgress = progress
                        val targetMs = (progress * durationMs).toLong()
                        if (progress <= 0.02f) {
                            if (lastEndHit != -1) {
                                lastEndHit = -1
                                HapticFeedbackHelper.triggerTick(view, "seek bar start")
                            }
                        } else if (progress >= 0.98f) {
                            if (lastEndHit != 1) {
                                lastEndHit = 1
                                HapticFeedbackHelper.triggerTick(view, "seek bar end")
                            }
                        } else if (progress in 0.05f..0.95f) {
                            lastEndHit = null
                        }
                        onSeekProgress(targetMs)
                    },
                    onValueChangeFinished = {
                        val targetMs = (dragProgress * durationMs).toLong()
                        onSeekFinished(targetMs)
                        isDragging = false
                        lastEndHit = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color(0x66FFFFFF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                ChapterTicks(
                    chapters = chapters,
                    durationMs = durationMs,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

@Composable
private fun ChapterTicks(
    chapters: List<Chapter>,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    TrackRecomposition("ChapterTicks")
    if (durationMs <= 0L || chapters.isEmpty()) return
    val density = LocalDensity.current
    val thumbPaddingPx = with(density) { 10.dp.toPx() }
    val tickWidthPx = with(density) { 2.dp.toPx() }
    val tickHeightPx = with(density) { 10.dp.toPx() }

    Canvas(modifier = modifier) {
        val availableWidth = size.width - 2 * thumbPaddingPx
        if (availableWidth <= 0f) return@Canvas
        val centerY = size.height / 2f
        for (chapter in chapters) {
            if (chapter.startMs <= 0L || chapter.startMs >= durationMs) continue
            val fraction = chapter.startMs.toFloat() / durationMs.toFloat()
            val x = thumbPaddingPx + fraction * availableWidth
            drawLine(
                color = Color(0xDD000000),
                start = androidx.compose.ui.geometry.Offset(x, centerY - tickHeightPx / 2f),
                end = androidx.compose.ui.geometry.Offset(x, centerY + tickHeightPx / 2f),
                strokeWidth = tickWidthPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}
