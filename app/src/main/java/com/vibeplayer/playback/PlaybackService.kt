package com.vibeplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vibeplayer.R
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.db.FolderSettings
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.data.prefs.PlaybackModeOption
import com.vibeplayer.data.prefs.RendererOption
import com.vibeplayer.data.prefs.ShaderPresetOption
import com.vibeplayer.data.prefs.SubtitlePreferenceMode
import com.vibeplayer.player.FileTrackChoice
import com.vibeplayer.player.FolderTrackChoice
import com.vibeplayer.player.LanguagePreferences
import com.vibeplayer.player.PlaybackConfig
import com.vibeplayer.player.PlaybackMode
import com.vibeplayer.player.PerfLog
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.Renderer
import com.vibeplayer.player.RendererResolver
import com.vibeplayer.player.ShaderPreset
import com.vibeplayer.player.PlayerState
import com.vibeplayer.player.SubtitleMode
import com.vibeplayer.player.TrackSelector
import com.vibeplayer.ui.player.FolderPlaylist
import com.vibeplayer.player.FormatProfileTable
import com.vibeplayer.player.RendererSetting
import com.vibeplayer.player.VideoFormat
import com.vibeplayer.ui.player.PlayerActivity
import com.vibeplayer.ui.player.PlayerViewModel.Neighbours
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : Service() {

    companion object {
        /** P7-02: deferred start-up work (MediaSession, shader extraction) runs at the latest after this. */
        private const val DEFERRED_WORK_TIMEOUT_MS = 3000L
        const val CHANNEL_ID = "vibeplayer_playback"
        /** Benchmark runs: silent channel for the notification Android requires after startForegroundService. */
        const val BENCHMARK_CHANNEL_ID = "vibeplayer_benchmark"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.vibeplayer.playback.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND_10 = "com.vibeplayer.playback.ACTION_REWIND_10"
        const val ACTION_FORWARD_10 = "com.vibeplayer.playback.ACTION_FORWARD_10"
        const val ACTION_PREVIOUS = "com.vibeplayer.playback.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.vibeplayer.playback.ACTION_NEXT"
        const val ACTION_STOP = "com.vibeplayer.playback.ACTION_STOP"
        const val ACTION_DISMISS = "com.vibeplayer.playback.ACTION_DISMISS"

        @Volatile
        private var activeService: PlaybackService? = null
        val currentInstance: PlaybackService? get() = activeService

        @Volatile
        private var activeEngine: PlayerEngine? = null

        /**
         * Debug benchmark run (set by PlayerActivity from its "benchmark" extra): no notification, no
         * foreground start, no history writes, and the service stops when the activity unbinds.
         */
        @Volatile
        var benchmarkRun: Boolean = false

        fun getOrCreateEngine(): PlayerEngine {
            return activeEngine ?: synchronized(PlaybackService::class.java) {
                activeEngine ?: PlayerEngine(waitForInitialConfig = true).also { activeEngine = it }
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    // Captured once: a getter that called getOrCreateEngine() after onDestroy would create (and leak)
    // a second engine.
    val playerEngine: PlayerEngine = getOrCreateEngine()

    private var playerAdapter: VibePlayerAdapter? = null
    private var mediaSession: androidx.media3.session.MediaSession? = null

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var hasAudioFocus = false
    private var isDucked = false
    private var isNoisyReceiverRegistered = false
    private val thermalManager by lazy { ThermalManager(this, playerEngine) }

    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->

        Log.i("VP", "onAudioFocusChange: focusChange=$focusChange")
        val isPlaying = !playerEngine.state.value.paused
        when (AudioFocusPolicy.handleFocusChange(focusChange, isPlaying, resumeOnFocusGain, isDucked)) {
            FocusAction.PAUSE_PERMANENT -> {
                if (isDucked) {
                    isDucked = false
                    playerEngine.setDuck(false)
                }
                resumeOnFocusGain = false
                playerEngine.pause()
            }
            FocusAction.PAUSE_TRANSIENT -> {
                if (isDucked) {
                    isDucked = false
                    playerEngine.setDuck(false)
                }
                resumeOnFocusGain = true
                playerEngine.pause()
            }
            FocusAction.DUCK -> {
                isDucked = true
                playerEngine.setDuck(true)
            }
            FocusAction.RESUME -> {
                resumeOnFocusGain = false
                ensureForegroundForResume("focus gain")
                playerEngine.play()
            }
            FocusAction.UNDUCK -> {
                isDucked = false
                playerEngine.setDuck(false)
            }
            FocusAction.UNDUCK_AND_RESUME -> {
                isDucked = false
                playerEngine.setDuck(false)
                resumeOnFocusGain = false
                ensureForegroundForResume("focus gain")
                playerEngine.play()
            }
            FocusAction.NONE -> {}
        }
    }

    /**
     * P10-10 debug-only: drive the audio-focus listener directly so the transient-loss resume path
     * (path 8) can be exercised on a device without a second player or a real call. Never called in
     * release builds (the debug receiver that reaches it is debug-only).
     */
    fun debugSimulateFocusChange(focusChange: Int) {
        Log.i("VP", "debugSimulateFocusChange: focusChange=$focusChange")
        audioFocusListener.onAudioFocusChange(focusChange)
    }

    private val becomingNoisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i("VP", "ACTION_AUDIO_BECOMING_NOISY -> pausing playback")
                if (isDucked) {
                    isDucked = false
                    playerEngine.setDuck(false)
                }
                resumeOnFocusGain = false
                playerEngine.pause()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i("VP", "requestAudioFocus: granted=$hasAudioFocus")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (isDucked) {
            isDucked = false
            playerEngine.setDuck(false)
        }
        val req = audioFocusRequest
        if (req != null) {
            audioManager.abandonAudioFocusRequest(req)
        }
        hasAudioFocus = false
        resumeOnFocusGain = false
        Log.i("VP", "abandonAudioFocus")
    }

    private fun updateNoisyReceiver(isPlaying: Boolean) {
        if (isPlaying && !isNoisyReceiverRegistered) {
            try {
                val filter = android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    becomingNoisyReceiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isNoisyReceiverRegistered = true
            } catch (e: Exception) {
                Log.w("VP", "Failed to register becomingNoisyReceiver: ${e.message}")
            }
        } else if (!isPlaying && isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    private lateinit var db: AppDatabase
    private val dao by lazy { db.fileSettingsDao() }
    private val folderDao by lazy { db.folderSettingsDao() }
    private lateinit var appSettingsRepository: AppSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var boundClients = 0
    @Volatile private var backgroundAudioEnabled = false

    @Volatile private var currentKey: String? = null
    @Volatile private var currentVideoPath: String? = null
    @Volatile private var currentFolderPath: String? = null
    private var periodicSaveJob: Job? = null
    private var loadJob: Job? = null
    @Volatile private var loadGeneration = 0L
    @Volatile private var lastPathHandedToEngine: String? = null

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _currentVideoTitle = MutableStateFlow("Vibe Player")
    val currentVideoTitle: StateFlow<String> = _currentVideoTitle.asStateFlow()

    private val _neighbours = MutableStateFlow(Neighbours())
    val neighbours: StateFlow<Neighbours> = _neighbours.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.i("VP", "PlaybackService onCreate")
        activeService = this
        db = AppDatabase.getInstance(this)
        appSettingsRepository = AppSettingsRepository(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        FormatTableLoader.loadAsync(this) // P6-06: per-format table for this Vulkan driver (background)

        // Ensure engine is instantiated
        getOrCreateEngine()

        // The engine classifies the failure from the failing handle's mpv log: no Vulkan instance or
        // device is remembered for the whole driver build, a VO/swapchain problem only for the format.
        playerEngine.onVulkanFailureListener = { format, failure ->
            VulkanDriverCache.recordVulkanFailure(
                applicationContext, format,
                isGlobalDriverFailure = failure.scope == com.vibeplayer.player.VulkanFailureScope.DRIVER
            )
        }
        // A content:// file plays from a descriptor (fdclose://N) that mpv closes with its handle; a
        // reconfigure (quality toggle, Vulkan/GLES fallback) or a B-08 reload asks for a fresh one
        // from the file's identity, the original content URI. The engine calls this on IO.
        playerEngine.pathReopener = { identity -> reopenContentUri(identity) }
        serviceScope.launch(Dispatchers.IO) {
            appSettingsRepository.migrateRendererDefaultIfNeeded()
        }

        // P10-06: ADPF hint session while a file plays (setting, default off). Runs on IO; the session
        // closes when the service scope is cancelled in onDestroy.
        PerformanceHintController(
            this, playerEngine,
            appSettingsRepository.appSettingsFlow.map { it.performanceHints }
        ).start(serviceScope)

        // P10-09: HDR output for HDR content on Vulkan gpu-next (setting, default off).
        serviceScope.launch {
            appSettingsRepository.appSettingsFlow.map { it.hdrOutput }.distinctUntilChanged()
                .collect { playerEngine.setHdrOutputEnabled(it) }
        }

        // Shader presets (P7): the service owns the engine, so it owns the directory and the setting.
        playerEngine.setShaderDirectory(File(filesDir, "shaders").absolutePath)
        // P7-02: extraction is only needed before a load that uses a shader preset (awaited there);
        // otherwise it starts after the first frame.
        serviceScope.launch {
            withTimeoutOrNull(DEFERRED_WORK_TIMEOUT_MS) { playerEngine.firstFrameReady.first { it } }
            shaderExtraction.start()
        }
        serviceScope.launch {
            appSettingsRepository.appSettingsFlow
                .map { it.shaderPreset }
                .distinctUntilChanged()
                .collect { playerEngine.setShaderPreset(mapShaderPreset(it)) }
        }

        // P7-02: the Media3 adapter and MediaSession are built after the first frame (see
        // createMediaSession); until then the notification simply has no media style.
        // P4-13: the live subtitle-style collector also starts after the first frame; every
        // loadfile already applies the style (openVideo), and setSubtitleStyle ignores an equal value.
        serviceScope.launch {
            withTimeoutOrNull(DEFERRED_WORK_TIMEOUT_MS) { playerEngine.firstFrameReady.first { it } }
            appSettingsRepository.appSettingsFlow
                .map { it.subtitleStyle() }
                .distinctUntilChanged()
                .collect { playerEngine.setSubtitleStyle(it) }
        }

        // Initial foreground notification (type mediaPlayback)
        startForegroundCompat()
        serviceScope.launch {
            withTimeoutOrNull(DEFERRED_WORK_TIMEOUT_MS) { playerEngine.firstFrameReady.first { it } }
            createMediaSession()
        }
        PerfLog.mark("service-created")

        // Listen for background audio setting changes
        serviceScope.launch {
            appSettingsRepository.appSettingsFlow
                .map { it.backgroundAudio }
                .distinctUntilChanged()
                .collect { backgroundAudioEnabled = it }
        }

        // Periodic save only while actively playing; pause transitions save below.
        periodicSaveJob = serviceScope.launch(Dispatchers.IO) {
            playerEngine.state.map { it.paused }.distinctUntilChanged().collectLatest { paused ->
                if (!paused) {
                    while (isActive) {
                        delay(10_000L)
                        saveCurrentPosition()
                    }
                }
            }
        }

        // Save when playback pauses, update notification, manage audio focus & noisy receiver, and check if service should stop
        serviceScope.launch {
            var wasPaused = playerEngine.state.value.paused
            playerEngine.state.collect { state ->
                playerAdapter?.notifyStateChanged()
                if (state.paused && !wasPaused) {
                    saveCurrentPosition()
                    updateNoisyReceiver(false)
                    thermalManager.onPlaybackStopped()
                    if (!resumeOnFocusGain) {
                        abandonAudioFocus()
                    }
                } else if (!state.paused && wasPaused) {
                    // Playing again (user, notification, auto-advance): a pending transient-loss resume
                    // must not start playback later after the user paused again.
                    resumeOnFocusGain = false
                    // P10-10: the foreground service must be up before the focus request (Android 17 returns
                    // AUDIOFOCUS_REQUEST_FAILED from a background app without one).
                    val fgOk = ensureForegroundForResume("resume")
                    val granted = fgOk && requestAudioFocus()
                    if (!granted) {
                        Log.w("VP", "Audio focus denied (foreground=$fgOk; AudioHardening?) -> pausing playback")
                        playerEngine.pause()
                    } else {
                        updateNoisyReceiver(true)
                        thermalManager.onPlaybackStarted()
                    }
                }

                if (state.paused != wasPaused) {
                    if (!state.paused) {
                        pausedTimeoutJob?.cancel()
                        pausedTimeoutJob = null
                        startForegroundCompat()
                    }
                    updateNotification()
                    // Not immediate: at the end of a file keep-open pauses and eof-reached arrives in
                    // the same burst, and auto-advance (onPlaybackEnded) must get its chance first.
                    if (state.paused) scheduleStopCheck()
                }
                wasPaused = state.paused
            }
        }

        serviceScope.launch {
            // Title, file and folder neighbours feed the MediaSession playlist.
            combine(currentPath, currentVideoTitle, neighbours) { _, _, _ -> }.collect {
                playerAdapter?.notifyStateChanged()
                updateNotification()
            }
        }

        serviceScope.launch {
            playerEngine.position.collect {
                playerAdapter?.notifyStateChanged()
            }
        }

        // Auto-play the next file in the folder when the current one reaches its end
        serviceScope.launch {
            var wasEof = false
            playerEngine.state.collect { state ->
                if (state.eof && !wasEof && state.durationMs > 0L) {
                    onPlaybackEnded()
                }
                wasEof = state.eof
            }
        }
    }

    private var pausedTimeoutJob: Job? = null
    @Volatile private var isForegroundService = false

    private fun mapShaderPreset(option: ShaderPresetOption): ShaderPreset = when (option) {
        ShaderPresetOption.NONE -> ShaderPreset.NONE
        ShaderPresetOption.ANIME4K_A -> ShaderPreset.ANIME4K_A
        ShaderPresetOption.ANIME4K_B -> ShaderPreset.ANIME4K_B
        ShaderPresetOption.ANIME4K_C -> ShaderPreset.ANIME4K_C
        ShaderPresetOption.SNAPDRAGON_GSR -> ShaderPreset.SNAPDRAGON_GSR
    }

    private fun createMediaSession() {
        if (activeService !== this || mediaSession != null) return
        val adapter = VibePlayerAdapter(mainLooper, playerEngine, this)
        playerAdapter = adapter
        mediaSession = androidx.media3.session.MediaSession.Builder(this, adapter)
            .setId("vibeplayer_session")
            .setSessionActivity(contentPendingIntent)
            .build()
        adapter.notifyStateChanged()
        updateNotification() // now with the media style
    }

    /** Extracted from assets on first need; started early by the first load that uses a preset. */
    private val shaderExtraction = serviceScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        ensureShadersExtracted(this@PlaybackService)
    }

    private fun ensureShadersExtracted(context: Context): File {
        val targetDir = File(context.filesDir, "shaders")
        val versionFile = File(targetDir, ".version")
        val targetVersion = com.vibeplayer.BuildConfig.VERSION_CODE.toString()

        if (targetDir.exists() && versionFile.exists()) {
            val currentVersion = try { versionFile.readText().trim() } catch (_: Exception) { "" }
            if (currentVersion == targetVersion) {
                return targetDir
            }
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        try {
            val assetManager = context.assets
            val assetFiles = assetManager.list("shaders") ?: emptyArray()
            for (fileName in assetFiles) {
                val outFile = File(targetDir, fileName)
                assetManager.open("shaders/$fileName").use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            versionFile.writeText(targetVersion)
            Log.i("VP", "Extracted ${assetFiles.size} shaders to ${targetDir.absolutePath} (v$targetVersion)")
        } catch (e: Exception) {
            Log.e("VP", "Failed to extract shaders from assets", e)
        }

        return targetDir
    }

    /**
     * [notice] NONE does nothing; SILENT (benchmark run) posts a minimal notification on a silent channel;
     * MEDIA the normal one. See [ForegroundStartPolicy] for which applies where.
     */
    private fun startForegroundCompat(notice: ForegroundNotice = ForegroundStartPolicy.opportunistic(benchmarkRun)): Boolean {
        if (notice == ForegroundNotice.NONE) return true
        val notif = if (notice == ForegroundNotice.SILENT) {
            Log.i("VP", "PlaybackService: foreground start during a benchmark run -> silent notification")
            buildBenchmarkNotification()
        } else {
            buildNotification(currentVideoTitle.value, !playerEngine.state.value.paused)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            isForegroundService = true
            return true
        } catch (e: Exception) {
            Log.e("VP", "Failed to startForeground: ${e.message}", e)
            return false
        }
    }

    /**
     * P10-10 (Android 17 background-audio hardening): before audio resumes with no activity bound
     * (media key, lock-screen control, focus gain), make sure the mediaPlayback foreground service is
     * running. Returns false when it could not be started (then the caller must not start audio).
     * A benchmark run never gets a foreground notice (NONE) and counts as fine.
     */
    private fun ensureForegroundForResume(why: String): Boolean {
        if (!PlaybackLifecyclePolicy.needsForegroundBeforeResume(boundClients, isForegroundService)) return true
        val ok = startForegroundCompat()
        Log.i("VP", "ensureForegroundForResume($why): foreground=$ok")
        return ok
    }

    /** Called by the MediaSession adapter before engine.play() (headset, lock screen, system media controls). */
    fun prepareAudioResume() {
        pausedTimeoutJob?.cancel()
        pausedTimeoutJob = null
        ensureForegroundForResume("media session play")
    }

    private fun detachFromForeground() {
        if (isForegroundService) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForegroundService = false
                Log.i("VP", "PlaybackService detached from foreground (media notification retained)")
            } catch (e: Exception) {
                Log.e("VP", "Failed to stopForeground(STOP_FOREGROUND_DETACH): ${e.message}", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("VP", "PlaybackService onStartCommand (action=${intent?.action})")
        // Every startForegroundService() must be followed by startForeground() or the app is killed, also
        // during a benchmark run (silent notification) and whatever the benchmark flag says by now;
        // a service that already left the foreground (task removed while still bound) is reused.
        // Already foreground since onCreate: the notification is kept current by updateNotification().
        startForegroundCompat(ForegroundStartPolicy.onStartCommand(intent?.action, benchmarkRun, isForegroundService))
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (isDucked) {
                    isDucked = false
                    playerEngine.setDuck(false)
                }
                resumeOnFocusGain = false
                pausedTimeoutJob?.cancel()
                pausedTimeoutJob = null
                playerEngine.togglePause()
            }
            ACTION_REWIND_10 -> playerEngine.seekRelative(-10.0, exact = true)
            ACTION_FORWARD_10 -> playerEngine.seekRelative(10.0, exact = true)
            ACTION_PREVIOUS -> skipToNeighbour(forward = false)
            ACTION_NEXT -> skipToNeighbour(forward = true)
            ACTION_DISMISS -> {
                Log.i("VP", "PlaybackService dismissed via notification deleteIntent -> stopping")
                if (isDucked) {
                    isDucked = false
                    playerEngine.setDuck(false)
                }
                resumeOnFocusGain = false
                pausedTimeoutJob?.cancel()
                pausedTimeoutJob = null
                saveCurrentPosition()
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundService = false
                stopSelf()
            }
            ACTION_STOP -> stopFromUser()
        }
        return START_NOT_STICKY
    }

    /** The user ended playback (notification stop, mini player closed): pause, save, remove the notification. */
    fun stopFromUser() {
        if (isDucked) {
            isDucked = false
            playerEngine.setDuck(false)
        }
        resumeOnFocusGain = false
        pausedTimeoutJob?.cancel()
        pausedTimeoutJob = null
        playerEngine.pause()
        saveCurrentPosition()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundService = false
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        pausedTimeoutJob?.cancel()
        pausedTimeoutJob = null
        Log.i("VP", "PlaybackService onBind, boundClients=$boundClients")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients--
        Log.i("VP", "PlaybackService onUnbind, boundClients=$boundClients")
        if (benchmarkRun && boundClients <= 0) {
            playerEngine.pause()
            stopSelf()
            return true
        }
        checkShouldStop()
        return true // allow rebind
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        boundClients++
        pausedTimeoutJob?.cancel()
        pausedTimeoutJob = null
        Log.i("VP", "PlaybackService onRebind, boundClients=$boundClients")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("VP", "PlaybackService onTaskRemoved")
        val state = playerEngine.state.value
        val keepPlaying = PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(
            backgroundAudioEnabled = backgroundAudioEnabled,
            isPaused = state.paused,
            isEof = state.eof
        )
        if (!keepPlaying) {
            Log.i("VP", "onTaskRemoved: stopping service and releasing engine")
            pausedTimeoutJob?.cancel()
            pausedTimeoutJob = null
            saveCurrentPosition()
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundService = false
            stopSelf()
        }
    }

    fun isBackgroundAudioEnabled(): Boolean = backgroundAudioEnabled

    private var stopCheckJob: Job? = null

    private fun scheduleStopCheck() {
        stopCheckJob?.cancel()
        stopCheckJob = serviceScope.launch {
            delay(1_000L)
            if (loadJob?.isActive != true) checkShouldStop()
        }
    }

    fun checkShouldStop(isPausedTimeout: Boolean = false) {
        val state = playerEngine.state.value
        val shouldStop = PlaybackLifecyclePolicy.shouldStopService(
            boundClients = boundClients,
            backgroundAudioEnabled = backgroundAudioEnabled,
            isPaused = state.paused,
            isEof = state.eof,
            pausedTimeoutExpired = isPausedTimeout
        )
        if (shouldStop) {
            Log.i("VP", "PlaybackService stopping itself: boundClients=$boundClients, bgAudio=$backgroundAudioEnabled, paused=${state.paused}, eof=${state.eof}, timeout=$isPausedTimeout")
            pausedTimeoutJob?.cancel()
            pausedTimeoutJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundService = false
            stopSelf()
        } else {
            val shouldBeInFg = PlaybackLifecyclePolicy.shouldBeInForeground(boundClients, state.paused, resumeOnFocusGain)
            if (!shouldBeInFg) {
                detachFromForeground()
                updateNotification()
                schedulePausedTimeout()
            } else {
                pausedTimeoutJob?.cancel()
                pausedTimeoutJob = null
            }
        }
    }

    private fun schedulePausedTimeout() {
        if (pausedTimeoutJob?.isActive == true) return
        pausedTimeoutJob = serviceScope.launch {
            Log.i("VP", "Scheduling 10-minute paused timeout")
            delay(PlaybackLifecyclePolicy.PAUSED_STOP_TIMEOUT_MS)
            if (playerEngine.state.value.paused && boundClients == 0) {
                Log.i("VP", "10-minute paused timeout reached -> stopping service")
                checkShouldStop(isPausedTimeout = true)
            }
        }
    }

    private suspend fun onPlaybackEnded() {
        val endedPath = currentVideoPath ?: return
        if (!appSettingsRepository.appSettingsFlow.first().autoPlayNext) {
            checkShouldStop()
            return
        }
        val next = withContext(Dispatchers.IO) { FolderPlaylist.next(endedPath) }
        if (next == null) {
            Log.i("VP", "Auto-play: no next file after $endedPath")
            checkShouldStop()
            return
        }
        if (currentVideoPath != endedPath) return // file changed meanwhile
        Log.i("VP", "Auto-play: $endedPath ended -> $next")
        openVideo(next, startFromBeginning = true, playAfterLoad = true)
    }

    fun skipToNeighbour(forward: Boolean) {
        val from = currentVideoPath ?: return
        // P10-10: next/previous from a headset or lock screen starts playback; foreground first.
        ensureForegroundForResume("skip")
        serviceScope.launch {
            val leavingKey = currentKey ?: return@launch
            val leavingState = playerEngine.state.value
            val leavingPosition = withContext(Dispatchers.IO) { playerEngine.currentPositionMs() }
            savePositionSnapshot(leavingKey, leavingPosition, leavingState)

            if (currentVideoPath != from) return@launch
            val target = withContext(Dispatchers.IO) {
                if (forward) FolderPlaylist.next(from) else FolderPlaylist.previous(from)
            } ?: return@launch
            if (currentVideoPath != from) return@launch
            openVideo(target, playAfterLoad = true, leavingPositionSaved = true)
        }
    }

    /** IO only. A fresh `fdclose://N` for a content:// [identity], or null (grant gone, file deleted). */
    private fun reopenContentUri(identity: String): String? {
        if (!identity.startsWith("content://")) return null
        return try {
            contentResolver.openFileDescriptor(Uri.parse(identity), "r")?.let { pfd ->
                "fdclose://${pfd.detachFd()}"
            }
        } catch (e: Exception) {
            // SecurityException when the URI grant ended with the activity, FileNotFoundException.
            Log.w("VP", "Reopening $identity failed: ${e.message}")
            null
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
            Log.w("VP", "Failed to query displayName in PlaybackService: ${e.message}")
        }
        return uri.lastPathSegment ?: "Video"
    }

    fun computeKey(path: String, sizeBytes: Long = 0L): String {
        val normalized = com.vibeplayer.ui.normalizeMediaPath(path)
        val size = if (sizeBytes > 0L) {
            sizeBytes
        } else {
            val f = File(normalized)
            if (f.exists()) f.length() else {
                val fOrig = File(path)
                if (fOrig.exists()) fOrig.length() else 0L
            }
        }
        return if (size > 0L) "$normalized:$size" else normalized
    }

    fun openVideo(
        path: String,
        sizeBytes: Long = 0L,
        identityKey: String? = null,
        folderKey: String? = null,
        startFromBeginning: Boolean = false,
        playAfterLoad: Boolean = false,
        title: String? = null,
        playbackConfig: PlaybackConfig? = null,
        shaderPreset: ShaderPreset? = null,
        startPositionOverride: Long? = null,
        /** skipToNeighbour already saved the leaving file with a fresh (bounded) read. */
        leavingPositionSaved: Boolean = false
    ) {
        val leavingKey = currentKey
        val leavingPath = currentVideoPath
        val leavingPos = playerEngine.lastKnownPositionMs()
        val leavingState = playerEngine.state.value
        if (!benchmarkRun && !leavingPositionSaved && leavingKey != null && leavingPath != null && leavingPath != path) {
            saveScope.launch {
                savePositionSnapshot(leavingKey, leavingPos, leavingState)
            }
        }

        val generation = ++loadGeneration
        loadJob?.cancel()
        currentVideoPath = path
        currentFolderPath = folderKey ?: File(path).parent
        _currentPath.value = path
        val resolvedTitle = title ?: when {
            path.startsWith("/") -> File(path).name
            identityKey?.startsWith("content://") == true -> queryDisplayName(Uri.parse(identityKey))
            path.startsWith("content://") -> queryDisplayName(Uri.parse(path))
            else -> identityKey ?: "Video"
        }
        _currentVideoTitle.value = resolvedTitle
        _neighbours.value = Neighbours()
        // The title/path/neighbours collector in onCreate refreshes the notification.

        serviceScope.launch(Dispatchers.IO) {
            val files = if (path.startsWith("/")) FolderPlaylist.list(path) else emptyList()
            val index = files.indexOf(File(path).absolutePath)
            val updated = Neighbours(previous = index > 0, next = index >= 0 && index < files.lastIndex)
            withContext(Dispatchers.Main.immediate) {
                if (currentVideoPath == path) _neighbours.value = updated
            }
        }
        // An fdclose://N path is owned by this open until it is handed to the engine; a superseded or
        // cancelled open closes it (unless a newer open uses the same path string).
        var handedToEngine = false
        loadJob = serviceScope.launch {
            val resolvedFolderKey = folderKey ?: (File(path).parent ?: "")
            // P7-02: key (File.exists/length), per-file row, folder row, app settings, and format detection in parallel,
            // none of it on the main thread; loadfile follows with everything at once.
            val keyDeferred = async(Dispatchers.IO) { computeKey(identityKey ?: path, sizeBytes) }
            val fileDeferred = async(Dispatchers.IO) {
                val k = keyDeferred.await()
                dao.getByKey(k)
                    ?: dao.getByKey(path)
                    ?: (if (k.contains("/storage/emulated/0/")) dao.getByKey(k.replace("/storage/emulated/0/", "/sdcard/")) else null)
                    ?: (if (path.contains("/storage/emulated/0/")) dao.getByKey(path.replace("/storage/emulated/0/", "/sdcard/")) else null)
            }
            val folderDeferred = async(Dispatchers.IO) {
                if (resolvedFolderKey.isNotEmpty()) folderDao.getByPath(resolvedFolderKey) else null
            }
            val settingsDeferred = async(Dispatchers.IO) { appSettingsRepository.appSettingsFlow.first() }
            val clipDeferred = async(Dispatchers.IO) {
                VideoFormatDetector.detectClip(applicationContext, path, identityKey)
            }
            val formatDeferred = async { clipDeferred.await().format }
            // IO: the gpu-api query can create a temporary mpv handle, and the driver cache reads
            // SharedPreferences and stats the Vulkan driver file.
            val vulkanDeferred = async(Dispatchers.IO) {
                val libMpvChoices = playerEngine.availableGpuApis.ifEmpty { PlayerEngine.getGpuApiChoices() }
                val libMpvHasVulkan = libMpvChoices.any { it.equals("vulkan", ignoreCase = true) }
                VulkanDriverCache.isVulkanAvailable(
                    context = applicationContext,
                    format = formatDeferred.await(),
                    libMpvHasVulkan = libMpvHasVulkan
                ) && !PlayerEngine.sessionVulkanFailed
            }

            val key = keyDeferred.await()
            val fileSettings = fileDeferred.await()
            val folderSettings = folderDeferred.await()
            val appSettings = settingsDeferred.await()
            val detectedClip = clipDeferred.await()
            val detectedFormat = detectedClip.format
            val isVulkanAvailable = vulkanDeferred.await()
            if (generation != loadGeneration || currentVideoPath != path) return@launch

            val startPositionMs = if (startPositionOverride != null) {
                Log.i("VP", "Resume: override starting from $startPositionOverride ms")
                startPositionOverride
            } else if (startFromBeginning) {
                0L
            } else if (fileSettings != null) {
                val dur = fileSettings.durationMs
                val pos = fileSettings.positionMs
                if (dur > 0L && pos >= (dur * 0.95)) {
                    Log.i("VP", "Resume: pos $pos ms is in last 5% of duration $dur ms -> restarting from 0")
                    0L
                } else if (pos > 0L) {
                    Log.i("VP", "Resume: resuming from $pos ms for key $key")
                    pos
                } else {
                    0L
                }
            } else {
                0L
            }

            val fileChoice = fileSettings?.let {
                FileTrackChoice(
                    audioTrackId = it.audioTrackId,
                    audioTrackTitle = it.audioTrackTitle,
                    audioTrackLang = it.audioTrackLang,
                    audioEnabled = it.audioEnabled,
                    subTrackId = it.subTrackId,
                    subTrackTitle = it.subTrackTitle,
                    subTrackLang = it.subTrackLang,
                    subEnabled = it.subEnabled
                )
            }

            val folderChoice = folderSettings?.let {
                FolderTrackChoice(
                    audioLang = it.audioLang,
                    audioTitle = it.audioTitle,
                    subLang = it.subLang,
                    subTitle = it.subTitle,
                    subKind = it.subKind,
                    subEnabled = it.subEnabled
                )
            }

            val subMode = when (appSettings.subtitleMode) {
                SubtitlePreferenceMode.FORCED_ONLY -> SubtitleMode.FORCED_ONLY
                SubtitlePreferenceMode.FULL -> SubtitleMode.FULL
                SubtitlePreferenceMode.OFF -> SubtitleMode.OFF
            }

            val preferences = LanguagePreferences(
                preferredAudioLanguages = appSettings.preferredAudioLanguages,
                preferredSubtitleLanguages = appSettings.preferredSubtitleLanguages,
                subtitleMode = subMode
            )

            playerEngine.setPendingTrackSelection(
                fileChoice = fileChoice,
                folderChoice = folderChoice,
                preferences = preferences
            )

            val audioTrackId = fileChoice?.audioTrackId
            val audioEnabled = fileChoice?.audioEnabled ?: true
            val subTrackId = fileChoice?.subTrackId
            val subEnabled = fileChoice?.subEnabled ?: true
            val speed = fileSettings?.speed?.toDouble() ?: 1.0
            val aspect = fileSettings?.aspect
            val subDelayMs = fileSettings?.subDelayMs ?: 0
            val audioDelayMs = fileSettings?.audioDelayMs ?: 0

            // Quality/efficient mode and renderer come from settings (or the launch intent) and are
            // applied here, the code path that opens every file: opens from the activity, auto-advance,
            // skip buttons and the MediaSession all end up in this function.
            val targetMode = if (appSettings.playbackMode == PlaybackModeOption.QUALITY) {
                PlaybackMode.QUALITY
            } else {
                PlaybackMode.EFFICIENT
            }

            val rendererSetting = when (appSettings.renderer) {
                RendererOption.AUTO -> RendererSetting.AUTO
                RendererOption.VULKAN -> RendererSetting.VULKAN
                RendererOption.OPENGL -> RendererSetting.GLES
            }

            val resolvedConfig = FormatProfileTable.resolve(
                format = detectedFormat,
                mode = targetMode,
                rendererSetting = rendererSetting,
                isVulkanAvailableForFormat = isVulkanAvailable
            )
            playerEngine.setCurrentFormatProfile(resolvedConfig)

            Log.i("VP", "Picked configuration for ${detectedFormat.label}: ${resolvedConfig.chosenRowDescription} (table: ${resolvedConfig.tableSource}, reason: ${resolvedConfig.reason})")

            val targetConfig = if (playbackConfig?.voOverride != null) {
                playbackConfig
            } else {
                (playbackConfig ?: playerEngine.playbackConfig).copy(
                    mode = targetMode,
                    renderer = resolvedConfig.renderer,
                    voOverride = resolvedConfig.vo,
                    hwdecOverride = playbackConfig?.hwdecOverride ?: resolvedConfig.decodeMode.hwdec,
                    formatProfile = resolvedConfig
                )
            }
            playerEngine.provideInitialConfig(targetConfig)
            playerEngine.awaitInitialised()
            if (generation != loadGeneration || currentVideoPath != path) return@launch

            if (playerEngine.playbackConfig.mode != targetConfig.mode ||
                playerEngine.playbackConfig.renderer != targetConfig.renderer ||
                playerEngine.playbackConfig.vo != targetConfig.vo ||
                playerEngine.playbackConfig.hwdecOverride != targetConfig.hwdecOverride ||
                playerEngine.playbackConfig.debugMpvOptions != targetConfig.debugMpvOptions
            ) {
                // The new file is loaded below: do not reload the leaving one on the new handle.
                playerEngine.reconfigure(targetConfig, reloadCurrentFile = false)
            }

            val effectivePreset = shaderPreset ?: mapShaderPreset(appSettings.shaderPreset)
            if (effectivePreset != ShaderPreset.NONE) shaderExtraction.await()
            playerEngine.setShaderPreset(effectivePreset)
            playerEngine.setSubtitleStyle(appSettings.subtitleStyle())

            // P4-14: re-open the remembered external subtitle off the main thread (this coroutine runs on Main).
            val externalSubUri = fileSettings?.externalSubUri
            val externalSub: Pair<String, String>? = if (externalSubUri.isNullOrEmpty()) null else withContext(Dispatchers.IO) {
                try {
                    val subUri = Uri.parse(externalSubUri)
                    val pfd = contentResolver.openFileDescriptor(subUri, "r")
                    if (pfd != null) {
                        val title = queryDisplayName(subUri)
                        "fdclose://${pfd.detachFd()}" to title
                    } else {
                        Log.w("VP", "Reopening external subtitle: null descriptor for $externalSubUri")
                        null
                    }
                } catch (e: Exception) {
                    Log.w("VP", "Reopening external subtitle $externalSubUri failed: ${e.message}")
                    null
                }
            }
            if (generation != loadGeneration || currentVideoPath != path) {
                externalSub?.let { PlayerEngine.closeFdPath(it.first) }
                return@launch
            }

            thermalManager.onFileChanged(path)
            playerEngine.load(
                path = path,

                startPositionMs = startPositionMs,
                audioTrackId = if (audioEnabled) audioTrackId else null,
                audioEnabled = audioEnabled,
                subTrackId = if (subEnabled) subTrackId else null,
                subEnabled = subEnabled,
                speed = speed,
                aspect = aspect,
                subDelayMs = subDelayMs,
                audioDelayMs = audioDelayMs,
                identity = identityKey ?: path,
                clip = detectedClip
            )
            if (externalSub != null) {
                val (subFdPath, subTitle) = externalSub
                // Select it only when it was the last chosen subtitle; the user may have switched to an
                // embedded track (or turned subtitles off) since loading the file.
                val selectExternal = (fileSettings?.subEnabled ?: true) && fileSettings?.subTrackTitle == subTitle
                playerEngine.addSubtitleTrack(fdPath = subFdPath, title = subTitle, select = selectExternal, sourceUri = externalSubUri)
                Log.i("VP", "Reopening video: re-added external subtitle $externalSubUri ($subTitle, select=$selectExternal)")
            }
            handedToEngine = true
            lastPathHandedToEngine = path
            currentKey = key
            if (playAfterLoad) playerEngine.play()
        }
        loadJob?.invokeOnCompletion {
            // Never a path mpv may own: one an earlier or a parallel open of the same string handed over.
            if (!handedToEngine && currentVideoPath != path && lastPathHandedToEngine != path) {
                PlayerEngine.closeFdPath(path)
            }
        }
    }

    fun setAudioTrack(trackId: Int?) {
        playerEngine.setAudioTrack(trackId)
        if (benchmarkRun) return
        val key = currentKey ?: return
        val folderPathForKey = currentFolderPath
        serviceScope.launch(Dispatchers.IO) {
            val track = if (trackId != null) {
                playerEngine.state.value.tracks.firstOrNull { it.id == trackId && it.type == "audio" }
            } else null
            val isEnabled = (trackId != null)
            val existing = dao.getByKey(key)
            val updated = existing?.copy(
                audioTrackId = trackId,
                audioTrackTitle = track?.title,
                audioTrackLang = track?.lang,
                audioEnabled = isEnabled,
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                audioTrackId = trackId,
                audioTrackTitle = track?.title,
                audioTrackLang = track?.lang,
                audioEnabled = isEnabled,
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Persisted file audioTrackId=$trackId, audioEnabled=$isEnabled (${track?.lang}, ${track?.title}) for key=$key")

            val folderPath = folderPathForKey
            if (!folderPath.isNullOrEmpty()) {
                val existingFolder = folderDao.getByPath(folderPath)
                val updatedFolder = existingFolder?.copy(
                    audioLang = track?.lang,
                    audioTitle = track?.title,
                    updatedAt = System.currentTimeMillis()
                ) ?: FolderSettings(
                    folderPath = folderPath,
                    audioLang = track?.lang,
                    audioTitle = track?.title,
                    updatedAt = System.currentTimeMillis()
                )
                folderDao.upsert(updatedFolder)
                Log.i("VP", "Persisted folder audio: lang=${track?.lang}, title=${track?.title} for folder=$folderPath")
            }
        }
    }

    fun setSubtitleTrack(trackId: Int?) {
        playerEngine.setSubtitleTrack(trackId)
        if (benchmarkRun) return
        val key = currentKey ?: return
        val folderPathForKey = currentFolderPath
        serviceScope.launch(Dispatchers.IO) {
            val track = if (trackId != null) {
                playerEngine.state.value.tracks.firstOrNull { it.id == trackId && it.type == "sub" }
            } else null
            val isEnabled = (trackId != null)
            val existing = dao.getByKey(key)
            val updated = existing?.copy(
                subTrackId = trackId,
                subTrackTitle = track?.title,
                subTrackLang = track?.lang,
                subEnabled = isEnabled,
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                subTrackId = trackId,
                subTrackTitle = track?.title,
                subTrackLang = track?.lang,
                subEnabled = isEnabled,
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Persisted file subTrackId=$trackId, subEnabled=$isEnabled (${track?.lang}, ${track?.title}) for key=$key")

            val folderPath = folderPathForKey
            if (!folderPath.isNullOrEmpty()) {
                val subKind = if (track != null) TrackSelector.getSubtitleKind(track).name else null
                val existingFolder = folderDao.getByPath(folderPath)
                val updatedFolder = existingFolder?.copy(
                    subLang = track?.lang,
                    subTitle = track?.title,
                    subKind = subKind,
                    subEnabled = isEnabled,
                    updatedAt = System.currentTimeMillis()
                ) ?: FolderSettings(
                    folderPath = folderPath,
                    subLang = track?.lang,
                    subTitle = track?.title,
                    subKind = subKind,
                    subEnabled = isEnabled,
                    updatedAt = System.currentTimeMillis()
                )
                folderDao.upsert(updatedFolder)
                Log.i("VP", "Persisted folder sub: lang=${track?.lang}, title=${track?.title}, kind=$subKind, enabled=$isEnabled for folder=$folderPath")
            }
        }
    }

    /**
     * P4-14: Opens external subtitle [uri] through a file descriptor on IO, hands it to mpv with `sub-add`
     * using `fdclose://<fd>` (mpv owns and closes the fd), and remembers it per file in Room.
     */
    fun loadSubtitleFile(uri: Uri) {
        serviceScope.launch(Dispatchers.IO) {
            val key = currentKey ?: return@launch
            val displayName = queryDisplayName(uri)
            val pfd = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.w("VP", "Failed to open subtitle descriptor for $uri: ${e.message}")
                null
            } ?: return@launch

            val fd = pfd.detachFd()
            val fdPath = "fdclose://$fd"

            playerEngine.addSubtitleTrack(fdPath, displayName, select = true, sourceUri = uri.toString())

            val existing = dao.getByKey(key)
            val updated = existing?.copy(
                externalSubUri = uri.toString(),
                subEnabled = true,
                subTrackTitle = displayName,
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                externalSubUri = uri.toString(),
                subEnabled = true,
                subTrackTitle = displayName,
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Loaded external subtitle $uri (title=$displayName) for key=$key")
        }
    }

    fun setSpeed(speed: Double) {
        playerEngine.setSpeed(speed)
    }

    fun persistSpeed(speed: Double) {
        if (benchmarkRun) return
        val key = currentKey ?: return
        serviceScope.launch(Dispatchers.IO) {
            val existing = dao.getByKey(key)
            val updated = existing?.copy(
                speed = speed.toFloat(),
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                speed = speed.toFloat(),
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Persisted speed=$speed for key=$key")
        }
    }

    fun setSubDelay(ms: Int) {
        val v = com.vibeplayer.player.SyncDelay.clamp(ms)
        playerEngine.setSubDelay(v)
        persistDelays { it.copy(subDelayMs = v) }
    }

    fun setAudioDelay(ms: Int) {
        val v = com.vibeplayer.player.SyncDelay.clamp(ms)
        playerEngine.setAudioDelay(v)
        persistDelays { it.copy(audioDelayMs = v) }
    }

    private fun persistDelays(change: (FileSettings) -> FileSettings) {
        if (benchmarkRun) return
        val key = currentKey ?: return
        serviceScope.launch(Dispatchers.IO) {
            val base = dao.getByKey(key) ?: FileSettings(key = key)
            dao.upsert(change(base).copy(lastPlayedAt = System.currentTimeMillis()))
        }
    }

    fun setAspect(aspect: String?) {
        playerEngine.setAspect(aspect)
        if (benchmarkRun) return
        val key = currentKey ?: return
        serviceScope.launch(Dispatchers.IO) {
            val existing = dao.getByKey(key)
            val updated = existing?.copy(
                aspect = aspect,
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                aspect = aspect,
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Persisted aspect=$aspect for key=$key")
        }
    }

    /**
     * @param releaseWhenDone engine to release once the final position read has finished (onDestroy):
     * releasing it first would make the read fail and overwrite the saved position with 0.
     */
    fun saveCurrentPosition(releaseWhenDone: PlayerEngine? = null) {
        val engine = releaseWhenDone ?: playerEngine
        if (benchmarkRun) { releaseWhenDone?.release(); return }
        val generation = loadGeneration
        val key = currentKey ?: run { releaseWhenDone?.release(); return }
        val knownPos = engine.lastKnownPositionMs()
        val state = engine.state.value
        if (generation != loadGeneration || key != currentKey) { releaseWhenDone?.release(); return }
        saveScope.launch {
            // Bounded inside the engine (falls back to the last known position during a stall).
            val fresh = try {
                engine.currentPositionMs()
            } catch (_: Exception) {
                null
            }
            releaseWhenDone?.release()
            val pos = if (generation == loadGeneration && key == currentKey) {
                fresh ?: knownPos
            } else {
                knownPos
            }
            val dur = state.durationMs

            if (pos > 0L || dur > 0L) {
                withContext(Dispatchers.IO) {
                    val existing = dao.getByKey(key)
                    val finalDuration = if (dur > 0L) dur else (existing?.durationMs ?: 0L)

                    val currentAid = state.selectedAudioTrackId
                    val audioTrack = if (currentAid != null) {
                        state.tracks.firstOrNull { it.id == currentAid && it.type == "audio" }
                    } else null

                    val currentSid = state.selectedSubTrackId
                    val subTrack = if (currentSid != null) {
                        state.tracks.firstOrNull { it.id == currentSid && it.type == "sub" }
                    } else null

                    val audioTrackId = existing?.audioTrackId ?: currentAid
                    val audioTrackTitle = existing?.audioTrackTitle ?: audioTrack?.title
                    val audioTrackLang = existing?.audioTrackLang ?: audioTrack?.lang
                    val audioEnabled = existing?.audioEnabled ?: (currentAid != null)

                    val subTrackId = existing?.subTrackId ?: currentSid
                    val subTrackTitle = existing?.subTrackTitle ?: subTrack?.title
                    val subTrackLang = existing?.subTrackLang ?: subTrack?.lang
                    val subEnabled = existing?.subEnabled ?: (currentSid != null)

                    val speed = if (existing != null && existing.speed != 1.0f) {
                        existing.speed
                    } else {
                        state.speed.toFloat()
                    }

                    val aspect = existing?.aspect ?: state.aspect

                    val updated = existing?.copy(
                        positionMs = pos,
                        durationMs = finalDuration,
                        audioTrackId = audioTrackId,
                        audioTrackTitle = audioTrackTitle,
                        audioTrackLang = audioTrackLang,
                        audioEnabled = audioEnabled,
                        subTrackId = subTrackId,
                        subTrackTitle = subTrackTitle,
                        subTrackLang = subTrackLang,
                        subEnabled = subEnabled,
                        speed = speed,
                        aspect = aspect,
                        lastPlayedAt = System.currentTimeMillis()
                    ) ?: FileSettings(
                        key = key,
                        positionMs = pos,
                        durationMs = finalDuration,
                        audioTrackId = audioTrackId,
                        audioTrackTitle = audioTrackTitle,
                        audioTrackLang = audioTrackLang,
                        audioEnabled = audioEnabled,
                        subTrackId = subTrackId,
                        subTrackTitle = subTrackTitle,
                        subTrackLang = subTrackLang,
                        subEnabled = subEnabled,
                        speed = speed,
                        aspect = aspect,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                    dao.upsert(updated)
                    Log.i("VP", "Saved position $pos ms (dur=$finalDuration ms) for key $key")
                }
            }
        }
    }

    private suspend fun savePositionSnapshot(key: String, positionMs: Long, state: PlayerState) {
        if (benchmarkRun) return
        withContext(Dispatchers.IO) {
            val existing = dao.getByKey(key)
            val duration = if (state.durationMs > 0L) state.durationMs else (existing?.durationMs ?: 0L)
            if (positionMs <= 0L && duration <= 0L) return@withContext
            val updated = existing?.copy(
                positionMs = positionMs,
                durationMs = duration,
                lastPlayedAt = System.currentTimeMillis()
            ) ?: FileSettings(
                key = key,
                positionMs = positionMs,
                durationMs = duration,
                lastPlayedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            Log.i("VP", "Saved leaving-file position $positionMs ms (dur=$duration ms) for key $key")
        }
    }

    private fun buildBenchmarkNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(BENCHMARK_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(BENCHMARK_CHANNEL_ID, "Benchmark", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Debug benchmark runs"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        return NotificationCompat.Builder(this, BENCHMARK_CHANNEL_ID)
            .setContentTitle("Benchmark run")
            .setSmallIcon(R.drawable.ic_pip_play)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Vibe Player playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private val contentPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // P7-02: these never change, so they are created once instead of on every notification update
    // (each is a binder call to the system server, and several updates happen while a file opens).
    private val prevIntent by lazy { servicePendingIntent(1, ACTION_PREVIOUS) }
    private val rewIntent by lazy { servicePendingIntent(2, ACTION_REWIND_10) }
    private val playPauseIntent by lazy { servicePendingIntent(3, ACTION_PLAY_PAUSE) }
    private val fwdIntent by lazy { servicePendingIntent(4, ACTION_FORWARD_10) }
    private val nextIntent by lazy { servicePendingIntent(5, ACTION_NEXT) }
    private val dismissIntent by lazy { servicePendingIntent(6, ACTION_DISMISS) }

    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val contentIntent = contentPendingIntent

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "Vibe Player" })
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.drawable.ic_pip_play)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(R.drawable.ic_pip_rewind, "Rewind 10s", rewIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(R.drawable.ic_pip_forward, "Forward 10s", fwdIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)

        val session = mediaSession
        if (session != null) {
            val mediaStyle = androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                .setShowActionsInCompactView(1, 2, 3) // Rewind 10s, Play/Pause, Forward 10s
            builder.setStyle(mediaStyle)
        }

        return builder.build()
    }

    fun updateNotification() {
        if (benchmarkRun) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val isPlaying = !playerEngine.state.value.paused
        val notif = buildNotification(currentVideoTitle.value, isPlaying)
        nm.notify(NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("VP", "PlaybackService onDestroy")
        periodicSaveJob?.cancel()
        stopCheckJob?.cancel()
        pausedTimeoutJob?.cancel()
        serviceScope.cancel()
        // Detach from the statics first so a service created right after cannot pick up this engine.
        val engine = activeEngine
        activeEngine = null
        activeService = null

        thermalManager.onPlaybackStopped()
        updateNoisyReceiver(false)
        abandonAudioFocus()


        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)

        mediaSession?.release()
        mediaSession = null
        playerAdapter?.release()
        playerAdapter = null

        // Final save reads the position from the engine, then releases it.
        if (engine != null) saveCurrentPosition(releaseWhenDone = engine)
    }
}
