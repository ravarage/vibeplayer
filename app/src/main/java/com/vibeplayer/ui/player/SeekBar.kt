package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VideoSeekBar(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val positionMs by positionFlow.collectAsState()
    VideoSeekBar(
        positionMs = positionMs,
        durationMs = durationMs,
        onSeekProgress = onSeekProgress,
        onSeekFinished = onSeekFinished,
        modifier = modifier
    )
}

@Composable
fun VideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
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

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val labelWidth = 64.dp
        val density = LocalDensity.current

        // Calculate thumb offset so label can follow the thumb
        val thumbOffset = with(density) {
            val totalWidthPx = totalWidth.toPx()
            val thumbPaddingPx = 10.dp.toPx() // Slider thumb radius approximation
            val effectiveWidthPx = (totalWidthPx - 2 * thumbPaddingPx).coerceAtLeast(0f)
            val thumbXPx = thumbPaddingPx + currentProgress * effectiveWidthPx
            val labelWidthPx = labelWidth.toPx()
            val labelXPx = (thumbXPx - labelWidthPx / 2f).coerceIn(0f, totalWidthPx - labelWidthPx)
            labelXPx.toDp()
        }

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
                    modifier = Modifier.offset(x = thumbOffset)
                ) {
                    Box(
                        modifier = Modifier
                            .width(labelWidth)
                            .background(
                                color = Color(0xDD222222),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatTime(scrubPositionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

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
        }
    }
}
