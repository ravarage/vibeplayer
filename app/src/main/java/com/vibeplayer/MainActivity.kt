package com.vibeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vibeplayer.player.PlayerEngine
import java.io.File

class MainActivity : ComponentActivity() {
    private val playerEngine = PlayerEngine()
    private var surfaceAttached = false
    private var playbackStarted = false

    private val testVideoPath = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i("VP", "Storage permission result: $isGranted")
        tryStartPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissionAndProceed()

        setContent {
            val playerState by playerEngine.state.collectAsState()
            val positionMs by playerEngine.position.collectAsState()

            val posSec = positionMs / 1000.0
            val durSec = playerState.durationMs / 1000.0

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                playerEngine.togglePause()
                            }
                    ) {
                        AndroidView(
                            factory = { context ->
                                SurfaceView(context).apply {
                                    keepScreenOn = true
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            Log.i("VP", "surfaceCreated: ${holder.surface}")
                                            playerEngine.attachSurface(holder.surface)
                                            surfaceAttached = true
                                            tryStartPlayback()
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            Log.i("VP", "surfaceChanged: $width x $height")
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            Log.i("VP", "surfaceDestroyed")
                                            surfaceAttached = false
                                            playerEngine.detachSurface()
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Status overlay driven by StateFlows
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 40.dp)
                                .background(Color(0x99000000))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Pos: %.1f / %.1f | hwdec: %s | paused: %s | eof: %s\nTap screen to play/pause"
                                    .format(posSec, durSec, playerState.hwdecCurrent, playerState.paused, playerState.eof),
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }

                        // Bottom test controls
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                        ) {
                            Button(
                                onClick = {
                                    playerEngine.seekTo(305_000, exact = true)
                                    Log.i("VP", "Seeked near end (305s)")
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text("Seek Near End")
                            }

                            Button(
                                onClick = {
                                    playerEngine.seekTo(0, exact = true)
                                    Log.i("VP", "Seeked to beginning (0s)")
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text("Restart")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("VP", "READ_MEDIA_VIDEO already granted")
                tryStartPlayback()
            } else {
                Log.i("VP", "Requesting READ_MEDIA_VIDEO permission")
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("VP", "READ_EXTERNAL_STORAGE already granted")
                tryStartPlayback()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun tryStartPlayback() {
        if (!surfaceAttached || playbackStarted) {
            return
        }

        val file = File(testVideoPath)
        Log.i("VP", "File access: path=$testVideoPath exists=${file.exists()} canRead=${file.canRead()} size=${file.length()}")

        playbackStarted = true
        playerEngine.load(testVideoPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
        Log.i("VP", "MainActivity onDestroy completed")
    }
}
