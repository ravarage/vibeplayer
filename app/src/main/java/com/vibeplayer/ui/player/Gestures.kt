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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
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
    getCurrentPositionMs: suspend () -> Long = { 0L },
    durationMs: Long = 0L,
    zoomState: StateFlow<Double> = MutableStateFlow(0.0),
    onZoomChange: (Double) -> Unit = {},
    onPanChange: (panX: Double, panY: Double) -> Unit = { _, _ -> },
    onZoomCommit: (Double) -> Unit = {},
    onPanCommit: (panX: Double, panY: Double) -> Unit = { _, _ -> },
    onResetZoom: () -> Unit = {},
    onSeekPreview: (targetMs: Long) -> Unit = {},
    onSeekCommit: (targetMs: Long) -> Unit = {},
    onSeekRelative: (deltaSeconds: Double, exact: Boolean) -> Unit = { _, _ -> },
    onSingleTap: () -> Unit = {},
    onDoubleTap: (isLeft: Boolean) -> Unit = {},
    onGestureTypeChanged: (GestureType) -> Unit = {},
    onVerticalDrag: (isLeft: Boolean, deltaPercent: Float) -> Unit = { _, _ -> },
    onHorizontalDrag: (deltaPixels: Float) -> Unit = {},
    onPinchZoom: (centroid: Offset, zoomFactor: Float, panDelta: Offset) -> Unit = { _, _, _ -> },
    onGestureEnded: (GestureType) -> Unit = {},
    content: @Composable () -> Unit = {}
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    val latestDurationMs by rememberUpdatedState(durationMs)
    val currentLocalZoom = remember(zoomState) { java.util.concurrent.atomic.AtomicReference(zoomState.value) }
    LaunchedEffect(zoomState) { zoomState.collect { currentLocalZoom.set(it) } }
    val currentLocalPanX = remember { java.util.concurrent.atomic.AtomicReference(0.0) }
    val currentLocalPanY = remember { java.util.concurrent.atomic.AtomicReference(0.0) }
    var initialPinchDistance by remember { mutableFloatStateOf(0f) }
    var initialPinchCenter by remember { mutableStateOf(Offset.Zero) }
    var baseZoom by remember { mutableFloatStateOf(0f) }
    var basePanX by remember { mutableFloatStateOf(0f) }
    var basePanY by remember { mutableFloatStateOf(0f) }

    var brightnessLevel by remember { mutableFloatStateOf(initialBrightness) }
    LaunchedEffect(initialBrightness) { brightnessLevel = initialBrightness }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var hideIndicatorJob by remember { mutableStateOf<Job?>(null) }

    val currentVolume by volumeFlow.collectAsState()
    val latestVolume by rememberUpdatedState(currentVolume)
    var localVolumeFraction by remember { mutableFloatStateOf(currentVolume.toFloat()) }
    LaunchedEffect(currentVolume) { localVolumeFraction = currentVolume.toFloat() }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var hideVolumeJob by remember { mutableStateOf<Job?>(null) }

    var swipeSeekStartPositionMs by remember { mutableStateOf(0L) }
    var swipeSeekDeltaSeconds by remember { mutableStateOf(0L) }
    var swipeSeekTargetMs by remember { mutableStateOf(0L) }
    var totalSwipeDx by remember { mutableFloatStateOf(0f) }
    var lastPreviewSeekTime by remember { mutableStateOf(0L) }
    var showSwipeSeekIndicator by remember { mutableStateOf(false) }
    var hideSwipeSeekJob by remember { mutableStateOf<Job?>(null) }

    var doubleTapStreakActive by remember { mutableStateOf(false) }
    var doubleTapIsLeft by remember { mutableStateOf(false) }
    var doubleTapAccumulatedSec by remember { mutableIntStateOf(0) }
    var doubleTapBasePosMs by remember { mutableStateOf(0L) }
    var doubleTapRippleTrigger by remember { mutableLongStateOf(0L) }
    var doubleTapHideJob by remember { mutableStateOf<Job?>(null) }

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
                        val p1 = activePointers[0]
                        val p2 = activePointers[1]
                        val pDiffX = p1.position.x - p2.position.x
                        val pDiffY = p1.position.y - p2.position.y
                        val currentDistance = sqrt(pDiffX * pDiffX + pDiffY * pDiffY)
                        val currentCenter = Offset((p1.position.x + p2.position.x) / 2f, (p1.position.y + p2.position.y) / 2f)

                        if (!directionLocked || lockedGesture == GestureType.PINCH) {
                            if (lockedGesture != GestureType.PINCH) {
                                lockedGesture = GestureType.PINCH
                                directionLocked = true
                                Log.i("VP", "Gesture detected: PINCH")
                                onGestureTypeChanged(GestureType.PINCH)
                                initialPinchDistance = currentDistance
                                initialPinchCenter = currentCenter
                                baseZoom = currentLocalZoom.get().toFloat()
                                basePanX = currentLocalPanX.get().toFloat()
                                basePanY = currentLocalPanY.get().toFloat()
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapTime = 0L
                                if (doubleTapStreakActive) {
                                    doubleTapStreakActive = false
                                    doubleTapAccumulatedSec = 0
                                    doubleTapHideJob?.cancel()
                                }
                            } else {
                                if (initialPinchDistance > 10f && currentDistance > 10f) {
                                    val ratio = (currentDistance / initialPinchDistance).coerceAtLeast(0.01f)
                                    val zoomDelta = kotlin.math.ln(ratio.toDouble()) / kotlin.math.ln(2.0)
                                    val newZoom = (baseZoom.toDouble() + zoomDelta).coerceIn(0.0, 3.0)
                                    currentLocalZoom.set(newZoom)
                                    onZoomChange(newZoom)

                                    val panDx = (currentCenter.x - initialPinchCenter.x) / width
                                    val panDy = (currentCenter.y - initialPinchCenter.y) / height
                                    val newPanX = (basePanX.toDouble() + panDx.toDouble()).coerceIn(-1.5, 1.5)
                                    val newPanY = (basePanY.toDouble() + panDy.toDouble()).coerceIn(-1.5, 1.5)
                                    currentLocalPanX.set(newPanX)
                                    currentLocalPanY.set(newPanY)
                                    onPanChange(newPanX, newPanY)
                                }
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
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            lastTapTime = 0L
                            if (doubleTapStreakActive) {
                                doubleTapStreakActive = false
                                doubleTapAccumulatedSec = 0
                                doubleTapHideJob?.cancel()
                            }
                            if (absDx > absDy) {
                                lockedGesture = GestureType.HORIZONTAL
                                Log.i("VP", "Gesture detected: HORIZONTAL")
                                totalSwipeDx = totalDx
                                showSwipeSeekIndicator = true
                                hideSwipeSeekJob?.cancel()
                                coroutineScope.launch {
                                    val base = getCurrentPositionMs()
                                    swipeSeekStartPositionMs = base
                                    val deltaMs = (totalSwipeDx * 40f).toLong()
                                    val targetMs = (base + deltaMs).coerceIn(0L, latestDurationMs)
                                    swipeSeekTargetMs = targetMs
                                    swipeSeekDeltaSeconds = (targetMs - base) / 1000L
                                }
                                HapticFeedbackHelper.triggerTick(view, "swipe-seek step")
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
                                totalSwipeDx += dx
                                val deltaMs = (totalSwipeDx * 40f).toLong()
                                val targetMs = (swipeSeekStartPositionMs + deltaMs).coerceIn(0L, latestDurationMs)
                                swipeSeekTargetMs = targetMs
                                val newDeltaSec = (targetMs - swipeSeekStartPositionMs) / 1000L
                                if (newDeltaSec != swipeSeekDeltaSeconds) {
                                    swipeSeekDeltaSeconds = newDeltaSec
                                    HapticFeedbackHelper.triggerTick(view, "swipe-seek step")
                                }
                                showSwipeSeekIndicator = true
                                hideSwipeSeekJob?.cancel()
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
                                if (newVol != latestVolume) {
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
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                        lastTapTime = 0L
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val isLeft = startPos.x < width / 2f
                        if (doubleTapStreakActive && isLeft == doubleTapIsLeft) {
                            // Part of an ongoing streak on same side: accumulate!
                            doubleTapHideJob?.cancel()
                            doubleTapAccumulatedSec += 10
                            doubleTapRippleTrigger = now
                            val deltaSec = if (doubleTapIsLeft) -10.0 else 10.0
                            val targetMs = (doubleTapBasePosMs + (if (doubleTapIsLeft) -doubleTapAccumulatedSec else doubleTapAccumulatedSec) * 1000L).coerceIn(0L, latestDurationMs)
                            Log.i("VP", "Double-tap streak accumulate: isLeft=$doubleTapIsLeft, sec=$doubleTapAccumulatedSec, deltaSec=$deltaSec")
                            onSeekRelative(deltaSec, true)
                            onDoubleTap(doubleTapIsLeft)
                            HapticFeedbackHelper.triggerTick(view, "skip")
                            doubleTapHideJob = coroutineScope.launch {
                                delay(700L)
                                doubleTapStreakActive = false
                                doubleTapAccumulatedSec = 0
                            }
                        } else {
                            val doubleTapTimeout = 250L
                            val isDoubleTap = (now - lastTapTime < doubleTapTimeout) && (isLeft == (lastTapPos.x < width / 2f))

                            if (isDoubleTap) {
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapTime = 0L
                                doubleTapHideJob?.cancel()
                                doubleTapStreakActive = true
                                doubleTapIsLeft = isLeft
                                doubleTapAccumulatedSec = 10
                                doubleTapRippleTrigger = now
                                val deltaSec = if (isLeft) -10.0 else 10.0
                                val gType = if (isLeft) GestureType.DOUBLE_TAP_LEFT else GestureType.DOUBLE_TAP_RIGHT
                                Log.i("VP", "Gesture detected: $gType, deltaSec=$deltaSec")
                                onSeekRelative(deltaSec, true)
                                onDoubleTap(isLeft)
                                HapticFeedbackHelper.triggerTick(view, "skip")
                                doubleTapHideJob = coroutineScope.launch {
                                    doubleTapBasePosMs = getCurrentPositionMs()
                                    delay(700L)
                                    doubleTapStreakActive = false
                                    doubleTapAccumulatedSec = 0
                                }
                            } else {
                                if (doubleTapStreakActive) {
                                    doubleTapStreakActive = false
                                    doubleTapAccumulatedSec = 0
                                    doubleTapHideJob?.cancel()
                                }
                                lastTapTime = now
                                lastTapPos = startPos
                                pendingSingleTapJob?.cancel()
                                Log.i("VP", "Gesture detected: TAP")
                                onSingleTap()
                                pendingSingleTapJob = coroutineScope.launch {
                                    delay(doubleTapTimeout)
                                    if (lastTapTime == now) lastTapTime = 0L
                                }
                            }
                        }
                    }
                } else {
                    Log.i("VP", "Gesture ended: $lockedGesture")
                    if (lockedGesture == GestureType.HORIZONTAL) {
                        val deltaSec = (swipeSeekTargetMs - swipeSeekStartPositionMs) / 1000.0
                        Log.i("VP", "Swipe seek commit: deltaSec=$deltaSec (start=$swipeSeekStartPositionMs, target=$swipeSeekTargetMs)")
                        onSeekRelative(deltaSec, true)
                        hideSwipeSeekJob?.cancel()
                        hideSwipeSeekJob = coroutineScope.launch {
                            delay(800L)
                            showSwipeSeekIndicator = false
                        }
                    }
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
                    if (lockedGesture == GestureType.PINCH) {
                        if (currentLocalZoom.get() < 0.05) {
                            currentLocalZoom.set(0.0)
                            currentLocalPanX.set(0.0)
                            currentLocalPanY.set(0.0)
                            onResetZoom()
                        } else {
                            onZoomCommit(currentLocalZoom.get())
                            onPanCommit(currentLocalPanX.get(), currentLocalPanY.get())
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

        SwipeSeekIndicator(
            visible = showSwipeSeekIndicator,
            deltaSeconds = swipeSeekDeltaSeconds,
            targetPositionMs = swipeSeekTargetMs,
            durationMs = durationMs,
            modifier = Modifier.align(Alignment.Center)
        )

        DoubleTapSkipIndicator(
            visible = doubleTapStreakActive,
            isLeft = doubleTapIsLeft,
            seconds = doubleTapAccumulatedSec,
            rippleTrigger = doubleTapRippleTrigger,
            modifier = Modifier.align(if (doubleTapIsLeft) Alignment.CenterStart else Alignment.CenterEnd)
        )

        ResetZoomButton(
            zoomState = zoomState,
            onReset = {
                currentLocalZoom.set(0.0)
                currentLocalPanX.set(0.0)
                currentLocalPanY.set(0.0)
                onResetZoom()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 24.dp)
        )
    }
}
