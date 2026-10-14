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

class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "vibeplayer_playback"
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
                activeEngine ?: PlayerEngine().also { activeEngine = it }
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
                playerEngine.play()
            }
            FocusAction.NONE -> {}
        }
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

        // Ensure engine is instantiated
        getOrCreateEngine()

        playerEngine.onVulkanFailureListener = { format, reason ->
            val isGlobal = reason.contains("suitable GPU context", ignoreCase = true) ||
                reason.contains("pl_vulkan", ignoreCase = true) ||
                reason.contains("Found no suitable device", ignoreCase = true)
            VulkanDriverCache.recordVulkanFailure(applicationContext, format, isGlobal)
        }
        serviceScope.launch(Dispatchers.IO) {
            appSettingsRepository.migrateRendererDefaultIfNeeded()
        }

        // Shader presets (P7): the service owns the engine, so it owns the directory and the setting.
        playerEngine.setShaderDirectory(File(filesDir, "shaders").absolutePath)
        serviceScope.launch(Dispatchers.IO) { ensureShadersExtracted(this@PlaybackService) }
        serviceScope.launch {
            appSettingsRepository.appSettingsFlow
                .map { it.shaderPreset }
                .distinctUntilChanged()
                .collect { playerEngine.setShaderPreset(mapShaderPreset(it)) }
        }

        // Initialize Media3 SimpleBasePlayer adapter and MediaSession
        val adapter = VibePlayerAdapter(mainLooper, playerEngine, this)
        playerAdapter = adapter

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = androidx.media3.session.MediaSession.Builder(this, adapter)
            .setId("vibeplayer_session")
            .setSessionActivity(contentIntent)
            .build()

        // Initial foreground notification (type mediaPlayback)
        startForegroundCompat()

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
                adapter.notifyStateChanged()
                if (state.paused && !wasPaused) {
                    saveCurrentPosition()
                    updateNoisyReceiver(false)
                    if (!resumeOnFocusGain) {
                        abandonAudioFocus()
                    }
                } else if (!state.paused && wasPaused) {
                    // Playing again (user, notification, auto-advance): a pending transient-loss resume
                    // must not start playback later after the user paused again.
                    resumeOnFocusGain = false
                    val granted = requestAudioFocus()
                    if (!granted) {
                        Log.w("VP", "Audio focus denied -> pausing playback")
                        playerEngine.pause()
                    } else {
                        updateNoisyReceiver(true)
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
                adapter.notifyStateChanged()
                updateNotification()
            }
        }

        serviceScope.launch {
            playerEngine.position.collect {
                adapter.notifyStateChanged()
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

    private fun startForegroundCompat() {
        if (benchmarkRun) return // no notification during a benchmark
        val notif = buildNotification(currentVideoTitle.value, !playerEngine.state.value.paused)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            isForegroundService = true
        } catch (e: Exception) {
            Log.e("VP", "Failed to startForeground: ${e.message}", e)
        }
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
        // Every startForegroundService() must be followed by startForeground() or the app is killed;
        // a service that already left the foreground (task removed while still bound) is reused.
        if (intent?.action != ACTION_STOP && intent?.action != ACTION_DISMISS) startForegroundCompat()
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
            ACTION_STOP -> {
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
        }
        return START_NOT_STICKY
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
            val shouldBeInFg = PlaybackLifecyclePolicy.shouldBeInForeground(boundClients, state.paused)
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
            openVideo(target, playAfterLoad = true)
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
        val size = if (sizeBytes > 0L) {
            sizeBytes
        } else {
            val f = File(path)
            if (f.exists()) f.length() else 0L
        }
        return if (size > 0L) "$path:$size" else path
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
        shaderPreset: ShaderPreset? = null
    ) {
        val leavingKey = currentKey
        val leavingPath = currentVideoPath
        val leavingPos = playerEngine.position.value
        val leavingState = playerEngine.state.value
        if (!benchmarkRun && leavingKey != null && leavingPath != null && leavingPath != path) {
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

        updateNotification()

        serviceScope.launch(Dispatchers.IO) {
            val files = if (path.startsWith("/")) FolderPlaylist.list(path) else emptyList()
            val index = files.indexOf(File(path).absolutePath)
            val updated = Neighbours(previous = index > 0, next = index >= 0 && index < files.lastIndex)
            withContext(Dispatchers.Main.immediate) {
                if (currentVideoPath == path) _neighbours.value = updated
            }
        }
        val key = computeKey(identityKey ?: path, sizeBytes)

        loadJob = serviceScope.launch {
            val resolvedFolderKey = folderKey ?: (File(path).parent ?: "")
            val fileSettingsDeferred = async(Dispatchers.IO) {
                dao.getByKey(key) ?: dao.getByKey(path)
            }
            val folderSettingsDeferred = async(Dispatchers.IO) {
                if (resolvedFolderKey.isNotEmpty()) folderDao.getByPath(resolvedFolderKey) else null
            }
            val appSettingsDeferred = async(Dispatchers.IO) {
                appSettingsRepository.appSettingsFlow.first()
            }
            val formatDeferred = async(Dispatchers.IO) {
                VideoFormatDetector.detect(applicationContext, path, identityKey)
            }

            val fileSettings = fileSettingsDeferred.await()
            val folderSettings = folderSettingsDeferred.await()
            val appSettings = appSettingsDeferred.await()
            val detectedFormat = formatDeferred.await()

            if (generation != loadGeneration || currentVideoPath != path) return@launch

            val startPositionMs = if (startFromBeginning) {
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

            val libMpvChoices = playerEngine.availableGpuApis.ifEmpty { PlayerEngine.getGpuApiChoices() }
            val libMpvHasVulkan = libMpvChoices.any { it.equals("vulkan", ignoreCase = true) }
            val isVulkanAvailable = VulkanDriverCache.isVulkanAvailable(
                context = applicationContext,
                format = detectedFormat,
                libMpvHasVulkan = libMpvHasVulkan
            ) && !PlayerEngine.sessionVulkanFailed

            val resolvedConfig = FormatProfileTable.resolve(
                format = detectedFormat,
                mode = targetMode,
                rendererSetting = rendererSetting,
                isVulkanAvailableForFormat = isVulkanAvailable
            )
            playerEngine.setCurrentFormatProfile(resolvedConfig)

            Log.i("VP", "Picked configuration for ${detectedFormat.label}: ${resolvedConfig.chosenRowDescription} (reason: ${resolvedConfig.reason})")

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
            if (playerEngine.playbackConfig.mode != targetConfig.mode ||
                playerEngine.playbackConfig.renderer != targetConfig.renderer ||
                playerEngine.playbackConfig.vo != targetConfig.vo ||
                playerEngine.playbackConfig.hwdecOverride != targetConfig.hwdecOverride
            ) {
                playerEngine.reconfigure(targetConfig)
            }

            playerEngine.setShaderPreset(shaderPreset ?: mapShaderPreset(appSettings.shaderPreset))

            playerEngine.load(
                path = path,
                startPositionMs = startPositionMs,
                audioTrackId = if (audioEnabled) audioTrackId else null,
                audioEnabled = audioEnabled,
                subTrackId = if (subEnabled) subTrackId else null,
                subEnabled = subEnabled,
                speed = speed,
                aspect = aspect
            )
            currentKey = key
            if (playAfterLoad) playerEngine.play()
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
        val knownPos = engine.position.value
        val state = engine.state.value
        if (generation != loadGeneration || key != currentKey) { releaseWhenDone?.release(); return }
        saveScope.launch {
            val fresh = try {
                withTimeoutOrNull(1_000L) { engine.currentPositionMs() }
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

    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rewIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_REWIND_10),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fwdIntent = PendingIntent.getService(
            this, 4,
            Intent(this, PlaybackService::class.java).setAction(ACTION_FORWARD_10),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 5,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = PendingIntent.getService(
            this, 6,
            Intent(this, PlaybackService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
