package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

@Composable
fun PlayerControls(
    playerEngine: PlayerEngine,
    playerState: PlayerState,
    videoPath: String,
    controlsVisible: Boolean,
    onBack: () -> Unit,
    onToggleDebug: () -> Unit = {},
    onResetInactivity: () -> Unit = {},
    onHideControls: () -> Unit = {},
    isLocked: Boolean = false,
    onToggleLock: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    canSkipPrevious: Boolean = false,
    canSkipNext: Boolean = false,
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSelectAudioTrack: ((Int?) -> Unit)? = null,
    onSelectSubTrack: ((Int?) -> Unit)? = null,
    onSelectSpeed: ((Double) -> Unit)? = null,
    onPersistSpeed: ((Double) -> Unit)? = null,
    onSetSubDelay: ((Int) -> Unit)? = null,
    onSetAudioDelay: ((Int) -> Unit)? = null,
    onCycleAspect: (() -> Unit)? = null,
    onToggleMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout)
) {
    val interactionEvents = remember { MutableStateFlow(System.currentTimeMillis()) }

    DisposableEffect(playerEngine) {
        onDispose { playerEngine.setControlsVisible(false) }
    }

    // Sync visibility with PlayerEngine so time-pos is unobserved when hidden
    LaunchedEffect(controlsVisible) {
        playerEngine.setControlsVisible(controlsVisible)
        if (controlsVisible) {
            interactionEvents.value = System.currentTimeMillis()
        }
    }

    var showAudioTrackSheet by remember { mutableStateOf(false) }
    var showSubTrackSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSyncSheet by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds of inactivity (paused while sheet is open)
    LaunchedEffect(controlsVisible, playerState.paused, showAudioTrackSheet, showSubTrackSheet, showSpeedSheet, showSyncSheet) {
        if (controlsVisible && !playerState.paused && !showAudioTrackSheet && !showSubTrackSheet && !showSpeedSheet && !showSyncSheet) {
            interactionEvents.collectLatest { timestamp ->
                if (timestamp != Long.MAX_VALUE) {
                    delay(3000)
                    onHideControls()
                }
            }
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

    Box(modifier = modifier.fillMaxSize()) {
        if (isLocked) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Background touch interceptor: consumes all gestures
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                }
                            }
                        }
                )

                // Unlock button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 28.dp, start = 28.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xCC1E1E1E))
                        .clickable { onToggleLock() }
                        .semantics {
                            contentDescription = "unlock"
                            testTag = "unlock"
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "🔒", fontSize = 18.sp)
                        Text(
                            text = "Unlock",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
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
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            interactionEvents.value = System.currentTimeMillis()
                                            onToggleDebug()
                                        }
                                    )
                                }
                        )

                        if (playerState.fallback) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SW FALLBACK",
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0x33FF0000), shape = CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (playerEngine.playbackConfig.mode == com.vibeplayer.player.PlaybackMode.QUALITY) Color(0x3300E676) else Color(0x22FFFFFF))
                                .clickable {
                                    interactionEvents.value = System.currentTimeMillis()
                                    onToggleMode?.invoke()
                                }
                                .semantics {
                                    contentDescription = "quality_mode"
                                    testTag = "quality_mode"
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (playerEngine.playbackConfig.mode == com.vibeplayer.player.PlaybackMode.QUALITY) "QUALITY" else "EFFICIENT",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (playerEngine.playbackConfig.mode == com.vibeplayer.player.PlaybackMode.QUALITY) Color(0xFF00E676) else Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (playerState.speed != 1.0) Color(0x33BB86FC) else Color(0x22FFFFFF))
                                .clickable {
                                    interactionEvents.value = System.currentTimeMillis()
                                    showSpeedSheet = true
                                }
                                .semantics {
                                    contentDescription = "speed"
                                    testTag = "speed"
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val speedText = if (playerState.speed % 1.0 == 0.0) {
                                "${playerState.speed.toInt()}x"
                            } else {
                                "${playerState.speed}x"
                            }
                            Text(
                                text = speedText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (playerState.speed != 1.0) Color(0xFFBB86FC) else Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                interactionEvents.value = System.currentTimeMillis()
                                showAudioTrackSheet = true
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "audio"
                                    testTag = "audio"
                                }
                        ) {
                            Text(
                                text = "🎵",
                                fontSize = 20.sp,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                interactionEvents.value = System.currentTimeMillis()
                                showSubTrackSheet = true
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "sub"
                                    testTag = "sub"
                                }
                        ) {
                            Text(
                                text = "💬",
                                fontSize = 20.sp,
                                color = if (playerState.selectedSubTrackId != null) Color(0xFFBB86FC) else Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                interactionEvents.value = System.currentTimeMillis()
                                onCycleAspect?.invoke()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "aspect"
                                    testTag = "aspect"
                                }
                        ) {
                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.vibeplayer.R.drawable.ic_aspect_ratio),
                                contentDescription = "aspect",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                interactionEvents.value = System.currentTimeMillis()
                                onEnterPip()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "pip"
                                    testTag = "pip"
                                }
                        ) {
                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.vibeplayer.R.drawable.ic_pip),
                                contentDescription = "pip",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                interactionEvents.value = System.currentTimeMillis()
                                onToggleLock()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "lock"
                                    testTag = "lock"
                                }
                        ) {
                            Text(
                                text = "🔒",
                                fontSize = 20.sp,
                                color = Color.White
                            )
                        }
                    }

                // Previous / play-pause / next, grouped in the center like a video-player remote.
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = {
                            interactionEvents.value = System.currentTimeMillis()
                            onSkipPrevious()
                        },
                        enabled = canSkipPrevious,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0x88000000), shape = CircleShape)
                            .semantics {
                                contentDescription = "prev"
                                testTag = "prev"
                            }
                    ) {
                        Text("|◀", color = Color.White.copy(alpha = if (canSkipPrevious) 1f else 0.35f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0x88000000), shape = CircleShape)
                            .clickable {
                                interactionEvents.value = System.currentTimeMillis()
                                playerEngine.togglePause()
                            }
                            .semantics {
                                contentDescription = "play_pause"
                                testTag = "play_pause"
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

                    IconButton(
                        onClick = {
                            interactionEvents.value = System.currentTimeMillis()
                            onSkipNext()
                        },
                        enabled = canSkipNext,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0x88000000), shape = CircleShape)
                            .semantics {
                                contentDescription = "next"
                                testTag = "next"
                            }
                    ) {
                        Text("▶|", color = Color.White.copy(alpha = if (canSkipNext) 1f else 0.35f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                SeekControls(
                    positionFlow = playerEngine.position,
                    durationMs = durationMs,
                    onSeekProgress = { playerEngine.seekTo(it, exact = false) },
                    onSeekFinished = { playerEngine.seekTo(it, exact = true) },
                    onScrubbingChanged = { scrubbing ->
                        interactionEvents.value = if (scrubbing) Long.MAX_VALUE else System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        if (showAudioTrackSheet) {
            AudioTrackSheet(
                tracks = playerState.tracks,
                selectedTrackId = playerState.selectedAudioTrackId,
                onSelectTrack = { id ->
                    if (onSelectAudioTrack != null) {
                        onSelectAudioTrack(id)
                    } else {
                        playerEngine.setAudioTrack(id)
                    }
                },
                onDismiss = {
                    showAudioTrackSheet = false
                    interactionEvents.value = System.currentTimeMillis()
                }
            )
        }

        if (showSubTrackSheet) {
            SubtitleTrackSheet(
                tracks = playerState.tracks,
                selectedTrackId = playerState.selectedSubTrackId,
                onSelectTrack = { id ->
                    if (onSelectSubTrack != null) {
                        onSelectSubTrack(id)
                    } else {
                        playerEngine.setSubtitleTrack(id)
                    }
                },
                onDismiss = {
                    showSubTrackSheet = false
                    interactionEvents.value = System.currentTimeMillis()
                },
                onOpenSync = {
                    showSubTrackSheet = false
                    showSyncSheet = true
                }
            )
        }

        if (showSyncSheet) {
            SyncSheet(
                subDelayMs = playerState.subDelayMs,
                audioDelayMs = playerState.audioDelayMs,
                onSubDelay = { ms -> onSetSubDelay?.invoke(ms) ?: playerEngine.setSubDelay(ms) },
                onAudioDelay = { ms -> onSetAudioDelay?.invoke(ms) ?: playerEngine.setAudioDelay(ms) },
                onDismiss = {
                    showSyncSheet = false
                    interactionEvents.value = System.currentTimeMillis()
                }
            )
        }

        if (showSpeedSheet) {
            SpeedSheet(
                currentSpeed = playerState.speed,
                onSpeedChanged = { speed ->
                    if (onSelectSpeed != null) {
                        onSelectSpeed(speed)
                    } else {
                        playerEngine.setSpeed(speed)
                    }
                },
                onSpeedChangeFinished = { speed ->
                    onPersistSpeed?.invoke(speed)
                },
                onDismiss = {
                    showSpeedSheet = false
                    interactionEvents.value = System.currentTimeMillis()
                }
            )
        }
    }
}
}

fun formatTime(millis: Long): String {
    return formatTimeSeconds((millis / 1000).coerceAtLeast(0))
}

private fun formatTimeSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun SeekControls(
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    durationMs: Long,
    onSeekProgress: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val positionSeconds by remember(positionFlow) {
        positionFlow.map { it / 1000 }.distinctUntilChanged()
    }.collectAsState(initial = positionFlow.value / 1000)
    var userSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shownSeconds = if (userSeeking) (seekProgress * durationMs / 1000).toLong() else positionSeconds
            Text(
                text = formatTimeSeconds(shownSeconds),
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
            positionFlow = positionFlow,
            durationMs = durationMs,
            onSeekProgress = { targetMs ->
                userSeeking = true
                if (durationMs > 0L) seekProgress = (targetMs.toFloat() / durationMs).coerceIn(0f, 1f)
                onScrubbingChanged(true)
                onSeekProgress(targetMs)
            },
            onSeekFinished = { targetMs ->
                onSeekFinished(targetMs)
                userSeeking = false
                onScrubbingChanged(false)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "seek_bar"
                    testTag = "seek_bar"
                }
        )
    }
}

