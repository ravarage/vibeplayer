package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
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
import com.vibeplayer.player.Chapters
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VideoSeekBar(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList()
) {
    val positionMs by positionFlow.collectAsState()
    VideoSeekBar(
        positionMs = positionMs,
        durationMs = durationMs,
        onSeekProgress = onSeekProgress,
        onSeekFinished = onSeekFinished,
        modifier = modifier,
        chapters = chapters
    )
}

@Composable
fun VideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList()
) {
    val view = LocalView.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var lastEndHit by remember { mutableStateOf<Int?>(null) }
    var labelWidthPx by remember { mutableIntStateOf(0) }
    // P4-15: depends only on chapters + duration, never on the position.
    val tickFractions = remember(chapters, durationMs) { Chapters.tickFractions(chapters, durationMs) }

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

    Box(modifier = modifier.fillMaxWidth().onSizeChanged { containerWidthPx = it.width }) {
        val labelMinWidth = 64.dp
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
                        val thumbX = thumbPaddingPx + currentProgress * (widthPx - 2 * thumbPaddingPx).coerceAtLeast(0)
                        val maxX = (widthPx - labelWidthPx).coerceAtLeast(0)
                        IntOffset((thumbX - labelWidthPx / 2).toInt().coerceIn(0, maxX), 0)
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = labelMinWidth, max = 220.dp)
                            .onSizeChanged { labelWidthPx = it.width }
                            .background(
                                color = Color(0xDD222222),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = formatTime(scrubPositionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        // P4-15: the chapter under the thumb while scrubbing.
                        val chapter = if (chapters.isEmpty()) null else Chapters.chapterAt(chapters, scrubPositionMs)
                        if (chapter != null) {
                            Text(
                                text = Chapters.displayTitle(chapter),
                                color = Color(0xFFDDDDDD),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
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
            if (tickFractions.isNotEmpty()) {
                // P4-15: chapter start ticks over the track; drawn in the draw phase only.
                Canvas(modifier = Modifier.matchParentSize()) {
                    val inset = 10.dp.toPx()
                    val trackWidth = (size.width - 2 * inset).coerceAtLeast(0f)
                    val half = 5.dp.toPx()
                    val cy = size.height / 2f
                    val stroke = 2.dp.toPx()
                    for (f in tickFractions) {
                        val x = inset + f * trackWidth
                        drawLine(Color(0xCCFFFFFF), Offset(x, cy - half), Offset(x, cy + half), strokeWidth = stroke)
                    }
                }
            }
            }
        }
    }
}
