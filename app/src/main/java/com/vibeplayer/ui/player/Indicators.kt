package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import kotlin.math.pow
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

@Composable
fun DoubleTapSkipIndicator(
    visible: Boolean,
    isLeft: Boolean,
    seconds: Int,
    rippleTrigger: Long,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        val shape = if (isLeft) {
            RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
        } else {
            RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
        }

        val rippleProgress = remember { Animatable(0f) }

        LaunchedEffect(rippleTrigger) {
            if (rippleTrigger > 0L) {
                rippleProgress.snapTo(0f)
                rippleProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 400,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.42f)
                .clip(shape)
                .background(Color(0x55000000)),
            contentAlignment = Alignment.Center
        ) {
            val currentRipple = rippleProgress.value
            if (currentRipple > 0f && currentRipple < 1f) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            scaleX = 0.3f + currentRipple * 1.5f
                            scaleY = 0.3f + currentRipple * 1.5f
                            alpha = (1f - currentRipple) * 0.6f
                        }
                        .background(Color.White.copy(alpha = 0.4f), CircleShape)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isLeft) "◀◀" else "▶▶",
                    fontSize = 28.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                val sign = if (isLeft) "-" else "+"
                Text(
                    text = "$sign$seconds s",
                    fontSize = 20.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ResetZoomButton(
    visible: Boolean,
    zoom: Double,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val percent = (2.0.pow(zoom) * 100).roundToInt()
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC1E1E1E))
                .clickable { onReset() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "🔍",
                    fontSize = 14.sp
                )
                Text(
                    text = "Reset Zoom ($percent%)",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AspectIndicator(
    visible: Boolean,
    aspect: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        val label = when (aspect.lowercase()) {
            "fit" -> "Fit"
            "crop" -> "Crop"
            "stretch" -> "Stretch"
            "16:9" -> "16:9"
            "4:3" -> "4:3"
            else -> aspect.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
