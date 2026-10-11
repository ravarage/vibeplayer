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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vibeplayer.player.MpvNative
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var mpvHandle: Long = 0
    private var surfaceAttached = false
    private var playbackStarted = false

    private val testVideoPath = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"

    private val positionTextState = mutableStateOf("0.0 / 0.0")
    private val hwdecTextState = mutableStateOf("unknown")
    private val eofState = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i("VP", "Storage permission result: $isGranted")
        tryStartPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mpvHandle = MpvNative.create()
        if (mpvHandle != 0L) {
            MpvNative.setOptionString(mpvHandle, "vo", "gpu")
            MpvNative.setOptionString(mpvHandle, "gpu-context", "android")
            MpvNative.setOptionString(mpvHandle, "hwdec", "mediacodec")
            MpvNative.setOptionString(mpvHandle, "ao", "audiotrack")
            MpvNative.setOptionString(mpvHandle, "keep-open", "yes")
            val res = MpvNative.initialize(mpvHandle)
            Log.i("VP", "MpvNative.initialize returned $res")
        }

        checkPermissionAndProceed()
        startProgressMonitor()

        setContent {
            val posText by positionTextState
            val hwdecText by hwdecTextState
            val isEof by eofState

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                if (mpvHandle != 0L) {
                                    val cycleRes = MpvNative.command(mpvHandle, arrayOf("cycle", "pause"))
                                    val isPaused = MpvNative.getPropertyString(mpvHandle, "pause")
                                    Log.i("VP", "Toggled pause (res=$cycleRes), pause=$isPaused")
                                }
                            }
                    ) {
                        AndroidView(
                            factory = { context ->
                                SurfaceView(context).apply {
                                    keepScreenOn = true
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            Log.i("VP", "surfaceCreated: ${holder.surface}")
                                            if (mpvHandle != 0L) {
                                                MpvNative.attachSurface(mpvHandle, holder.surface)
                                                surfaceAttached = true
                                                tryStartPlayback()
                                            }
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
                                            if (mpvHandle != 0L) {
                                                MpvNative.detachSurface(mpvHandle)
                                            }
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Status overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 40.dp)
                                .background(Color(0x99000000))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Pos: $posText | hwdec: $hwdecText | eof: $isEof\nTap screen to play/pause",
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
                                    if (mpvHandle != 0L) {
                                        MpvNative.command(mpvHandle, arrayOf("seek", "305", "absolute"))
                                        Log.i("VP", "Seeked near end (305s)")
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text("Seek Near End")
                            }

                            Button(
                                onClick = {
                                    if (mpvHandle != 0L) {
                                        MpvNative.command(mpvHandle, arrayOf("seek", "0", "absolute"))
                                        Log.i("VP", "Seeked to beginning (0s)")
                                    }
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

    private fun startProgressMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (mpvHandle != 0L && playbackStarted) {
                    val pos = MpvNative.getPropertyString(mpvHandle, "time-pos") ?: "0"
                    val dur = MpvNative.getPropertyString(mpvHandle, "duration") ?: "0"
                    val hwdec = MpvNative.getPropertyString(mpvHandle, "hwdec-current") ?: "none"
                    val eof = MpvNative.getPropertyString(mpvHandle, "eof-reached") ?: "no"
                    val pause = MpvNative.getPropertyString(mpvHandle, "pause") ?: "no"

                    positionTextState.value = "%.1f / %.1f".format(pos.toDoubleOrNull() ?: 0.0, dur.toDoubleOrNull() ?: 0.0)
                    hwdecTextState.value = hwdec
                    eofState.value = eof == "yes"

                    Log.i("VP-Progress", "pos=$pos/$dur hwdec=$hwdec eof=$eof pause=$pause")
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
        if (!surfaceAttached || playbackStarted || mpvHandle == 0L) {
            return
        }

        val file = File(testVideoPath)
        Log.i("VP", "File access: path=$testVideoPath exists=${file.exists()} canRead=${file.canRead()} size=${file.length()}")

        playbackStarted = true
        val loadRes = MpvNative.command(mpvHandle, arrayOf("loadfile", testVideoPath))
        Log.i("VP", "loadfile returned: $loadRes for $testVideoPath")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mpvHandle != 0L) {
            MpvNative.destroy(mpvHandle)
            mpvHandle = 0
            Log.i("VP", "MpvNative destroyed")
        }
    }
}
