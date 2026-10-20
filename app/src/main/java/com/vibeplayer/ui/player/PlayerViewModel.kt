package com.vibeplayer.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.playback.PlaybackService
import com.vibeplayer.player.PerfLog
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.PlaybackConfig
import com.vibeplayer.player.ShaderPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    data class Neighbours(val previous: Boolean = false, val next: Boolean = false)

    val appSettingsRepository = AppSettingsRepository(application)
    /** P3-09: hold speed from settings, collected on IO; read when a hold starts. */
    val holdSpeed: StateFlow<Double> = appSettingsRepository.appSettingsFlow
        .map { it.holdSpeed }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, HoldGesture.DEFAULT_SPEED)

    val gesturesEnabled: StateFlow<Boolean> = appSettingsRepository.appSettingsFlow
        .map { it.gesturesEnabled }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, true)

    val skipSeconds: StateFlow<Int> = appSettingsRepository.appSettingsFlow
        .map { it.skipSeconds }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, 10)

    val volumeBoostMax: StateFlow<Int> = appSettingsRepository.appSettingsFlow
        .map { it.volumeBoostMax }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, com.vibeplayer.player.VolumeBoost.NONE)

    val matchDisplayRefreshRate: StateFlow<Boolean> = appSettingsRepository.appSettingsFlow
        .map { it.matchDisplayRefreshRate }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, true)

    /**
     * Debug benchmark run: the service skips history writes and does not post a notification.
     * Process-wide, set by PlayerActivity before this ViewModel is created.
     */
    var isBenchmarkRun: Boolean
        get() = PlaybackService.benchmarkRun
        set(value) { PlaybackService.benchmarkRun = value }
    @Volatile private var backgroundAudioEnabled = false

    @Volatile private var service: PlaybackService? = null
    @Volatile private var isBound = false

    private data class PendingOpen(
        val path: String,
        val sizeBytes: Long,
        val identityKey: String?,
        val folderKey: String?,
        val startFromBeginning: Boolean,
        val playAfterLoad: Boolean,
        val title: String? = null,
        val playbackConfig: PlaybackConfig? = null,
        val shaderPreset: ShaderPreset? = null,
        val startPositionOverride: Long? = null
    )
    @Volatile private var pendingOpen: PendingOpen? = null

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _neighbours = MutableStateFlow(Neighbours())
    val neighbours: StateFlow<Neighbours> = _neighbours.asStateFlow()

    val playerEngine: PlayerEngine
        get() = service?.playerEngine ?: PlaybackService.getOrCreateEngine()

    private var serviceObservationJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = s
            Log.i("VP", "PlayerViewModel onServiceConnected")
            PerfLog.mark("service-connected")
            serviceObservationJob?.cancel()
            serviceObservationJob = viewModelScope.launch {
                launch {
                    s.currentPath.collect { _currentPath.value = it }
                }
                launch {
                    s.neighbours.collect { _neighbours.value = Neighbours(it.previous, it.next) }
                }
            }
            pendingOpen?.let { req ->
                pendingOpen = null
                s.openVideo(
                    req.path, req.sizeBytes, req.identityKey, req.folderKey,
                    req.startFromBeginning, req.playAfterLoad, req.title,
                    req.playbackConfig, req.shaderPreset, req.startPositionOverride
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("VP", "PlayerViewModel onServiceDisconnected")
            serviceObservationJob?.cancel()
            serviceObservationJob = null
            service = null
        }
    }

    init {
        // P7-02: open the Room database now (IO) so the first per-file/folder query in the service
        // does not pay for it after the service has connected.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.vibeplayer.data.db.AppDatabase.getInstance(application).openHelper.writableDatabase
            } catch (e: Exception) {
                Log.w("VP", "Room warm-up failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            appSettingsRepository.appSettingsFlow
                .map { it.backgroundAudio }
                .distinctUntilChanged()
                .collect { backgroundAudioEnabled = it }
        }

        val intent = Intent(application, PlaybackService::class.java)
        // A benchmark run only binds: startForegroundService would force a notification. The service
        // answers every startForegroundService with startForeground whatever the flag says later.
        if (com.vibeplayer.playback.ForegroundStartPolicy.shouldStartForegroundService(PlaybackService.benchmarkRun)) {
            try {
                ContextCompat.startForegroundService(application, intent)
            } catch (e: Exception) {
                Log.w("VP", "Failed to startForegroundService: ${e.message}")
            }
        }
        try {
            isBound = application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i("VP", "bindService result: $isBound")
        } catch (e: Exception) {
            Log.e("VP", "Failed to bindService: ${e.message}", e)
        }
    }

    fun isBackgroundAudioEnabled(): Boolean =
        service?.isBackgroundAudioEnabled() ?: backgroundAudioEnabled

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
        startPositionOverride: Long? = null
    ) {
        _currentPath.value = path
        val active = service ?: PlaybackService.currentInstance
        if (active != null) {
            active.openVideo(path, sizeBytes, identityKey, folderKey, startFromBeginning, playAfterLoad, title, playbackConfig, shaderPreset, startPositionOverride)
        } else {
            pendingOpen = PendingOpen(path, sizeBytes, identityKey, folderKey, startFromBeginning, playAfterLoad, title, playbackConfig, shaderPreset, startPositionOverride)
        }
    }

    /** Quality/efficient switch (and renderer re-resolve) on the service-owned engine. */
    fun togglePlaybackMode() {
        val engine = playerEngine
        // IO: settings, gpu-api list and the Vulkan driver cache. The user's renderer setting
        // (e.g. OpenGL ES) and remembered per-format Vulkan failures must hold for the toggle too.
        viewModelScope.launch(Dispatchers.IO) {
            val setting = when (appSettingsRepository.appSettingsFlow.first().renderer) {
                com.vibeplayer.data.prefs.RendererOption.AUTO -> com.vibeplayer.player.RendererSetting.AUTO
                com.vibeplayer.data.prefs.RendererOption.VULKAN -> com.vibeplayer.player.RendererSetting.VULKAN
                com.vibeplayer.data.prefs.RendererOption.OPENGL -> com.vibeplayer.player.RendererSetting.GLES
            }
            val libMpvHasVulkan = engine.availableGpuApis.ifEmpty { PlayerEngine.getGpuApiChoices() }
                .any { it.equals("vulkan", ignoreCase = true) }
            val vulkanAvailable = com.vibeplayer.playback.VulkanDriverCache.isVulkanAvailable(
                getApplication(), engine.currentFormat, libMpvHasVulkan
            )
            engine.togglePlaybackMode(setting, vulkanAvailable)
        }
    }

    fun skipToNeighbour(forward: Boolean) {
        (service ?: PlaybackService.currentInstance)?.skipToNeighbour(forward)
    }

    fun setAudioTrack(trackId: Int?) {
        (service ?: PlaybackService.currentInstance)?.setAudioTrack(trackId)
    }

    fun setSubtitleTrack(trackId: Int?) {
        (service ?: PlaybackService.currentInstance)?.setSubtitleTrack(trackId)
    }

    fun loadSubtitleFile(uri: Uri) {
        (service ?: PlaybackService.currentInstance)?.loadSubtitleFile(uri)
    }

    fun setSpeed(speed: Double) {
        (service ?: PlaybackService.currentInstance)?.setSpeed(speed)
    }

    fun persistSpeed(speed: Double) {
        (service ?: PlaybackService.currentInstance)?.persistSpeed(speed)
    }

    fun setSubDelay(ms: Int) {
        (service ?: PlaybackService.currentInstance)?.setSubDelay(ms)
    }

    fun setAudioDelay(ms: Int) {
        (service ?: PlaybackService.currentInstance)?.setAudioDelay(ms)
    }

    fun setAspect(aspect: String?) {
        (service ?: PlaybackService.currentInstance)?.setAspect(aspect)
    }

    fun saveCurrentPosition() {
        (service ?: PlaybackService.currentInstance)?.saveCurrentPosition()
    }

    override fun onCleared() {
        Log.i("VP", "PlayerViewModel onCleared")
        serviceObservationJob?.cancel()
        serviceObservationJob = null
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w("VP", "Failed to unbindService: ${e.message}")
            }
            isBound = false
        }
        service = null
        super.onCleared()
    }
}
