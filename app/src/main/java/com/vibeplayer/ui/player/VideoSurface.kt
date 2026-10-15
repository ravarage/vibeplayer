package com.vibeplayer.ui.player

import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.SurfaceFrameRateHelper

/**
 * Manages display refresh rate matching for a Surface.
 *
 * Calls [Surface.setFrameRate] once per file (not per frame) when container fps is known,
 * and clears it (0f) on pause, stop, surface destroy, or when leaving the player.
 */
class VideoSurfaceFrameRateController {
    var matchDisplayRefreshRate: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                applyDecision("matchDisplayRefreshRateChanged")
            }
        }

    var containerFps: Double? = null
        set(value) {
            if (field != value) {
                field = value
                applyDecision("containerFpsChanged")
            }
        }

    var isPaused: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                applyDecision(if (value) "paused" else "resumed")
            }
        }

    var fileIdentity: String? = null
        set(value) {
            if (field != value) {
                field = value
                // Reset voted fps for new file so setFrameRate is called once per file
                lastVotedFps = null
                applyDecision("fileChanged")
            }
        }

    private var activeSurface: Surface? = null
    private var isSurfaceAlive: Boolean = false
    private var isPlayerActive: Boolean = true
    private var lastVotedFps: Float? = null

    fun onSurfaceCreated(surface: Surface) {
        activeSurface = surface
        isSurfaceAlive = true
        lastVotedFps = null
        applyDecision("surfaceCreated")
    }

    fun onSurfaceDestroyed(surface: Surface) {
        voteFrameRate(surface, SurfaceFrameRateHelper.CLEAR_FPS, "surfaceDestroyed")
        isSurfaceAlive = false
        activeSurface = null
        lastVotedFps = SurfaceFrameRateHelper.CLEAR_FPS
    }

    fun onPlayerStop() {
        isPlayerActive = false
        applyDecision("playerStop")
    }

    fun onPlayerStart() {
        isPlayerActive = true
        applyDecision("playerStart")
    }

    fun onLeavingPlayer() {
        isPlayerActive = false
        val s = activeSurface
        if (s != null) {
            voteFrameRate(s, SurfaceFrameRateHelper.CLEAR_FPS, "leavingPlayer")
        }
        lastVotedFps = SurfaceFrameRateHelper.CLEAR_FPS
    }

    fun applyDecision(reason: String) {
        val surface = activeSurface ?: return
        val targetFps = SurfaceFrameRateHelper.decideFrameRate(
            matchDisplayRefreshRate = matchDisplayRefreshRate,
            containerFps = containerFps,
            isPaused = isPaused,
            isSurfaceAlive = isSurfaceAlive,
            isPlayerActive = isPlayerActive
        )
        if (SurfaceFrameRateHelper.shouldUpdateFrameRate(lastVotedFps, targetFps)) {
            voteFrameRate(surface, targetFps, reason)
        }
    }

    private fun voteFrameRate(surface: Surface, fps: Float, reason: String) {
        if (!surface.isValid) return
        try {
            surface.setFrameRate(
                fps,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
            lastVotedFps = fps
            Log.i("VP", "Surface.setFrameRate: $fps fps (compatibility=FIXED_SOURCE, strategy=SEAMLESS, reason=$reason)")
        } catch (e: Throwable) {
            Log.w("VP", "Surface.setFrameRate failed: ${e.message}")
        }
    }
}

@Composable
fun VideoSurface(
    playerEngine: PlayerEngine,
    matchDisplayRefreshRate: Boolean = true,
    currentPath: String? = null,
    modifier: Modifier = Modifier
) {
    // A new SurfaceView (and Surface) after the engine abandoned a stuck mpv handle (B-08): the old
    // Surface stays connected to that handle's video output.
    val surfaceEpoch by playerEngine.surfaceEpoch.collectAsStateWithLifecycle()
    val playerState by playerEngine.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember(surfaceEpoch) { VideoSurfaceFrameRateController() }
    controller.matchDisplayRefreshRate = matchDisplayRefreshRate
    controller.containerFps = playerState.containerFps
    controller.isPaused = playerState.paused || playerState.eof
    controller.fileIdentity = currentPath

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onPlayerStop()
                Lifecycle.Event.ON_START -> controller.onPlayerStart()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.onLeavingPlayer()
        }
    }

    key(surfaceEpoch) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.i("VP", "VideoSurface surfaceCreated: ${holder.surface}")
                            controller.onSurfaceCreated(holder.surface)
                            playerEngine.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            Log.i("VP", "VideoSurface surfaceChanged: ${width}x${height}")
                            playerEngine.setSurfaceSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.i("VP", "VideoSurface surfaceDestroyed")
                            controller.onSurfaceDestroyed(holder.surface)
                            playerEngine.detachSurface(holder.surface)
                        }
                    })
                }
            },
            update = {
                controller.applyDecision("stateUpdate")
            },
            modifier = modifier.fillMaxSize()
        )
    }
}
