package com.vibeplayer.ui.player

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vibeplayer.player.PlayerEngine

@Composable
fun VideoSurface(
    playerEngine: PlayerEngine,
    modifier: Modifier = Modifier
) {
    // A new SurfaceView (and Surface) after the engine abandoned a stuck mpv handle (B-08): the old
    // Surface stays connected to that handle's video output.
    val surfaceEpoch by playerEngine.surfaceEpoch.collectAsStateWithLifecycle()
    key(surfaceEpoch) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = true
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.i("VP", "VideoSurface surfaceCreated: ${holder.surface}")
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
                        playerEngine.detachSurface(holder.surface)
                    }
                })
            }
        },
        modifier = modifier.fillMaxSize()
    )
    }
}
