package com.vibeplayer.ui.player

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import androidx.compose.runtime.LaunchedEffect
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlayerActivity : ComponentActivity() {

    val viewModel: PlayerViewModel by viewModels()
    val playerEngine: PlayerEngine get() = viewModel.playerEngine
    private var playbackStarted = false
    private var currentVideoPath: String = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"
    private var currentVideoTitle: String = "ichiban.mp4"
    private var currentIdentityKey: String? = null
    private var currentFolderKey: String? = null

    private val _isInPipFlow = MutableStateFlow(false)

    private lateinit var audioManager: AudioManager
    private val _volumeFlow = MutableStateFlow(0)
    private val _triggerVolumeIndicatorFlow = MutableStateFlow(0L)
    private val volumeFlow = _volumeFlow.asStateFlow()
    private val triggerVolumeIndicatorFlow = _triggerVolumeIndicatorFlow.asStateFlow()
    private val maxMusicVolume by lazy { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PipHelper.ACTION_REWIND -> {
                    Log.i("VP", "PiP Action: Rewind 10s")
                    playerEngine.seekRelative(-10.0, exact = true)
                }
                PipHelper.ACTION_PLAY_PAUSE -> {
                    Log.i("VP", "PiP Action: Play/Pause")
                    playerEngine.togglePause()
                }
                PipHelper.ACTION_FORWARD -> {
                    Log.i("VP", "PiP Action: Forward 10s")
                    playerEngine.seekRelative(10.0, exact = true)
                }
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    _volumeFlow.value = vol
                    _triggerVolumeIndicatorFlow.value = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val next = (current + 1).coerceAtMost(max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                _volumeFlow.value = next
                _triggerVolumeIndicatorFlow.value = System.currentTimeMillis()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val next = (current - 1).coerceAtLeast(0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                _volumeFlow.value = next
                _triggerVolumeIndicatorFlow.value = System.currentTimeMillis()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun resolveConfig(appSettings: com.vibeplayer.data.prefs.AppSettings? = null): PlaybackConfig {
        val modeArg = intent.getStringExtra("mode") ?: intent.getStringExtra("playback_mode")
        val mode = when (modeArg?.lowercase()) {
            "quality" -> PlaybackMode.QUALITY
            "efficient" -> PlaybackMode.EFFICIENT
            else -> if (appSettings?.playbackMode == com.vibeplayer.data.prefs.PlaybackModeOption.QUALITY) {
                PlaybackMode.QUALITY
            } else {
                PlaybackMode.EFFICIENT
            }
        }

        val rendererArg = intent.getStringExtra("renderer")
        val requestedRenderer = when (rendererArg?.lowercase()) {
            "vulkan" -> com.vibeplayer.player.Renderer.VULKAN
            "gles", "opengl" -> com.vibeplayer.player.Renderer.GLES
            else -> if (appSettings?.renderer == com.vibeplayer.data.prefs.RendererOption.VULKAN) {
                com.vibeplayer.player.Renderer.VULKAN
            } else {
                com.vibeplayer.player.Renderer.GLES
            }
        }

        val resolvedRenderer = com.vibeplayer.player.RendererResolver.resolveRenderer(
            requestedRenderer = requestedRenderer,
            mode = mode,
            gpuApiChoices = playerEngine.availableGpuApis.ifEmpty { com.vibeplayer.player.PlayerEngine.getGpuApiChoices() },
            sessionVulkanFailed = com.vibeplayer.player.PlayerEngine.sessionVulkanFailed
        )

        val pathArg = intent.getStringExtra("efficient_path") ?: intent.getStringExtra("path_mode")
        val path = when (pathArg?.lowercase()) {
            "path_a", "a" -> EfficientPath.PATH_A
            else -> EfficientPath.PATH_B
        }
        // Debug-only test hooks (B-08 comparison): renderer probe and mpv log detail.
        val debugMsgLevel = if (com.vibeplayer.BuildConfig.DEBUG) {
            intent.getStringExtra("mpv_msg_level")?.takeIf { it.isNotBlank() }
        } else null
        val shaderCache = File(cacheDir, "gpu-shader-cache").apply { mkdirs() }.absolutePath
        PlaybackConfig.defaultShaderCacheDir = shaderCache

        val fastProfile = intent.getBooleanExtra("fast_profile", false) ||
            intent.getBooleanExtra("profile_fast", false) ||
            intent.getStringExtra("profile")?.lowercase() == "fast"
        val decoderExtra = intent.getStringExtra("decoder")?.lowercase()
        val hwdecOverride = when (decoderExtra) {
            "software", "sw", "no" -> "no"
            "auto" -> null
            else -> intent.getStringExtra("force_hwdec") ?: intent.getStringExtra("hwdec")
        }

        if (com.vibeplayer.BuildConfig.DEBUG) {
            val probeVo = intent.getStringExtra("probe_vo")
            val probeRenderer = intent.getStringExtra("probe_renderer")
            if (probeVo in setOf("gpu", "gpu-next") && probeRenderer in setOf("vulkan", "opengl")) {
                return PlaybackConfig(
                    mode = PlaybackMode.QUALITY,
                    renderer = if (probeRenderer == "vulkan") com.vibeplayer.player.Renderer.VULKAN
                    else com.vibeplayer.player.Renderer.GLES,
                    voOverride = probeVo,
                    debugMsgLevel = debugMsgLevel,
                    shaderCacheDir = shaderCache,
                    fastProfile = fastProfile,
                    hwdecOverride = hwdecOverride
                )
            }
        }
        return PlaybackConfig(
            mode = mode,
            efficientPath = path,
            renderer = resolvedRenderer,
            debugMsgLevel = debugMsgLevel,
            shaderCacheDir = shaderCache,
            fastProfile = fastProfile,
            hwdecOverride = hwdecOverride
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

        if (intent.getBooleanExtra("benchmark", false)) {
            viewModel.isBenchmarkRun = true
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        _volumeFlow.value = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

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
                    if (!isInPictureInPictureMode && !_isInPipFlow.value && requestedOrientation != targetOrientation) {
                        Log.i("VP", "Auto-orienting: ${w}x${h} -> orientation=$targetOrientation")
                        requestedOrientation = targetOrientation
                    }
                }
            }
        }

        // Maintain up-to-date PiP params as playback/file state changes
        lifecycleScope.launch {
            playerEngine.state
                .map { PipState(it.videoWidth, it.videoHeight, it.paused, it.durationMs > 0L) }
                .distinctUntilChanged()
                .collect { state -> updatePipParams(state) }
        }

        // Listen for engine on-screen messages (e.g. Vulkan fallback)
        lifecycleScope.launch {
            playerEngine.onScreenMessage.collect { msg ->
                android.widget.Toast.makeText(this@PlayerActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Auto-advance and the skip buttons change the ViewModel path without a new Intent.
        // Keep Activity bookkeeping aligned so a later onNewIntent compares against the file
        // that is actually playing, and retain provider display names for content URIs.
        lifecycleScope.launch {
            viewModel.currentPath.collect { path ->
                if (path != null) {
                    currentVideoPath = path
                    if (path.startsWith("/")) currentVideoTitle = File(path).name
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
        if (intent.getStringExtra("test") == "p3_06") {
            runP306Test()
        }

        setContent {
            val playerState by playerEngine.state.collectAsState()
            val autoPath by viewModel.currentPath.collectAsState()
            val activePath = autoPath
            val shownTitle = when {
                activePath == null -> currentVideoTitle
                activePath.startsWith("/") -> File(activePath).name
                else -> currentVideoTitle
            }
            val neighbours by viewModel.neighbours.collectAsState()
            val zoomState = playerEngine.zoomState
            val isInPip by _isInPipFlow.collectAsState()
            var showDebugOverlay by remember { mutableStateOf(false) }
            var controlsVisible by remember { mutableStateOf(true) }
            var isLocked by remember { mutableStateOf(false) }
            var aspectIndicatorText by remember { mutableStateOf("") }
            var showAspectIndicator by remember { mutableStateOf(false) }
            var aspectIndicatorTrigger by remember { mutableStateOf(0L) }

            LaunchedEffect(aspectIndicatorTrigger) {
                if (aspectIndicatorTrigger > 0L) {
                    showAspectIndicator = true
                    kotlinx.coroutines.delay(1200)
                    showAspectIndicator = false
                }
            }

            BackHandler(enabled = isLocked && !isInPip) {
                Log.i("VP", "Back press ignored: screen is locked")
            }

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

                        if (!isInPip) {
                            GestureLayer(
                                modifier = Modifier.fillMaxSize(),
                                isLocked = isLocked,
                                initialBrightness = initialBrightness(),
                                onBrightnessChanged = { b ->
                                    val lp = window.attributes
                                    lp.screenBrightness = b
                                    window.attributes = lp
                                },
                                volumeFlow = volumeFlow,
                                maxVolume = maxMusicVolume,
                                triggerVolumeIndicatorFlow = triggerVolumeIndicatorFlow,
                                onVolumeChanged = { newVol ->
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    _volumeFlow.value = newVol
                                },
                                getCurrentPositionMs = { playerEngine.currentPositionMs() },
                                durationMs = playerState.durationMs,
                                zoomState = zoomState,
                                onZoomChange = { z -> playerEngine.setVideoZoom(z, commit = false) },
                                onPanChange = { px, py -> playerEngine.setVideoPan(px, py, commit = false) },
                                onZoomCommit = { z -> playerEngine.setVideoZoom(z, commit = true) },
                                onPanCommit = { px, py -> playerEngine.setVideoPan(px, py, commit = true) },
                                onResetZoom = { playerEngine.resetZoomAndPan() },
                                onSeekRelative = { deltaSec, exact ->
                                    playerEngine.seekRelative(deltaSec, exact)
                                },
                                onSingleTap = {
                                    controlsVisible = !controlsVisible
                                },
                                onDoubleTap = { isLeft ->
                                    controlsVisible = false
                                }
                            )

                            PlayerControls(
                                playerEngine = playerEngine,
                                playerState = playerState,
                                videoPath = shownTitle,
                                controlsVisible = controlsVisible,
                                isLocked = isLocked,
                                onToggleLock = {
                                    isLocked = !isLocked
                                    Log.i("VP", "Lock toggled: isLocked=$isLocked")
                                    if (isLocked) {
                                        controlsVisible = false
                                    }
                                },
                                onBack = { finish() },
                                onToggleDebug = { showDebugOverlay = !showDebugOverlay },
                                onHideControls = { controlsVisible = false },
                                onEnterPip = { enterPip() },
                                canSkipPrevious = neighbours.previous,
                                canSkipNext = neighbours.next,
                                onSkipPrevious = { viewModel.skipToNeighbour(forward = false) },
                                onSkipNext = { viewModel.skipToNeighbour(forward = true) },
                                onSelectAudioTrack = { viewModel.setAudioTrack(it) },
                                onSelectSubTrack = { viewModel.setSubtitleTrack(it) },
                                onSelectSpeed = { viewModel.setSpeed(it) },
                                onPersistSpeed = { viewModel.persistSpeed(it) },
                                onToggleMode = { viewModel.togglePlaybackMode() },
                                onCycleAspect = {
                                    val modes = listOf("fit", "crop", "stretch", "16:9", "4:3")
                                    val current = playerState.aspect ?: "fit"
                                    val currentIndex = modes.indexOf(current).let { if (it >= 0) it else 0 }
                                    val nextMode = modes[(currentIndex + 1) % modes.size]
                                    viewModel.setAspect(nextMode)
                                    aspectIndicatorText = nextMode
                                    aspectIndicatorTrigger = System.currentTimeMillis()
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            AspectIndicator(
                                visible = showAspectIndicator,
                                aspect = aspectIndicatorText,
                                modifier = Modifier.align(Alignment.Center)
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
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initialBrightness(): Float = window.attributes.screenBrightness.let {
        if (it < 0f) 0.5f else it
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val config = resolveConfig()
        val oldPath = viewModel.currentPath.value ?: currentVideoPath
        resolveVideoPath()
        val forceHwdec = intent.getStringExtra("force_hwdec") ?: intent.getStringExtra("hwdec")
        if (!forceHwdec.isNullOrEmpty()) {
            playerEngine.setHwdec(forceHwdec)
        }
        if (config != playerEngine.playbackConfig) {
            playerEngine.reconfigure(config)
        } else if (currentVideoPath != oldPath) {
            openResolvedVideo()
        }
    }

    private fun resolveVideoPath() {
        currentIdentityKey = null
        currentFolderKey = null
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
                currentIdentityKey = dataUri.toString()
                currentFolderKey = dataUri.toString().substringBeforeLast('/', "")
            } else {
                currentVideoPath = dataUri.toString()
                currentVideoTitle = dataUri.lastPathSegment ?: "Video"
            }
        }
        Log.i("VP", "Resolved video path: $currentVideoPath (title: $currentVideoTitle)")
    }

    private fun openResolvedVideo() {
        lifecycleScope.launch {
            val appSettings = viewModel.appSettingsRepository.appSettingsFlow.first()
            val config = resolveConfig(appSettings)
            val shaderArg = intent.getStringExtra("shader") ?: intent.getStringExtra("shader_preset")
            val preset = when (shaderArg?.lowercase()) {
                "anime4k_a", "a" -> com.vibeplayer.player.ShaderPreset.ANIME4K_A
                "anime4k_b", "b" -> com.vibeplayer.player.ShaderPreset.ANIME4K_B
                "anime4k_c", "c" -> com.vibeplayer.player.ShaderPreset.ANIME4K_C
                "snapdragon_gsr", "gsr" -> com.vibeplayer.player.ShaderPreset.SNAPDRAGON_GSR
                "none" -> com.vibeplayer.player.ShaderPreset.NONE
                else -> null
            }
            viewModel.openVideo(
                path = currentVideoPath,
                identityKey = currentIdentityKey,
                folderKey = currentFolderKey,
                playbackConfig = config,
                shaderPreset = preset
            )
        }
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
        if (com.vibeplayer.BuildConfig.DEBUG && intent.getBooleanExtra("debug_stall", false)) {
            playerEngine.debugSimulateStallOnce = true
        }
        playbackStarted = true
        openResolvedVideo()
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

    fun enterPip() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val state = playerEngine.state.value
        val params = PipHelper.buildPipParams(
            context = this,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            windowWidth = bounds.width(),
            windowHeight = bounds.height(),
            isPaused = state.paused,
            hasDuration = state.durationMs > 0L
        )
        try {
            val success = enterPictureInPictureMode(params)
            Log.i("VP", "enterPictureInPictureMode: success=$success")
        } catch (e: Exception) {
            Log.e("VP", "Failed to enter Picture-in-Picture: ${e.message}", e)
        }
    }

    private fun updatePipParams(state: PipState) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = PipHelper.buildPipParams(
            context = this,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            windowWidth = bounds.width(),
            windowHeight = bounds.height(),
            isPaused = state.paused,
            hasDuration = state.hasDuration
        )
        try {
            setPictureInPictureParams(params)
        } catch (e: Exception) {
            Log.w("VP", "Failed to setPictureInPictureParams: ${e.message}")
        }
    }

    private data class PipState(
        val videoWidth: Int,
        val videoHeight: Int,
        val paused: Boolean,
        val hasDuration: Boolean
    )

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!playerEngine.state.value.paused && playerEngine.state.value.durationMs > 0L) {
            Log.i("VP", "onUserLeaveHint: entering PiP")
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.i("VP", "onPictureInPictureModeChanged: isInPictureInPictureMode=$isInPictureInPictureMode")
        _isInPipFlow.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            hideSystemBars()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i("VP", "PlayerActivity onStart")
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(this, volumeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.w("VP", "Failed to register volume receiver: ${e.message}")
        }
        try {
            val pipFilter = IntentFilter().apply {
                addAction(PipHelper.ACTION_REWIND)
                addAction(PipHelper.ACTION_PLAY_PAUSE)
                addAction(PipHelper.ACTION_FORWARD)
            }
            ContextCompat.registerReceiver(this, pipReceiver, pipFilter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.w("VP", "Failed to register pip receiver: ${e.message}")
        }
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
        Log.i("VP", "PlayerActivity onPause (isInPip=$isInPictureInPictureMode)")
        viewModel.saveCurrentPosition()
    }

    override fun onStop() {
        super.onStop()
        Log.i("VP", "PlayerActivity onStop (isInPip=$isInPictureInPictureMode, wasInPip=${_isInPipFlow.value})")
        if (isInPictureInPictureMode || _isInPipFlow.value) {
            Log.i("VP", "PiP dismissed/closed onStop -> stopping playback")
            playerEngine.pause()
            _isInPipFlow.value = false
        }
        viewModel.saveCurrentPosition()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        Log.i("VP", "Reset window brightness override on exit")
    }

    private fun runP306Test() {
        lifecycleScope.launch(Dispatchers.Main) {
            delay(1500)
            Log.i("VP", "=== Starting P3-06 Multi-touch Pinch Zoom & Pan Test ===")

            val downTime = SystemClock.uptimeMillis()
            val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val properties = arrayOf(pp0, pp1)

            val pc0 = MotionEvent.PointerCoords().apply { x = 1000f; y = 500f }
            val pc1 = MotionEvent.PointerCoords().apply { x = 1400f; y = 500f }
            val coords = arrayOf(pc0, pc1)

            // Pointer 0 DOWN
            var eventTime = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, arrayOf(pp0), arrayOf(pc0), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            window.decorView.dispatchTouchEvent(event)
            event.recycle()

            delay(30)
            // Pointer 1 POINTER_DOWN (pointer index 1)
            eventTime = SystemClock.uptimeMillis()
            val actionPointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            event = MotionEvent.obtain(downTime, eventTime, actionPointerDown, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            window.decorView.dispatchTouchEvent(event)
            event.recycle()

            // Move apart: pinch zoom in
            for (step in 1..10) {
                delay(30)
                eventTime = SystemClock.uptimeMillis()
                pc0.x = 1000f - step * 30f
                pc1.x = 1400f + step * 30f
                event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                window.decorView.dispatchTouchEvent(event)
                event.recycle()
            }

            // Two-finger pan
            for (step in 1..5) {
                delay(30)
                eventTime = SystemClock.uptimeMillis()
                pc0.x += 20f
                pc0.y += 15f
                pc1.x += 20f
                pc1.y += 15f
                event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                window.decorView.dispatchTouchEvent(event)
                event.recycle()
            }

            // Pointer 1 POINTER_UP (pointer index 1)
            delay(30)
            eventTime = SystemClock.uptimeMillis()
            val actionPointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            event = MotionEvent.obtain(downTime, eventTime, actionPointerUp, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            window.decorView.dispatchTouchEvent(event)
            event.recycle()

            // Pointer 0 UP
            delay(30)
            eventTime = SystemClock.uptimeMillis()
            event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, 1, arrayOf(pp0), arrayOf(pc0), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            window.decorView.dispatchTouchEvent(event)
            event.recycle()

            Log.i("VP", "=== P3-06 Multi-touch Pinch Zoom Dispatched; ZoomState=${playerEngine.zoomState.value} ===")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("VP", "PlayerActivity onDestroy")
    }
}
