package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun BrightnessIndicator(
    visible: Boolean,
    brightness: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "☀",
                    fontSize = 28.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                val percent = (brightness * 100f).roundToInt().coerceIn(0, 100)
                Text(
                    text = "$percent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(fraction = brightness.coerceIn(0f, 1f))
                            .width(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
fun VolumeIndicator(
    visible: Boolean,
    volume: Int,
    maxVolume: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val fraction = if (maxVolume > 0) (volume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f) else 0f
        val icon = when {
            volume <= 0 -> "🔇"
            fraction < 0.5f -> "🔉"
            else -> "🔊"
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = icon,
                    fontSize = 28.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                val percent = (fraction * 100f).roundToInt()
                Text(
                    text = "$percent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x44FFFFFF)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(fraction = fraction)
                            .width(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
fun SwipeSeekIndicator(
    visible: Boolean,
    deltaSeconds: Long,
    targetPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val sign = if (deltaSeconds >= 0) "+" else "-"
        val absSec = kotlin.math.abs(deltaSeconds)
        val deltaFormatted = String.format(java.util.Locale.US, "%s%02d:%02d", sign, absSec / 60, absSec % 60)
        val targetFormatted = formatTime(targetPositionMs)

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$deltaFormatted / $targetFormatted",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
