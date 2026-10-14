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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vibeplayer.R
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.db.FolderSettings
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.data.prefs.SubtitlePreferenceMode
import com.vibeplayer.player.FileTrackChoice
import com.vibeplayer.player.FolderTrackChoice
import com.vibeplayer.player.LanguagePreferences
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.PlayerState
import com.vibeplayer.player.SubtitleMode
import com.vibeplayer.player.TrackSelector
import com.vibeplayer.ui.player.FolderPlaylist
import com.vibeplayer.ui.player.PlayerActivity
import com.vibeplayer.ui.player.PlayerViewModel.Neighbours
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

        @Volatile
        private var activeService: PlaybackService? = null
        val currentInstance: PlaybackService? get() = activeService

        @Volatile
        private var activeEngine: PlayerEngine? = null

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
    val playerEngine: PlayerEngine get() = getOrCreateEngine()

    private var playerAdapter: VibePlayerAdapter? = null
    private var mediaSession: androidx.media3.session.MediaSession? = null

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var hasAudioFocus = false
    private var isNoisyReceiverRegistered = false

    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i("VP", "onAudioFocusChange: focusChange=$focusChange")
        val isPlaying = !playerEngine.state.value.paused
        when (AudioFocusPolicy.handleFocusChange(focusChange, isPlaying, resumeOnFocusGain)) {
            FocusAction.PAUSE_PERMANENT -> {
                resumeOnFocusGain = false
                playerEngine.pause()
            }
            FocusAction.PAUSE_TRANSIENT -> {
                resumeOnFocusGain = true
                playerEngine.pause()
            }
            FocusAction.RESUME -> {
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
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED
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
        val initialNotif = buildNotification(currentVideoTitle.value, !playerEngine.state.value.paused)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            Log.e("VP", "Failed to startForeground: ${e.message}", e)
        }

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
                    val granted = requestAudioFocus()
                    if (!granted) {
                        Log.w("VP", "Audio focus denied -> pausing playback")
                        playerEngine.pause()
                    } else {
                        updateNoisyReceiver(true)
                    }
                }
                if (state.paused != wasPaused) {
                    updateNotification()
                    checkShouldStop()
                }
                wasPaused = state.paused
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("VP", "PlaybackService onStartCommand (action=${intent?.action})")
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                resumeOnFocusGain = false
                playerEngine.togglePause()
            }
            ACTION_REWIND_10 -> playerEngine.seekRelative(-10.0, exact = true)
            ACTION_FORWARD_10 -> playerEngine.seekRelative(10.0, exact = true)
            ACTION_PREVIOUS -> skipToNeighbour(forward = false)
            ACTION_NEXT -> skipToNeighbour(forward = true)
            ACTION_STOP -> {
                resumeOnFocusGain = false
                playerEngine.pause()
                saveCurrentPosition()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        Log.i("VP", "PlaybackService onBind, boundClients=$boundClients")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients--
        Log.i("VP", "PlaybackService onUnbind, boundClients=$boundClients")
        checkShouldStop()
        return true // allow rebind
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        boundClients++
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
            saveCurrentPosition()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun isBackgroundAudioEnabled(): Boolean = backgroundAudioEnabled

    fun checkShouldStop() {
        val state = playerEngine.state.value
        val shouldStop = PlaybackLifecyclePolicy.shouldStopService(
            boundClients = boundClients,
            backgroundAudioEnabled = backgroundAudioEnabled,
            isPaused = state.paused,
            isEof = state.eof
        )
        if (shouldStop) {
            Log.i("VP", "PlaybackService stopping itself: boundClients=$boundClients, bgAudio=$backgroundAudioEnabled, paused=${state.paused}, eof=${state.eof}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        playAfterLoad: Boolean = false
    ) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        currentVideoPath = path
        currentFolderPath = folderKey ?: File(path).parent
        _currentPath.value = path
        val title = if (path.startsWith("/")) File(path).name else (identityKey ?: "Video")
        _currentVideoTitle.value = title
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
            val fileSettings = withContext(Dispatchers.IO) {
                dao.getByKey(key) ?: dao.getByKey(path)
            }
            val folderSettings = withContext(Dispatchers.IO) {
                if (resolvedFolderKey.isNotEmpty()) folderDao.getByPath(resolvedFolderKey) else null
            }
            val appSettings = appSettingsRepository.appSettingsFlow.first()
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

    fun saveCurrentPosition() {
        val generation = loadGeneration
        val key = currentKey ?: return
        val knownPos = playerEngine.position.value
        val state = playerEngine.state.value
        if (generation != loadGeneration || key != currentKey) return
        saveScope.launch {
            val fresh = try {
                withTimeoutOrNull(1_000L) { playerEngine.currentPositionMs() }
            } catch (_: Exception) {
                null
            }
            val pos = if (generation == loadGeneration && key == currentKey) {
                fresh ?: playerEngine.position.value
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "Vibe Player" })
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.drawable.ic_pip_play)
            .setContentIntent(contentIntent)
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val isPlaying = !playerEngine.state.value.paused
        val notif = buildNotification(currentVideoTitle.value, isPlaying)
        nm.notify(NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("VP", "PlaybackService onDestroy")
        periodicSaveJob?.cancel()
        serviceScope.cancel()
        saveCurrentPosition()

        updateNoisyReceiver(false)
        abandonAudioFocus()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)

        mediaSession?.release()
        mediaSession = null
        playerAdapter?.release()
        playerAdapter = null

        activeEngine?.release()
        activeEngine = null
        activeService = null
    }
}
