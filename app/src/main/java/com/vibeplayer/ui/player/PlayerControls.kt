package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.PlayerState
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun PlayerControls(
    playerEngine: PlayerEngine,
    playerState: PlayerState,
    positionMs: Long,
    videoPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var userSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }

    // Sync visibility with PlayerEngine so time-pos is unobserved when hidden
    LaunchedEffect(controlsVisible) {
        playerEngine.setControlsVisible(controlsVisible)
    }

    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(controlsVisible, lastInteraction, userSeeking, playerState.paused) {
        if (controlsVisible && !userSeeking && !playerState.paused) {
            delay(3000)
            controlsVisible = false
        }
    }

    val title = remember(videoPath) {
        if (videoPath.isNotEmpty()) {
            File(videoPath).name
        } else {
            "Video Player"
        }
    }

    val durationMs = playerState.durationMs
    val currentPosition = if (userSeeking) {
        (seekProgress * durationMs).toLong()
    } else {
        positionMs
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                controlsVisible = !controlsVisible
                if (controlsVisible) {
                    lastInteraction = System.currentTimeMillis()
                }
            }
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x44000000))
            ) {
                // Top Bar: Back button + Title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(
                            text = "←",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Center: Play / Pause toggle
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color(0x88000000), shape = CircleShape)
                        .clickable {
                            lastInteraction = System.currentTimeMillis()
                            playerEngine.togglePause()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (playerState.paused) "▶" else "❚❚",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Bottom Bar: Time labels + Seek Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xCC000000))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = formatTime(durationMs),
                            color = Color(0xFFCCCCCC),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    VideoSeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeekProgress = { targetMs ->
                            userSeeking = true
                            if (durationMs > 0) {
                                seekProgress = (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            }
                            lastInteraction = System.currentTimeMillis()
                            playerEngine.seekTo(targetMs, exact = false)
                        },
                        onSeekFinished = { targetMs ->
                            playerEngine.seekTo(targetMs, exact = true)
                            userSeeking = false
                            lastInteraction = System.currentTimeMillis()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
