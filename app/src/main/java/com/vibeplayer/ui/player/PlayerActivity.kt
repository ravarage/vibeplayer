package com.vibeplayer.ui.player

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vibeplayer.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.vibeplayer.player.EfficientPath
import com.vibeplayer.player.PlaybackConfig
import com.vibeplayer.player.PlaybackMode

class PlayerActivity : ComponentActivity() {

    private val playerEngine = PlayerEngine()
    private var playbackStarted = false
    private var currentVideoPath: String = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"
    private var currentVideoTitle: String = "ichiban.mp4"

    private fun resolveConfig(): PlaybackConfig {
        val pathArg = intent.getStringExtra("efficient_path") ?: intent.getStringExtra("path_mode")
        val path = when (pathArg?.lowercase()) {
            "path_a", "a" -> EfficientPath.PATH_A
            else -> EfficientPath.PATH_B
        }
        return PlaybackConfig(
            mode = PlaybackMode.EFFICIENT,
            efficientPath = path
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i("VP", "PlayerActivity storage permission result: $isGranted")
        tryStartPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("VP", "PlayerActivity onCreate")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Auto-orient based on video aspect ratio (P1-09)
        lifecycleScope.launch {
            playerEngine.state.collect { state ->
                val w = state.videoWidth
                val h = state.videoHeight
                if (w > 0 && h > 0) {
                    val targetOrientation = if (w >= h) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                    if (requestedOrientation != targetOrientation) {
                        Log.i("VP", "Auto-orienting: ${w}x${h} -> orientation=$targetOrientation")
                        requestedOrientation = targetOrientation
                    }
                }
            }
        }

        val config = resolveConfig()
        if (config != playerEngine.playbackConfig) {
            playerEngine.reconfigure(config)
        }

        resolveVideoPath()
        checkPermissionAndProceed()

        if (intent.getStringExtra("test") == "p1_02") {
            runP102Test()
        }

        setContent {
            val playerState by playerEngine.state.collectAsState()
            val positionMs by playerEngine.position.collectAsState()
            var showDebugOverlay by remember { mutableStateOf(false) }
            var controlsVisible by remember { mutableStateOf(true) }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        VideoSurface(
                            playerEngine = playerEngine,
                            modifier = Modifier.fillMaxSize()
                        )

                        GestureLayer(
                            modifier = Modifier.fillMaxSize(),
                            initialBrightness = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it },
                            onBrightnessChanged = { b ->
                                val lp = window.attributes
                                lp.screenBrightness = b
                                window.attributes = lp
                            },
                            onSingleTap = {
                                controlsVisible = !controlsVisible
                            }
                        )

                        PlayerControls(
                            playerEngine = playerEngine,
                            playerState = playerState,
                            positionMs = positionMs,
                            videoPath = currentVideoTitle,
                            controlsVisible = controlsVisible,
                            onBack = { finish() },
                            onToggleDebug = { showDebugOverlay = !showDebugOverlay },
                            onHideControls = { controlsVisible = false },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (showDebugOverlay) {
                            DebugOverlay(
                                playerEngine = playerEngine,
                                onClose = { showDebugOverlay = false },
                                modifier = Modifier
                                    .padding(top = 80.dp, start = 16.dp)
                                    .align(Alignment.TopStart)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val config = resolveConfig()
        val oldPath = currentVideoPath
        resolveVideoPath()
        val forceHwdec = intent.getStringExtra("force_hwdec") ?: intent.getStringExtra("hwdec")
        if (!forceHwdec.isNullOrEmpty()) {
            playerEngine.setHwdec(forceHwdec)
        }
        if (config != playerEngine.playbackConfig) {
            playerEngine.reconfigure(config)
        } else if (currentVideoPath != oldPath) {
            playerEngine.load(currentVideoPath)
        }
    }

    private fun resolveVideoPath() {
        val extraPath = intent.getStringExtra("path")
        val dataUri = intent.data
        if (!extraPath.isNullOrEmpty()) {
            currentVideoPath = extraPath
            currentVideoTitle = File(extraPath).name
        } else if (dataUri != null) {
            val scheme = dataUri.scheme
            if (scheme == "file") {
                val path = dataUri.path ?: dataUri.toString()
                currentVideoPath = path
                currentVideoTitle = File(path).name
            } else if (scheme == "content") {
                currentVideoTitle = queryDisplayName(dataUri)
                currentVideoPath = resolveContentUri(dataUri)
            } else {
                currentVideoPath = dataUri.toString()
                currentVideoTitle = dataUri.lastPathSegment ?: "Video"
            }
        }
        Log.i("VP", "Resolved video path: $currentVideoPath (title: $currentVideoTitle)")
    }

    private fun queryDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VP", "Failed to query displayName: ${e.message}")
        }
        return uri.lastPathSegment ?: "Video"
    }

    private fun resolveContentUri(uri: Uri): String {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                return "fdclose://$fd"
            }
        } catch (e: Exception) {
            Log.e("VP", "Failed to openFileDescriptor for $uri: ${e.message}", e)
        }
        return uri.toString()
    }

    private fun checkPermissionAndProceed() {
        val dataUri = intent.data
        if (dataUri != null && dataUri.scheme == "content") {
            tryStartPlayback()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                tryStartPlayback()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                tryStartPlayback()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun tryStartPlayback() {
        if (playbackStarted) return

        if (!currentVideoPath.startsWith("fdclose://")) {
            val file = File(currentVideoPath)
            Log.i("VP", "File access: path=$currentVideoPath exists=${file.exists()} canRead=${file.canRead()} size=${file.length()}")
        }

        val forceHwdec = intent.getStringExtra("force_hwdec") ?: intent.getStringExtra("hwdec")
        if (!forceHwdec.isNullOrEmpty()) {
            playerEngine.setHwdec(forceHwdec)
        }
        playbackStarted = true
        playerEngine.load(currentVideoPath)
    }

    private fun runP102Test() {
        lifecycleScope.launch(Dispatchers.Default) {
            Log.i("VP", "=== Starting P1-02 verification test in PlayerActivity ===")
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

            val testClip = currentVideoPath
            val runtime = Runtime.getRuntime()
            System.gc()
            val startMem = runtime.totalMemory() - runtime.freeMemory()

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

    override fun onStart() {
        super.onStart()
        Log.i("VP", "PlayerActivity onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.i("VP", "PlayerActivity onResume")
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i("VP", "PlayerActivity onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.i("VP", "PlayerActivity onStop")
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        Log.i("VP", "Reset window brightness override on exit")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("VP", "PlayerActivity onDestroy")
        playerEngine.release()
    }
}
