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
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.playback.PlaybackService
import com.vibeplayer.player.PerfLog
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.data.thumbs.ThumbnailPause
import com.vibeplayer.data.prefs.AppSettings
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    private companion object {
        /** P7-02: deferred start-up work (thumbnails, permission dialog) resumes at the latest after this. */
        const val STARTUP_DEFER_TIMEOUT_MS = 4000L
    }

    val viewModel: PlayerViewModel by viewModels()
    val playerEngine: PlayerEngine get() = viewModel.playerEngine
    private var playbackStarted = false
    @Volatile private var currentVideoPath: String = "/sdcard/Movies/vibeplayer-test/ichiban.mp4"
    // State: a content:// title is resolved off the main thread after the first composition.
    private var currentVideoTitle: String by mutableStateOf("ichiban.mp4")
    /** P7-02: content:// URI whose display name / fd are resolved on IO (see resolveContentOnIo). */
    private var pendingContentUri: Uri? = null

    /** P7-02: everything the first open needs from settings, computed on IO while the UI comes up. */
    private class Startup(val config: PlaybackConfig, val preset: com.vibeplayer.player.ShaderPreset?)
    private var startupDeferred: Deferred<Startup>? = null
    private var contentDeferred: Deferred<Unit>? = null
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

    private fun resolveConfig(appSettings: AppSettings): PlaybackConfig =
        resolveConfig(appSettings.playbackMode, appSettings.renderer)

    /** Null mode/renderer (no settings yet): keep what the service-owned engine runs. */
    private fun resolveConfig(
        modeSetting: com.vibeplayer.data.prefs.PlaybackModeOption? = null,
        rendererSetting: com.vibeplayer.data.prefs.RendererOption? = null
    ): PlaybackConfig {
        val modeArg = intent.getStringExtra("mode") ?: intent.getStringExtra("playback_mode")
        val mode = when (modeArg?.lowercase()) {
            "quality" -> PlaybackMode.QUALITY
            "efficient" -> PlaybackMode.EFFICIENT
            // Without settings (onCreate/onNewIntent) keep what the service-owned engine already
            // runs, so reopening from the notification never flips a playing file's mode.
            else -> when {
                modeSetting == null -> playerEngine.playbackConfig.mode
                modeSetting == com.vibeplayer.data.prefs.PlaybackModeOption.QUALITY -> PlaybackMode.QUALITY
                else -> PlaybackMode.EFFICIENT
            }
        }

        val rendererArg = intent.getStringExtra("renderer")
        val requestedRenderer = when (rendererArg?.lowercase()) {
            "vulkan" -> com.vibeplayer.player.Renderer.VULKAN
            "gles", "opengl" -> com.vibeplayer.player.Renderer.GLES
            else -> when {
                rendererSetting == null -> playerEngine.playbackConfig.renderer
                rendererSetting == com.vibeplayer.data.prefs.RendererOption.VULKAN -> com.vibeplayer.player.Renderer.VULKAN
                else -> com.vibeplayer.player.Renderer.GLES
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
        com.vibeplayer.player.MemoryBudget.useLegacyCache =
            com.vibeplayer.BuildConfig.DEBUG && intent.getBooleanExtra("legacy_cache", false) // P7-07 A/B
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

    private val appSettingsRepository by lazy { AppSettingsRepository(applicationContext) }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i("VP", "PlayerActivity POST_NOTIFICATIONS result: $isGranted")
        if (!isGranted) {
            lifecycleScope.launch(Dispatchers.IO) {
                appSettingsRepository.setNotificationPermissionDenied(true)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                lifecycleScope.launch {
                    val denied = withContext(Dispatchers.IO) {
                        appSettingsRepository.notificationPermissionDeniedFlow.first()
                    }
                    if (!denied) {
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Log.i("VP", "POST_NOTIFICATIONS previously denied; not asking again")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("VP", "PlayerActivity onCreate")
        // Before the ViewModel exists: it decides whether the service may post a notification.
        PlaybackService.benchmarkRun = intent.getBooleanExtra("benchmark", false)
        PerfLog.mark("intent-received")
        // P7-02: touching the engine creates it and starts its mpv initialisation on its own thread,
        // in parallel with everything below. Thumbnail extraction (browser) waits until the first
        // frame is up so it does not compete with start-up.
        val engine = playerEngine
        ThumbnailPause.setPaused(true)
        lifecycleScope.launch {
            withTimeoutOrNull(STARTUP_DEFER_TIMEOUT_MS) { engine.firstFrameReady.first { it } }
            ThumbnailPause.setPaused(false)
            // The notification-permission dialog and its DataStore read are not needed for the first frame.
            if (!PlaybackService.benchmarkRun) checkNotificationPermission()
        }
        startStartupWork(engine)

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

        resolveVideoPath(deferContent = true)
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
            val initialDebugOverlay = com.vibeplayer.BuildConfig.DEBUG && (
                intent.getBooleanExtra("debug_overlay", false) ||
                intent.getStringExtra("debug_overlay")?.toBooleanStrictOrNull() == true
            )
            var showDebugOverlay by remember { mutableStateOf(initialDebugOverlay) }
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
                                holdSpeed = { viewModel.holdSpeed.value },
                                onHoldStart = { playerEngine.setSpeedOverride(it) },
                                onHoldEnd = { playerEngine.setSpeedOverride(null) },
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
                                onSetSubDelay = { viewModel.setSubDelay(it) },
                                onSetAudioDelay = { viewModel.setAudioDelay(it) },
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

    private val startupHints by lazy { getSharedPreferences("startup_hint", Context.MODE_PRIVATE) }

    /**
     * P7-02, IO: (1) give the engine its config straight away, using the mode/renderer seen last time
     * (no DataStore read needed), so the first mpv handle is built once; (2) read the real settings
     * and resolve the config for the open; (3) resolve a content:// URI. The open waits for these.
     */
    private fun startStartupWork(engine: PlayerEngine) {
        startupDeferred = lifecycleScope.async(Dispatchers.IO) {
            val modeHint = startupHints.getString("mode", null)?.let {
                runCatching { com.vibeplayer.data.prefs.PlaybackModeOption.valueOf(it) }.getOrNull()
            }
            val rendererHint = startupHints.getString("renderer", null)?.let {
                runCatching { com.vibeplayer.data.prefs.RendererOption.valueOf(it) }.getOrNull()
            }
            val hinted = resolveConfig(modeHint, rendererHint)
            if (!engine.provideInitialConfig(hinted)) {
                // The handle exists already (service-owned engine of a running session, or init did
                // not wait): keep what the engine runs, as before, never the remembered setting.
                val current = resolveConfig()
                if (current != engine.playbackConfig) engine.reconfigure(current)
            }
            val settings = viewModel.appSettingsRepository.appSettingsFlow.first()
            if (settings.playbackMode.name != modeHint?.name || settings.renderer.name != rendererHint?.name) {
                startupHints.edit()
                    .putString("mode", settings.playbackMode.name)
                    .putString("renderer", settings.renderer.name)
                    .apply()
            }
            computeStartup(settings)
        }
        contentDeferred = lifecycleScope.async(Dispatchers.IO) { resolveContentOnIo() }
    }

    private fun computeStartup(settings: AppSettings): Startup {
        val shaderArg = intent.getStringExtra("shader") ?: intent.getStringExtra("shader_preset")
        val preset = when (shaderArg?.lowercase()) {
            "anime4k_a", "a" -> com.vibeplayer.player.ShaderPreset.ANIME4K_A
            "anime4k_b", "b" -> com.vibeplayer.player.ShaderPreset.ANIME4K_B
            "anime4k_c", "c" -> com.vibeplayer.player.ShaderPreset.ANIME4K_C
            "snapdragon_gsr", "gsr" -> com.vibeplayer.player.ShaderPreset.SNAPDRAGON_GSR
            "none" -> com.vibeplayer.player.ShaderPreset.NONE
            else -> null
        }
        return Startup(resolveConfig(settings), preset)
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
        }
        val hasExplicitTarget = !intent.getStringExtra("path").isNullOrEmpty() || intent.data != null
        if (currentVideoPath != oldPath) {
            openResolvedVideo()
        } else if (hasExplicitTarget && playerEngine.state.value.paused) {
            playerEngine.play()
        }
    }

    /**
     * @param deferContent onCreate: do not query the content resolver here (main thread); keep the URI
     * in [pendingContentUri] and let [resolveContentOnIo] fill in title and fd path.
     */
    private fun resolveVideoPath(deferContent: Boolean = false) {
        currentIdentityKey = null
        currentFolderKey = null
        pendingContentUri = null
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
                if (deferContent) {
                    currentVideoTitle = dataUri.lastPathSegment ?: "Video"
                    currentVideoPath = dataUri.toString()
                    pendingContentUri = dataUri
                } else {
                    currentVideoTitle = queryDisplayName(dataUri)
                    currentVideoPath = resolveContentUri(dataUri)
                }
                currentIdentityKey = dataUri.toString()
                currentFolderKey = dataUri.toString().substringBeforeLast('/', "")
            } else {
                currentVideoPath = dataUri.toString()
                currentVideoTitle = dataUri.lastPathSegment ?: "Video"
            }
        } else {
            // Launched without a target (notification tap after the player was closed with background
            // audio on): show the file the service is playing, not the built-in default.
            PlaybackService.currentInstance?.let { s ->
                s.currentPath.value?.let { currentVideoPath = it }
                currentVideoTitle = s.currentVideoTitle.value
            }
        }
        Log.i("VP", "Resolved video path: $currentVideoPath (title: $currentVideoTitle)")
    }

    /** IO: display name and file descriptor of a content:// target (see resolveVideoPath). */
    private fun resolveContentOnIo() {
        val uri = pendingContentUri
        if (uri != null) {
            currentVideoTitle = queryDisplayName(uri)
            currentVideoPath = resolveContentUri(uri)
            pendingContentUri = null
        } else if (!currentVideoPath.startsWith("fdclose://")) {
            val file = File(currentVideoPath)
            Log.i("VP", "File access: path=$currentVideoPath exists=${file.exists()} canRead=${file.canRead()} size=${file.length()}")
        }
    }

    /** @param useStartup the cold-start open: reuse the work started in onCreate instead of redoing it. */
    private fun openResolvedVideo(useStartup: Boolean = false) {
        lifecycleScope.launch {
            val startup = startupDeferred.takeIf { useStartup }?.await()
                ?: withContext(Dispatchers.IO) {
                    computeStartup(viewModel.appSettingsRepository.appSettingsFlow.first())
                }
            if (useStartup) contentDeferred?.await()
            val debugStartMs = if (com.vibeplayer.BuildConfig.DEBUG && intent.hasExtra("start_ms")) {
                intent.getLongExtra("start_ms", -1L).takeIf { it >= 0 }
                    ?: intent.getIntExtra("start_ms", -1).toLong().takeIf { it >= 0 }
                    ?: intent.getStringExtra("start_ms")?.toLongOrNull()?.takeIf { it >= 0 }
            } else null
            viewModel.openVideo(
                path = currentVideoPath,
                identityKey = currentIdentityKey,
                folderKey = currentFolderKey,
                title = currentVideoTitle,
                playbackConfig = startup.config,
                shaderPreset = startup.preset,
                startPositionOverride = debugStartMs
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

        val forceHwdec = intent.getStringExtra("force_hwdec") ?: intent.getStringExtra("hwdec")
        if (!forceHwdec.isNullOrEmpty()) {
            playerEngine.setHwdec(forceHwdec)
        }
        if (com.vibeplayer.BuildConfig.DEBUG && intent.getBooleanExtra("debug_stall", false)) {
            playerEngine.debugSimulateStallOnce = true
        }
        playbackStarted = true
        val noExplicitTarget = intent.getStringExtra("path").isNullOrEmpty() && intent.data == null
        if (noExplicitTarget && PlaybackService.currentInstance?.currentPath?.value == currentVideoPath) {
            Log.i("VP", "PlayerActivity re-attached to the running session of $currentVideoPath; not reloading")
            return
        }
        openResolvedVideo(useStartup = true)
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

    private val debugReceiver = if (com.vibeplayer.BuildConfig.DEBUG) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.getStringExtra("action")
                    ?: intent.action?.removePrefix("com.vibeplayer.")?.removePrefix("DEBUG_")?.lowercase()
                    ?: return
                Log.i("VP", "PlayerActivity debugReceiver: action=$action")
                when {
                    action == "pause" -> playerEngine.pause()
                    action == "play" -> playerEngine.play()
                    action == "toggle_pause" -> playerEngine.togglePause()
                    action == "next" -> viewModel.skipToNeighbour(true)
                    action == "prev" -> viewModel.skipToNeighbour(false)
                    action.startsWith("seek:") -> {
                        action.substringAfter("seek:").toLongOrNull()?.let { playerEngine.seekTo(it, exact = true) }
                    }
                    action == "seek" -> {
                        val ms = intent.getLongExtra("ms", -1L).takeIf { it >= 0 }
                            ?: intent.getLongExtra("position_ms", -1L).takeIf { it >= 0 }
                            ?: intent.getStringExtra("ms")?.toLongOrNull()
                        if (ms != null) {
                            playerEngine.seekTo(ms, exact = true)
                        } else {
                            val deltaSec = intent.getDoubleExtra("delta_s", 0.0)
                            if (deltaSec != 0.0) playerEngine.seekRelative(deltaSec, exact = true)
                        }
                    }
                    action.startsWith("aspect:") -> {
                        viewModel.setAspect(action.substringAfter("aspect:"))
                    }
                    action == "aspect" -> {
                        val mode = intent.getStringExtra("mode") ?: intent.getStringExtra("aspect")
                        if (mode != null) {
                            viewModel.setAspect(mode)
                        } else {
                            val modes = listOf("fit", "crop", "stretch", "16:9", "4:3")
                            val cur = playerEngine.state.value.aspect ?: "fit"
                            val nextMode = modes[(modes.indexOf(cur) + 1) % modes.size]
                            viewModel.setAspect(nextMode)
                        }
                    }
                    action.startsWith("speed:") -> {
                        action.substringAfter("speed:").toDoubleOrNull()?.let {
                            viewModel.setSpeed(it)
                            viewModel.persistSpeed(it)
                        }
                    }
                    action == "speed" -> {
                        val sp = intent.getDoubleExtra("speed", -1.0).takeIf { it > 0 }
                            ?: intent.getStringExtra("speed")?.toDoubleOrNull()
                        if (sp != null) {
                            viewModel.setSpeed(sp)
                            viewModel.persistSpeed(sp)
                        }
                    }
                    action == "double_tap" || intent.action == "com.vibeplayer.DEBUG_DOUBLE_TAP" -> {
                        val side = intent.getStringExtra("side")?.lowercase() ?: "right"
                        playerEngine.seekRelative(if (side == "left") -10.0 else 10.0, exact = true)
                    }
                }
            }
        }
    } else null

    private fun updatePipParams(state: PipState) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val rectHint = PipHelper.calculateSourceRectHint(bounds.width(), bounds.height(), state.videoWidth, state.videoHeight)
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
            val rectStr = rectHint?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none"
            val boundsStr = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            Log.i("VP", "PiP params set: bounds=$boundsStr sourceRectHint=$rectStr")
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
        val bounds = windowManager.currentWindowMetrics.bounds
        val boundsStr = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        Log.i("VP", "onPictureInPictureModeChanged: isInPictureInPictureMode=$isInPictureInPictureMode bounds=$boundsStr")
        _isInPipFlow.value = isInPictureInPictureMode
        playerEngine.setIsInPip(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            hideSystemBars()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i("VP", "PlayerActivity onStart")
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(this, volumeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w("VP", "Failed to register volume receiver: ${e.message}")
        }
        try {
            val pipFilter = IntentFilter().apply {
                addAction(PipHelper.ACTION_REWIND)
                addAction(PipHelper.ACTION_PLAY_PAUSE)
                addAction(PipHelper.ACTION_FORWARD)
            }
            ContextCompat.registerReceiver(this, pipReceiver, pipFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w("VP", "Failed to register pip receiver: ${e.message}")
        }
        if (debugReceiver != null) {
            try {
                val debugFilter = IntentFilter().apply {
                    addAction("com.vibeplayer.DEBUG_ACTION")
                    addAction("com.vibeplayer.DEBUG_DOUBLE_TAP")
                }
                ContextCompat.registerReceiver(this, debugReceiver, debugFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (e: Exception) {
                Log.w("VP", "Failed to register debug receiver: ${e.message}")
            }
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
        val inPip = isInPictureInPictureMode || _isInPipFlow.value
        if (inPip) {
            Log.i("VP", "PiP dismissed/closed onStop -> stopping playback")
            playerEngine.pause()
            _isInPipFlow.value = false
        } else {
            if (!viewModel.isBackgroundAudioEnabled()) {
                Log.i("VP", "PlayerActivity onStop outside PiP -> pausing playback (background audio off)")
                playerEngine.pause()
            } else {
                Log.i("VP", "PlayerActivity onStop outside PiP -> background audio is on, continuing playback")
            }
        }
        viewModel.saveCurrentPosition()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        if (debugReceiver != null) {
            try {
                unregisterReceiver(debugReceiver)
            } catch (_: Exception) {}
        }
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
        ThumbnailPause.setPaused(false)
    }
}
