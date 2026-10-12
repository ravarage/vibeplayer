package com.vibeplayer.ui.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class GestureType {
    NONE,
    TAP,
    DOUBLE_TAP_LEFT,
    DOUBLE_TAP_RIGHT,
    LEFT_VERTICAL,   // brightness
    RIGHT_VERTICAL,  // volume
    HORIZONTAL,      // swipe seek
    PINCH            // zoom
}

@Composable
fun GestureLayer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLocked: Boolean = false,
    initialBrightness: Float = 0.5f,
    onBrightnessChanged: (Float) -> Unit = {},
    volumeFlow: StateFlow<Int> = MutableStateFlow(0),
    maxVolume: Int = 20,
    triggerVolumeIndicatorFlow: StateFlow<Long> = MutableStateFlow(0L),
    onVolumeChanged: (Int) -> Unit = {},
    onSingleTap: () -> Unit = {},
    onDoubleTap: (isLeft: Boolean) -> Unit = {},
    onGestureTypeChanged: (GestureType) -> Unit = {},
    onVerticalDrag: (isLeft: Boolean, deltaPercent: Float) -> Unit = { _, _ -> },
    onHorizontalDrag: (deltaPixels: Float) -> Unit = {},
    onPinchZoom: (centroid: Offset, zoomFactor: Float, panDelta: Offset) -> Unit = { _, _, _ -> },
    onGestureEnded: (GestureType) -> Unit = {},
    content: @Composable () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    var brightnessLevel by remember(initialBrightness) { mutableFloatStateOf(initialBrightness) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var hideIndicatorJob by remember { mutableStateOf<Job?>(null) }

    val currentVolume by volumeFlow.collectAsState()
    var localVolumeFraction by remember(currentVolume) { mutableFloatStateOf(currentVolume.toFloat()) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var hideVolumeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(triggerVolumeIndicatorFlow) {
        triggerVolumeIndicatorFlow.collect { timestamp ->
            if (timestamp > 0L) {
                showVolumeIndicator = true
                hideVolumeJob?.cancel()
                hideVolumeJob = coroutineScope.launch {
                    delay(1000L)
                    showVolumeIndicator = false
                }
            }
        }
    }

    Box(
        modifier = modifier.pointerInput(enabled, isLocked) {
            if (!enabled || isLocked) return@pointerInput

            val touchSlop = viewConfiguration.touchSlop

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val startPos = down.position
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val isLeftZone = startPos.x < width / 2f

                var lockedGesture = GestureType.NONE
                var directionLocked = false
                var activePointerId = down.id

                var upConsumed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.isEmpty()) {
                        upConsumed = event.changes.any { it.isConsumed }
                        break
                    }

                    if (activePointers.size >= 2) {
                        if (!directionLocked || lockedGesture == GestureType.PINCH) {
                            if (lockedGesture != GestureType.PINCH) {
                                lockedGesture = GestureType.PINCH
                                directionLocked = true
                                Log.i("VP", "Gesture detected: PINCH")
                                onGestureTypeChanged(GestureType.PINCH)
                            }
                            activePointers.forEach { it.consume() }
                        }
                        continue
                    }

                    // Single pointer
                    val change = activePointers.firstOrNull { it.id == activePointerId } ?: activePointers.first()
                    activePointerId = change.id

                    val totalDx = change.position.x - startPos.x
                    val totalDy = change.position.y - startPos.y

                    if (!directionLocked) {
                        val absDx = abs(totalDx)
                        val absDy = abs(totalDy)
                        val distance = sqrt(absDx * absDx + absDy * absDy)

                        if (distance > touchSlop) {
                            directionLocked = true
                            if (absDx > absDy) {
                                lockedGesture = GestureType.HORIZONTAL
                                Log.i("VP", "Gesture detected: HORIZONTAL")
                            } else {
                                if (isLeftZone) {
                                    lockedGesture = GestureType.LEFT_VERTICAL
                                    Log.i("VP", "Gesture detected: LEFT_VERTICAL")
                                } else {
                                    lockedGesture = GestureType.RIGHT_VERTICAL
                                    Log.i("VP", "Gesture detected: RIGHT_VERTICAL")
                                }
                            }
                            onGestureTypeChanged(lockedGesture)
                            change.consume()
                        }
                    } else {
                        val dx = change.positionChange().x
                        val dy = change.positionChange().y
                        change.consume()
                        when (lockedGesture) {
                            GestureType.HORIZONTAL -> {
                                onHorizontalDrag(dx)
                            }
                            GestureType.LEFT_VERTICAL -> {
                                val deltaPercent = -dy / height
                                val newB = (brightnessLevel + deltaPercent).coerceIn(0.01f, 1.0f)
                                brightnessLevel = newB
                                showBrightnessIndicator = true
                                hideIndicatorJob?.cancel()
                                onBrightnessChanged(newB)
                                onVerticalDrag(true, deltaPercent)
                            }
                            GestureType.RIGHT_VERTICAL -> {
                                val deltaPercent = -dy / height
                                localVolumeFraction = (localVolumeFraction + deltaPercent * maxVolume).coerceIn(0f, maxVolume.toFloat())
                                val newVol = localVolumeFraction.roundToInt().coerceIn(0, maxVolume)
                                if (newVol != currentVolume) {
                                    onVolumeChanged(newVol)
                                }
                                showVolumeIndicator = true
                                hideVolumeJob?.cancel()
                                onVerticalDrag(false, deltaPercent)
                            }
                            else -> {}
                        }
                    }
                }

                if (!directionLocked) {
                    if (upConsumed) {
                        Log.i("VP", "Tap consumed by controls element; ignoring in GestureLayer")
                    } else {
                        // Tap detection with double-tap check
                        val now = SystemClock.uptimeMillis()
                        val doubleTapTimeout = 250L
                        val isDoubleTap = (now - lastTapTime < doubleTapTimeout) &&
                                (abs(startPos.x - lastTapPos.x) < touchSlop * 4f) &&
                                (abs(startPos.y - lastTapPos.y) < touchSlop * 4f)

                        if (isDoubleTap) {
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            lastTapTime = 0L
                            val isLeft = startPos.x < width / 2f
                            val gType = if (isLeft) GestureType.DOUBLE_TAP_LEFT else GestureType.DOUBLE_TAP_RIGHT
                            Log.i("VP", "Gesture detected: $gType")
                            onDoubleTap(isLeft)
                        } else {
                            lastTapTime = now
                            lastTapPos = startPos
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = coroutineScope.launch {
                                delay(200L)
                                Log.i("VP", "Gesture detected: TAP")
                                onSingleTap()
                                lastTapTime = 0L
                            }
                        }
                    }
                } else {
                    Log.i("VP", "Gesture ended: $lockedGesture")
                    if (lockedGesture == GestureType.LEFT_VERTICAL) {
                        hideIndicatorJob?.cancel()
                        hideIndicatorJob = coroutineScope.launch {
                            delay(1000L)
                            showBrightnessIndicator = false
                        }
                    }
                    if (lockedGesture == GestureType.RIGHT_VERTICAL) {
                        hideVolumeJob?.cancel()
                        hideVolumeJob = coroutineScope.launch {
                            delay(1000L)
                            showVolumeIndicator = false
                        }
                    }
                    onGestureEnded(lockedGesture)
                    onGestureTypeChanged(GestureType.NONE)
                }
            }
        }
    ) {
        content()

        BrightnessIndicator(
            visible = showBrightnessIndicator,
            brightness = brightnessLevel,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 64.dp)
        )

        VolumeIndicator(
            visible = showVolumeIndicator,
            volume = currentVolume,
            maxVolume = maxVolume,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 64.dp)
        )
    }
}
