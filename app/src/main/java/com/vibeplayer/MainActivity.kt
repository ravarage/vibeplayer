package com.vibeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
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
import androidx.lifecycle.lifecycleScope
import com.vibeplayer.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )

        checkPermissionAndProceed()

        if (intent.getStringExtra("test") == "p1_02") {
            runP102Test()
        }

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
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("Seek End")
                            }

                            Button(
                                onClick = {
                                    playerEngine.seekTo(0, exact = true)
                                    Log.i("VP", "Seeked to beginning (0s)")
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("Restart")
                            }

                            Button(
                                onClick = {
                                    runP102Test()
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("100x Test")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun runP102Test() {
        lifecycleScope.launch(Dispatchers.Default) {
            Log.i("VP", "=== Starting P1-02 verification test ===")
            var threadAssertPassed = false
            try {
                withContext(Dispatchers.Main) {
                    playerEngine.assertCommandThread()
                }
            } catch (e: IllegalStateException) {
                Log.i("VP", "Main thread assertion check PASSED: caught expected exception: ${e.message}")
                threadAssertPassed = true
            }

            if (!threadAssertPassed) {
                Log.e("VP", "FAILED: Calling assertCommandThread on main thread did not throw!")
                return@launch
            }

            val testClip = testVideoPath
            val runtime = Runtime.getRuntime()
            System.gc()
            val startMem = runtime.totalMemory() - runtime.freeMemory()
            Log.i("VP", "Starting 100x load/seek/release loop. Initial memory: ${startMem / 1024} KB")

            var allSuccess = true
            for (i in 1..100) {
                try {
                    val testEngine = PlayerEngine()
                    testEngine.load(testClip)
                    testEngine.seekTo(5000, exact = false)
                    testEngine.seekTo(15000, exact = true)
                    testEngine.release(sync = true)
                    if (i % 20 == 0 || i == 100) {
                        val currentMem = runtime.totalMemory() - runtime.freeMemory()
                        Log.i("VP", "P1-02 loop progress: $i/100 completed. Used memory: ${currentMem / 1024} KB")
                    }
                } catch (e: Throwable) {
                    Log.e("VP", "P1-02 loop iteration $i failed: ${e.message}", e)
                    allSuccess = false
                    break
                }
            }

            System.gc()
            val endMem = runtime.totalMemory() - runtime.freeMemory()
            Log.i("VP", "=== P1-02 test completed. success=$allSuccess, memDelta=${(endMem - startMem) / 1024} KB ===")
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
