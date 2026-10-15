package com.vibeplayer.player

import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class PlayerEngine(
    initialConfig: PlaybackConfig = PlaybackConfig(),
    /**
     * P7-02: the service-owned engine starts creating the mpv handle immediately but waits (on its own
     * command thread, at most [INITIAL_CONFIG_WAIT_MS]) for [provideInitialConfig], so the handle is
     * built once with the right mode/renderer instead of being rebuilt by [reconfigure].
     */
    private val waitForInitialConfig: Boolean = false
) {

    private fun newCommandExecutor(): CommandExecutor =
        CommandExecutor("VP-Command") { android.os.SystemClock.elapsedRealtime() }

    // Replaced as a set when a stuck handle is abandoned (B-08): the old thread never returns
    // from its blocked mpv call, so it cannot be reused.
    @Volatile private var commandExecutor: CommandExecutor = newCommandExecutor()
    @Volatile private var commandDispatcher = commandExecutor.asCoroutineDispatcher()
    @Volatile private var scope = CoroutineScope(commandDispatcher + SupervisorJob())
    @Volatile private var released = false
    @Volatile private var timePosObserved = true

    private val initialConfigLatch = java.util.concurrent.CountDownLatch(1)
    private var initialConfigOverride: PlaybackConfig? = null
    private var initStarted = false
    private val initialisedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val _firstFrameReady = MutableStateFlow(false)
    /** True after the first playback-restart of this engine (first frame is up); never reset. */
    val firstFrameReady: StateFlow<Boolean> = _firstFrameReady.asStateFlow()

    /** Completes when the mpv handle has been created and initialised (also if that failed). */
    suspend fun awaitInitialised() = initialisedSignal.await()

    /**
     * Supplies the config for the first mpv handle. Returns false when the handle was already created
     * (the caller then falls back to [reconfigure]). Only has an effect on engines created with
     * waitForInitialConfig.
     */
    fun provideInitialConfig(config: PlaybackConfig): Boolean = synchronized(initialConfigLatch) {
        if (initStarted) return false
        initialConfigOverride = config
        initialConfigLatch.countDown()
        true
    }

    private fun awaitInitialConfig() {
        if (waitForInitialConfig) {
            try {
                initialConfigLatch.await(INITIAL_CONFIG_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        synchronized(initialConfigLatch) {
            initStarted = true
            initialConfigOverride?.let { playbackConfig = it }
        }
    }

    private fun executeCommand(block: () -> Unit) {
        if (released) return
        try {
            commandExecutor.execute { if (!released) block() }
        } catch (_: RejectedExecutionException) {
            // Event callbacks and Compose disposal can race with release().
        }
    }

    @Volatile private var handle: Long = 0L
    /** P3-09 temporary speed (hold to fast-forward). Never part of [PlayerState.speed], never persisted. */
    @Volatile private var speedOverride: Double? = null
    @Volatile var playbackConfig: PlaybackConfig = initialConfig
        private set
    @Volatile private var currentFilePath: String = ""
    /** Identity of [currentFilePath] (see [LoadRequest.identity]). */
    @Volatile private var currentFileIdentity: String = ""
    /**
     * Resolves a fresh `fdclose://N` path for a content:// identity, so a file opened through a
     * descriptor can be loaded again on a new handle. Blocking; called on the IO dispatcher only.
     * Installed by the owner of the engine (PlaybackService, which holds the ContentResolver).
     */
    @Volatile var pathReopener: ((identity: String) -> String?)? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var shaderDirectory: String = ""
    @Volatile private var currentShaderPreset: ShaderPreset = ShaderPreset.NONE
    @Volatile private var savedVid: String? = null
    /** P4-13 global subtitle appearance; applied to every loadfile and live when changed. */
    @Volatile private var subtitleStyle = SubtitleStyle()

    // ---- B-08 safety net state ----
    private data class LoadRequest(
        val path: String,
        val startPositionMs: Long,
        val audioTrackId: Int?,
        val audioEnabled: Boolean,
        val subTrackId: Int?,
        val subEnabled: Boolean,
        val speed: Double,
        val aspect: String?,
        val subDelayMs: Int = 0,
        val audioDelayMs: Int = 0,
        /** Stable identity of the file (its content:// URI for an fdclose:// path); defaults to the path. */
        val identity: String = path,
        /** Format detection result (frame rate, size) for the MediaCodec operating-rate hint (P6-08 fix). */
        val clip: ClipInfo? = null
    )
    @Volatile private var lastLoad: LoadRequest? = null
    private data class PendingSubtitle(
        val fdPath: String,
        val title: String,
        val select: Boolean = true
    )
    @Volatile private var pendingSubtitle: PendingSubtitle? = null
    private val loadSerial = AtomicInteger(0)
    /** Highest load serial whose loadfile command has been issued. */
    @Volatile private var loadIssuedSerial = 0
    /** Serial of the newest [load] call (reloads by reconfigure and B-08 take serials too). */
    @Volatile private var latestOpenSerial = 0
    /** Load serial for which a stuck handle was abandoned by [pendingLoadCheck]. */
    @Volatile private var abandonedForSerial = -1
    /**
     * Latest config and track selection asked for, recorded on the caller's thread: their queued
     * tasks are dropped with the queue when a stuck handle is abandoned, and the new handle needs them.
     */
    @Volatile private var requestedConfig: PlaybackConfig? = null
    @Volatile private var requestedTrackSelection: PendingTrackSelection? = null
    @Volatile private var restartSeenSerial = -1
    /** Events from an abandoned handle carry an old token and are dropped. */
    @Volatile private var handleToken = 0
    /**
     * Identity of the file that was reloaded with software decoding after a stall. Survives
     * reconfigures of that file (quality toggle, Vulkan/GLES fallback, a fresh fdclose:// descriptor);
     * hardware decoding returns, and the one-reload budget resets, when another file loads.
     */
    @Volatile private var softwareRecoveryIdentity: String? = null
    /** hwdec=no was set on the current handle for [softwareRecoveryIdentity] (a new handle starts without). */
    @Volatile private var hwdecForcedSoftware = false
    /**
     * The `hwdec` value last set on the current mpv handle (option table at init, [setHwdec], B-08), which
     * mpv decodes the next file with; the P6-08 operating-rate hint is decided from it, not from the
     * requested config ([setHwdec] changes the handle only).
     */
    @Volatile private var handleHwdec: String? = null
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "VP-Watchdog").apply { isDaemon = true }
    }
    /** Debug builds: simulate one stalled open (intent extra `debug_stall`). */
    @Volatile var debugSimulateStallOnce = false

    private val _surfaceEpoch = MutableStateFlow(0)
    /** Bumped when the UI must create a fresh Surface (the old one stays tied to an abandoned handle). */
    val surfaceEpoch: StateFlow<Int> = _surfaceEpoch.asStateFlow()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _stats = MutableStateFlow(PlayerStats())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    private val _onScreenMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val onScreenMessage: kotlinx.coroutines.flow.SharedFlow<String> = _onScreenMessage

    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    fun setIsInPip(inPip: Boolean) {
        if (_isInPip.value != inPip) {
            _isInPip.value = inPip
            if (firstFrameReady.value) {
                emitCheckLog(force = true)
            }
        }
    }

    private val checkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var lastCheckSnapshot: String = ""
    @Volatile private var lastCheckLogTimeMs: Long = 0L

    fun emitCheckLog(force: Boolean = false) {
        val s = _state.value
        val pos = _position.value
        val dur = s.durationMs
        val stateStr = when {
            s.eof -> "ended"
            s.paused -> "paused"
            dur > 0L || pos > 0L -> "playing"
            else -> "idle"
        }
        val hwdec = s.hwdecCurrent
        // playbackConfig.vo includes the per-format voOverride (P6-07: H.264 runs gpu-next in Efficient mode)
        val vo = _stats.value.vo.ifEmpty { playbackConfig.vo }
        val speed = s.speed
        val aspect = s.aspect ?: "fit"
        val aid = s.selectedAudioTrackId?.toString() ?: "no"
        val sid = s.selectedSubTrackId?.toString() ?: "no"
        val path = currentFilePath
        val pip = _isInPip.value
        val renderer = playbackConfig.renderer.name.lowercase()

        val stateSnapshot = "$stateStr|$dur|$hwdec|$vo|$speed|$aspect|$aid|$sid|$path|$pip|$renderer"
        val now = System.currentTimeMillis()
        val stateChanged = stateSnapshot != lastCheckSnapshot
        val timeDue = (now - lastCheckLogTimeMs) >= 2000L

        if (force || stateChanged || (stateStr == "playing" && timeDue)) {
            lastCheckSnapshot = stateSnapshot
            lastCheckLogTimeMs = now
            Log.i("VP-Check", "state=$stateStr pos_ms=$pos dur_ms=$dur hwdec=$hwdec vo=$vo speed=$speed aspect=$aspect aid=$aid sid=$sid path=$path pip=$pip renderer=$renderer")
        }
    }

    @Volatile private var pendingPauseOnLoad: Boolean = false
    @Volatile var lastVulkanErrorText: String? = null
    @Volatile var availableGpuApis: List<String> = emptyList()
    @Volatile private var lastPlaybackRestartMs: Long = 0L
    private var fallbackCheckJob: Job? = null
    @Volatile var currentFormat: VideoFormat = VideoFormat.OTHER
    @Volatile var currentResolvedConfig: ResolvedConfiguration? = null
    /** Told about every Vulkan fallback; [VulkanFailure.scope] says whether the whole driver failed. */
    @Volatile var onVulkanFailureListener: ((format: VideoFormat?, failure: VulkanFailure) -> Unit)? = null
    /** Wall clock of the current handle's creation: the log window for classifying a Vulkan failure. */
    @Volatile private var handleCreatedAtMs = 0L
    /** Debug builds: simulate one forced Vulkan failure. */
    @Volatile var debugForceVulkanFailureOnce = false
    /** Debug builds: simulate one forced OpenGL ES failure. */
    @Volatile var debugForceGlesFailureOnce = false

    companion object {
        private const val DETACH_TIMEOUT_MS = 1500L
        private const val INITIAL_CONFIG_WAIT_MS = 600L
        /** Position reads for skip/save: a stalled core never answers. */
        const val POSITION_READ_TIMEOUT_MS = 1_000L
        const val FALLBACK_GRACE_PERIOD_MS = 1000L
        @Volatile var sessionVulkanFailed: Boolean = false
        @Volatile var cachedGpuApis: List<String> = emptyList()

        fun computeFallback(
            hwdec: String,
            codec: String?,
            durationMs: Long,
            hasFilePath: Boolean = true,
            playbackRestartMs: Long = 0L,
            nowMs: Long = 0L,
            minTimeSinceRestartMs: Long = FALLBACK_GRACE_PERIOD_MS,
            alreadyFallback: Boolean = false,
            /** Software decoding was chosen (format table row such as H.264 10-bit, or decoder=software). */
            softwareByDesign: Boolean = false
        ): Boolean {
            if (softwareByDesign) return false
            if (!hasFilePath || durationMs <= 0 || codec.isNullOrEmpty()) {
                return false
            }
            val hwdecCodecs = setOf("h264", "hevc", "vp9")
            val isHwdecCodec = hwdecCodecs.any { codec.lowercase().contains(it) }
            if (!isHwdecCodec) return false
            val isNo = hwdec == "no" || hwdec.isEmpty()
            if (!isNo) return false
            if (alreadyFallback) return true
            if (playbackRestartMs <= 0L) return false
            return (nowMs - playbackRestartMs) >= minTimeSinceRestartMs
        }

        /**
         * Whether a Vulkan VO that did not come up within the check window counts as a Vulkan
         * failure. Not before the file has loaded (a surface attached while a slow open is still
         * pending), not without a surface, and not when the file plays no video (audio-only, vid=no).
         */
        fun isVulkanVoFailure(fileLoaded: Boolean, surfaceAttached: Boolean, vid: String?, voConfigured: Boolean): Boolean =
            fileLoaded && surfaceAttached && !voConfigured && !vid.isNullOrEmpty() && vid != "no"

        /** Closes the descriptor of an `fdclose://N` path that mpv never got to own. */
        fun closeFdPath(path: String) {
            val fd = ReloadPolicy.fdOf(path) ?: return
            try {
                android.os.ParcelFileDescriptor.adoptFd(fd).close()
            } catch (e: Exception) {
                Log.w("VP", "closing $path failed: ${e.message}")
            }
        }

        fun getGpuApiChoices(): List<String> {
            if (cachedGpuApis.isNotEmpty()) return cachedGpuApis
            try {
                val h = MpvNative.create(false)
                if (h != 0L) {
                    val raw = MpvNative.getPropertyString(h, "option-info/gpu-api/choices")
                    MpvNative.destroy(h)
                    val parsed = RendererResolver.parseGpuApiChoices(raw)
                    if (parsed.isNotEmpty()) {
                        cachedGpuApis = parsed
                        return parsed
                    }
                }
            } catch (e: Throwable) {
                Log.w("VP", "Could not query mpv gpu-api choices: ${e.message}")
            }
            return listOf("opengl", "vulkan")
        }

        const val REPLY_PAUSE = 1L
        const val REPLY_DURATION = 2L
        const val REPLY_TIME_POS = 3L
        const val REPLY_HWDEC = 4L
        const val REPLY_EOF = 5L
        const val REPLY_BUFFERING = 6L
        const val REPLY_SPEED = 7L
        const val REPLY_DWIDTH = 8L
        const val REPLY_DHEIGHT = 9L
        const val REPLY_VIDEO_CODEC = 10L
        const val REPLY_VIDEO_FORMAT = 11L
        const val REPLY_AID = 12L
        const val REPLY_SID = 13L
    }

    private val eventCallback = object : MpvNative.EventCallback {
        override fun onPropertyChange(name: String, value: Any?) {
            if (BuildConfig.DEBUG && name != "time-pos") {
                Log.i("VP", "onPropertyChange: $name = $value")
            }
            when (name) {
                "pause" -> {
                    val paused = when (value) {
                        is Boolean -> value
                        is Number -> value.toLong() != 0L
                        is String -> value == "yes"
                        else -> false
                    }
                    _state.update { it.copy(paused = paused) }
                }
                "duration" -> {
                    val durationMs = when (value) {
                        is Number -> (value.toDouble() * 1000).toLong()
                        is String -> (value.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                        else -> 0L
                    }
                    _state.update { current ->
                        val fallback = computeFallback(
                            hwdec = current.hwdecCurrent,
                            codec = current.videoCodec,
                            durationMs = durationMs
                        )
                        current.copy(durationMs = durationMs, fallback = fallback)
                    }
                    if (_state.value.hwdecCurrent == "no" || _state.value.hwdecCurrent.isEmpty()) {
                        scheduleFallbackCheck()
                    }
                }
                "time-pos" -> {
                    val posMs = when (value) {
                        is Number -> (value.toDouble() * 1000).toLong()
                        is String -> (value.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                        else -> 0L
                    }
                    _position.value = posMs
                }
                "hwdec-current" -> {
                    val hwdec = (value as? String) ?: "no"
                    _state.update { current ->
                        val fallback = computeFallback(
                            hwdec = hwdec,
                            codec = current.videoCodec,
                            durationMs = current.durationMs
                        )
                        current.copy(hwdecCurrent = hwdec, fallback = fallback)
                    }
                    if (hwdec == "no" || hwdec.isEmpty()) {
                        scheduleFallbackCheck()
                    } else {
                        fallbackCheckJob?.cancel()
                    }
                }
                "video-codec", "video-format" -> {
                    val codec = value as? String
                    _state.update { current ->
                        val fallback = computeFallback(
                            hwdec = current.hwdecCurrent,
                            codec = codec,
                            durationMs = current.durationMs
                        )
                        current.copy(videoCodec = codec, fallback = fallback)
                    }
                    if (_state.value.hwdecCurrent == "no" || _state.value.hwdecCurrent.isEmpty()) {
                        scheduleFallbackCheck()
                    }
                }
                "eof-reached" -> {
                    val eof = when (value) {
                        is Boolean -> value
                        is String -> value == "yes"
                        else -> false
                    }
                    _state.update { it.copy(eof = eof) }
                }
                "paused-for-cache" -> {
                    val buffering = when (value) {
                        is Boolean -> value
                        is String -> value == "yes"
                        else -> false
                    }
                    _state.update { it.copy(buffering = buffering) }
                }
                "speed" -> {
                    // P3-09: while a hold override is active mpv reports the override; state.speed stays the file's own.
                    if (speedOverride != null) return
                    val spd = (value as? Number)?.toDouble() ?: 1.0
                    _state.update { it.copy(speed = spd) }
                }
                "dwidth" -> {
                    val w = (value as? Number)?.toInt() ?: 0
                    _state.update { it.copy(videoWidth = w) }
                }
                "dheight" -> {
                    val h = (value as? Number)?.toInt() ?: 0
                    _state.update { it.copy(videoHeight = h) }
                }
                "aid" -> {
                    val aid = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    _state.update { it.copy(selectedAudioTrackId = aid) }
                    updateTracks()
                }
                "sid" -> {
                    val sid = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    _state.update { it.copy(selectedSubTrackId = sid) }
                    updateTracks()
                }
            }
        }

        override fun onEvent(id: Int) {
            when (id) {
                MpvEvent.PLAYBACK_RESTART -> {
                    if (ignoreRestartSerial == loadSerial.get()) return
                    restartSeenSerial = loadSerial.get()
                    lastPlaybackRestartMs = System.currentTimeMillis()
                    PerfLog.mark("playback-restart")
                    _firstFrameReady.value = true
                    scheduleFallbackCheck()
                }
                MpvEvent.END_FILE -> {
                    logStats()
                    Log.i("VP", "mpv event end-file")
                }
                MpvEvent.FILE_LOADED -> {
                    fileLoaded = true
                    PerfLog.mark("file-loaded")
                    Log.i("VP", "mpv event file-loaded")
                    if (!pendingPauseOnLoad) {
                        _state.update { it.copy(paused = false) }
                    }
                    pendingPauseOnLoad = false
                    armStallWatchdog()
                    if (BuildConfig.DEBUG && debugSimulateStallOnce) {
                        debugSimulateStallOnce = false
                        simulateStall()
                    }
                    executeCommand {
                        assertCommandThread()
                        val pending = pendingSubtitle
                        if (pending != null) {
                            pendingSubtitle = null
                            if (handle != 0L) {
                                val cmd = SubtitleCommands.buildSubAddArgs(pending.fdPath, pending.title, pending.select)
                                val res = MpvNative.command(handle, cmd)
                                Log.i("VP", "Applied pending subtitle on FILE_LOADED: path=${pending.fdPath}, title=${pending.title}, select=${pending.select}, res=$res")
                            } else {
                                closeFdPath(pending.fdPath)
                            }
                        }
                    }
                    updateTracks(allowPendingSelection = true)
                    checkVulkanVoInitialization()
                    checkGlesInitialization()
                }
                MpvEvent.TRACKS_CHANGED -> {
                    Log.i("VP", "mpv event tracks-changed")
                    updateTracks()
                }
                MpvEvent.TRACK_SWITCHED -> {
                    Log.i("VP", "mpv event track-switched")
                    updateTracks()
                }
                else -> {
                    Log.d("VP", "onEvent: $id")
                }
            }
        }
    }

    private inner class GatedCallback(private val token: Int) : MpvNative.EventCallback {
        override fun onPropertyChange(name: String, value: Any?) {
            if (token == handleToken) eventCallback.onPropertyChange(name, value)
        }

        override fun onEvent(id: Int) {
            if (token == handleToken) eventCallback.onEvent(id)
        }
    }

    @Volatile private var ignoreRestartSerial = -1

    private fun armStallWatchdog() {
        val serial = loadSerial.get()
        val armedAt = android.os.SystemClock.elapsedRealtime()
        scheduleStallCheck(serial, armedAt, StallPolicy.FIRST_CHECK_MS)
    }

    private fun scheduleStallCheck(serial: Int, armedAt: Long, delayMs: Long) {
        try {
            watchdog.schedule({ stallCheck(serial, armedAt) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // released
        }
    }

    /** Runs on the watchdog thread; never on main and never on the command thread. */
    private fun stallCheck(serial: Int, armedAt: Long) {
        val facts = StallPolicy.Facts(
            released = released,
            sameLoad = serial == loadSerial.get(),
            playbackRestartSeen = restartSeenSerial == serial,
            surfaceAttached = attachedSurface != null,
            alreadyRecovered = StallPolicy.softwareAfterStall(softwareRecoveryIdentity, lastLoad?.identity),
            elapsedSinceFileLoadedMs = android.os.SystemClock.elapsedRealtime() - armedAt,
            vid = if (savedVid != null || attachedSurface == null) "no" else null
        )
        if (StallPolicy.onTimer(facts) != StallPolicy.Action.PROBE) return
        // Liveness probe: a stuck core never answers a synchronous property read, and the command
        // thread is normally already blocked in one (updateTracks), so this task does not even start.
        val probed = commandExecutor
        val answered = try {
            val probe = probed.submit {
                if (handle != 0L) MpvNative.getPropertyString(handle, "hwdec-current")
            }
            probe.get(StallPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: Exception) {
            return
        }
        if (released || serial != loadSerial.get() || restartSeenSerial == serial) return
        val busyMs = probed.busyForMs()
        if (!StallPolicy.shouldRecover(answered, busyMs)) {
            if (!answered) Log.i("VP", "B-08 probe: queued behind a task busy for only $busyMs ms (e.g. a Vulkan fallback reconfigure); looking again")
            scheduleStallCheck(serial, armedAt, StallPolicy.RECHECK_MS)
            return
        }
        val request = lastLoad ?: return
        Log.e("VP", "B-08 watchdog: no playback-restart ${facts.elapsedSinceFileLoadedMs} ms after file-loaded and the mpv core does not answer (hwdec-current=${_state.value.hwdecCurrent}, command thread busy $busyMs ms); abandoning the handle and reloading ${request.path} with software decoding at ${request.startPositionMs} ms")
        abandonAndReloadInSoftware(request, probed)
    }

    private fun abandonAndReloadInSoftware(request: LoadRequest, stuck: CommandExecutor) {
        abandonStuckHandle(stuck, "B-08 recovery") {
            softwareRecoveryIdentity = request.identity
            Log.w("VP", "B-08 recovery: ${request.identity} is decoded in software from now on")
            // An fdclose:// file gets a fresh descriptor: the stuck handle keeps the old one (and a
            // simulated stall closes it when its handle is destroyed).
            reloadOnNewHandle(request, loadSerial.incrementAndGet(), paused = false, why = "B-08 recovery", reuseOpenFd = true)
        }
    }

    /**
     * Leaves the handle whose command thread [stuck] is blocked inside mpv and builds a new handle
     * on a new command thread, then runs [reload] there. Returns false when [stuck] is no longer the
     * current command thread (the other path already abandoned it).
     */
    private fun abandonStuckHandle(stuck: CommandExecutor, why: String, reload: () -> Unit): Boolean {
        synchronized(this) {
            if (released || commandExecutor !== stuck) return false
            val oldScope = scope
            // From here on nothing touches the stuck handle again: its command thread is blocked inside
            // mpv and destroying it would block as well. It is leaked until the process ends.
            handleToken++
            // Nothing may use the stuck handle from here on (a task queued on the new thread, release()).
            handle = 0L
            val executor = newCommandExecutor()
            // Queued before the executor is published: a task another thread queues meanwhile
            // (detachSurface, a position read, a load) must not run before the new handle exists.
            executor.execute {
                if (released) return@execute
                attachedSurface = null
                requestedConfig?.let { playbackConfig = it }
                // Like reconfigure: a hold ends with the handle, an audio-focus duck carries over.
                speedOverride = null
                if (!initHandle()) return@execute
                if (isDucked && handle != 0L) {
                    MpvNative.setPropertyDouble(handle, "volume", (preDuckVolume ?: 100.0) * 0.3)
                }
                Log.w("VP", "$why: new mpv handle (${if (handle != 0L) "ok" else "failed"}) with ${playbackConfig.mode}/${playbackConfig.renderer}; asking the UI for a fresh surface")
                // The old Surface still belongs to the abandoned handle's video output.
                _surfaceEpoch.update { it + 1 }
                reload()
            }
            val dispatcher = executor.asCoroutineDispatcher()
            commandExecutor = executor
            commandDispatcher = dispatcher
            scope = CoroutineScope(dispatcher + SupervisorJob())
            oldScope.coroutineContext[Job]?.cancel()
            // Drops everything queued behind the stuck task; the caller's latest wishes are in
            // requestedConfig / requestedTrackSelection and the load requests.
            stuck.shutdownNowClosingFds(::closeFdPath)
            statsJob = null
        }
        return true
    }

    private fun schedulePendingLoadCheck(serial: Int, queuedAt: Long, delayMs: Long) {
        try {
            watchdog.schedule({ pendingLoadCheck(serial, queuedAt) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // released
        }
    }

    /** Watchdog thread: a load still queued behind a stuck command thread abandons that handle. */
    private fun pendingLoadCheck(serial: Int, queuedAt: Long) {
        val stuck = commandExecutor
        val busyMs = stuck.busyForMs()
        val action = StallPolicy.onPendingLoad(StallPolicy.PendingLoad(
            released = released,
            sameLoad = serial == loadSerial.get(),
            issued = loadIssuedSerial >= serial,
            alreadyAbandoned = abandonedForSerial == serial,
            commandBusyMs = busyMs,
            waitedMs = android.os.SystemClock.elapsedRealtime() - queuedAt
        ))
        when (action) {
            StallPolicy.PendingLoadAction.NONE -> return
            StallPolicy.PendingLoadAction.RECHECK -> {
                schedulePendingLoadCheck(serial, queuedAt, StallPolicy.LOAD_RECHECK_MS)
                return
            }
            StallPolicy.PendingLoadAction.ABANDON -> Unit
        }
        val request = lastLoad ?: return
        abandonedForSerial = serial
        Log.e("VP", "B-08 watchdog: new open of ${request.path} is queued behind a command thread blocked for $busyMs ms; abandoning the stuck handle and opening it on a new one")
        abandonStuckHandle(stuck, "B-08 new open") {
            // The newest open wins (a later one may have been dropped with the old queue). Its
            // fdclose:// descriptor was never given to mpv, so it is loaded as it is.
            val latestSerial = loadSerial.get()
            val latest = lastLoad ?: request
            pendingTrackSelection = requestedTrackSelection
            lastLoad = latest
            loadRequest(latest, paused = false)
            markIssued(latestSerial)
        }
    }

    private fun markIssued(serial: Int) = synchronized(this) {
        if (serial > loadIssuedSerial) loadIssuedSerial = serial
    }

    /**
     * True on the thread of an abandoned command executor: a task that was only slow (a reconfigure
     * past [StallPolicy.STUCK_COMMAND_MS]) returns there after the new handle took over and must not
     * touch the handle or the shared state any more.
     */
    private fun isStaleCommandThread(): Boolean = CommandExecutor.ofCurrentThread() !== commandExecutor

    /** Debug only: behave like B-08 for the current load (command thread blocked, no playback-restart). */
    private fun simulateStall() {
        val serial = loadSerial.get()
        ignoreRestartSerial = serial
        Log.w("VP", "B-08 debug: simulating a stalled open")
        executeCommand {
            val stuck = handle
            val token = handleToken
            if (stuck != 0L) MpvNative.setPropertyBoolean(stuck, "pause", true)
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                // The watchdog abandoned this handle. Unlike a real stall this one is healthy,
                // so it can be destroyed instead of leaked.
                if (stuck != 0L && token != handleToken) {
                    MpvNative.setEventCallback(stuck, null)
                    MpvNative.detachSurface(stuck)
                    MpvNative.destroy(stuck)
                    Log.w("VP", "B-08 debug: simulated stuck handle destroyed")
                }
            }
        }
    }

    internal fun computeFallback(hwdec: String, codec: String?, durationMs: Long): Boolean {
        return Companion.computeFallback(
            hwdec = hwdec,
            codec = codec,
            durationMs = durationMs,
            hasFilePath = currentFilePath.isNotEmpty(),
            playbackRestartMs = lastPlaybackRestartMs,
            nowMs = System.currentTimeMillis(),
            alreadyFallback = _state.value.fallback,
            softwareByDesign = playbackConfig.hwdecOverride == "no"
        )
    }

    private fun scheduleFallbackCheck() {
        val current = _state.value
        val codec = current.videoCodec
        if (codec == null || !setOf("h264", "hevc", "vp9").any { codec.lowercase().contains(it) }) {
            return
        }
        fallbackCheckJob?.cancel()
        fallbackCheckJob = scope.launch {
            delay(FALLBACK_GRACE_PERIOD_MS + 50L)
            executeCommand {
                assertCommandThread()
                val st = _state.value
                val fallback = computeFallback(
                    hwdec = st.hwdecCurrent,
                    codec = st.videoCodec,
                    durationMs = st.durationMs
                )
                if (st.fallback != fallback) {
                    _state.update { it.copy(fallback = fallback) }
                }
            }
        }
    }

    @Volatile private var statsJob: Job? = null

    fun setStatsEnabled(enabled: Boolean) {
        executeCommand {
            assertCommandThread()
            if (enabled) {
                if (statsJob == null || statsJob?.isActive == false) {
                    statsJob = scope.launch {
                        while (isActive) {
                            logStats()
                            delay(500)
                        }
                    }
                }
            } else {
                statsJob?.cancel()
                statsJob = null
            }
        }
    }

    fun logStats() {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                val vo = MpvNative.getPropertyString(handle, "current-vo") ?: ""
                val hwdec = MpvNative.getPropertyString(handle, "hwdec-current") ?: ""
                val drops = MpvNative.getPropertyLong(handle, "frame-drop-count") ?: 0L
                val decDrops = MpvNative.getPropertyLong(handle, "decoder-frame-drop-count") ?: 0L
                val fps = MpvNative.getPropertyDouble(handle, "estimated-vf-fps") ?: 0.0
                val gpuApi = MpvNative.getPropertyString(handle, "gpu-api")
                    ?: (playbackConfig.getOptionTable()["gpu-api"] ?: if (playbackConfig.mode == PlaybackMode.QUALITY) "opengl" else "none")
                val gpuContext = MpvNative.getPropertyString(handle, "gpu-context")
                    ?: (playbackConfig.getOptionTable()["gpu-context"] ?: "none")
                val modeStr = "${playbackConfig.mode} (${if (playbackConfig.mode == PlaybackMode.EFFICIENT) playbackConfig.efficientPath else playbackConfig.renderer})"
                val rendererInUse = playbackConfig.renderer.name
                _stats.value = PlayerStats(
                    vo = vo,
                    hwdecCurrent = hwdec,
                    mode = modeStr,
                    gpuApi = gpuApi,
                    gpuContext = gpuContext,
                    rendererInUse = rendererInUse,
                    lastVulkanError = lastVulkanErrorText,
                    videoW = _state.value.videoWidth,
                    videoH = _state.value.videoHeight,
                    fps = fps,
                    frameDropCount = drops,
                    decoderFrameDropCount = decDrops,
                    formatProfileRow = currentResolvedConfig?.chosenRowDescription ?: _state.value.formatProfileRow,
                    formatProfileReason = currentResolvedConfig?.reason ?: _state.value.formatProfileReason,
                    detectedFormat = currentFormat
                )
                Log.i("VP-Perf", "Stats: vo=$vo hwdec=$hwdec mode=$modeStr gpuApi=$gpuApi gpuContext=$gpuContext drops=$drops decDrops=$decDrops fps=$fps")
            }
        }
    }

    init {
        scope.launch {
            awaitInitialConfig()
            try {
                initHandle()
            } finally {
                initialisedSignal.complete(Unit)
            }
            PerfLog.mark("engine-initialised")
        }
        checkScope.launch {
            firstFrameReady.first { it }
            emitCheckLog(force = true)
            while (isActive) {
                delay(500)
                emitCheckLog()
            }
        }
    }

    fun assertCommandThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "mpv call must never run on main thread!"
        }
        check(Thread.currentThread().name.startsWith("VP-Command")) {
            "mpv call must run on VP-Command thread, but was ${Thread.currentThread().name}"
        }
    }

    /** Returns false when this command thread was abandoned meanwhile (nothing was changed then). */
    private fun initHandle(): Boolean {
        assertCommandThread()
        val created = MpvNative.create(BuildConfig.DEBUG)
        val published = synchronized(this) {
            if (released || isStaleCommandThread()) {
                false
            } else {
                handle = created
                true
            }
        }
        if (!published) {
            Log.w("VP", "initHandle: command thread abandoned or engine released meanwhile; dropping the new handle")
            if (created != 0L) MpvNative.destroy(created)
            return false
        }
        handleCreatedAtMs = System.currentTimeMillis()
        hwdecForcedSoftware = false
        if (handle == 0L) {
            Log.e("VP", "Failed to create mpv handle")
            return true
        }

        MpvNative.setEventCallback(handle, GatedCallback(handleToken))
        timePosObserved = true
        MpvNative.setVo(handle, playbackConfig.vo)

        // Options from PlaybackConfig option table
        val optionTable = playbackConfig.getOptionTable()
        for ((key, value) in optionTable) {
            MpvNative.setOptionString(handle, key, value)
        }
        handleHwdec = optionTable["hwdec"]
        if (playbackConfig.debugMpvOptions.isNotEmpty()) {
            Log.i("VP", "Debug mpv_opts applied at init: ${playbackConfig.debugMpvOptions}")
        }

        val res = MpvNative.initialize(handle)
        Log.i("VP", "MpvNative.initialize returned $res (vo=${playbackConfig.vo}, path=${playbackConfig.efficientPath}, renderer=${playbackConfig.renderer})")

        val choicesRaw = MpvNative.getPropertyString(handle, "option-info/gpu-api/choices")
        val parsedChoices = RendererResolver.parseGpuApiChoices(choicesRaw)
        if (parsedChoices.isNotEmpty()) {
            availableGpuApis = parsedChoices
            cachedGpuApis = parsedChoices
        }
        val profile = playbackConfig.formatProfile
        if (profile != null) {
            currentFormat = profile.format
            currentResolvedConfig = profile
        }
        _state.update {
            it.copy(
                rendererInUse = playbackConfig.renderer.name,
                lastVulkanError = lastVulkanErrorText,
                formatProfileRow = currentResolvedConfig?.chosenRowDescription ?: it.formatProfileRow,
                formatProfileReason = currentResolvedConfig?.reason ?: it.formatProfileReason,
                detectedFormat = currentFormat
            )
        }

        MpvNative.observeProperty(handle, REPLY_PAUSE, "pause", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, REPLY_DURATION, "duration", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, REPLY_TIME_POS, "time-pos", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, REPLY_HWDEC, "hwdec-current", MpvFormat.STRING)
        MpvNative.observeProperty(handle, REPLY_EOF, "eof-reached", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, REPLY_BUFFERING, "paused-for-cache", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, REPLY_SPEED, "speed", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, REPLY_DWIDTH, "dwidth", MpvFormat.INT64)
        MpvNative.observeProperty(handle, REPLY_DHEIGHT, "dheight", MpvFormat.INT64)
        MpvNative.observeProperty(handle, REPLY_VIDEO_CODEC, "video-codec", MpvFormat.STRING)
        MpvNative.observeProperty(handle, REPLY_VIDEO_FORMAT, "video-format", MpvFormat.STRING)
        MpvNative.observeProperty(handle, REPLY_AID, "aid", MpvFormat.STRING)
        MpvNative.observeProperty(handle, REPLY_SID, "sid", MpvFormat.STRING)

        MpvNative.setPropertyString(handle, "keepaspect", "yes")
        MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
        MpvNative.setPropertyDouble(handle, "panscan", 0.0)
        applyShaderPresetInternal()
        return true
    }

    fun setControlsVisible(visible: Boolean) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                if (visible && !timePosObserved) {
                    MpvNative.observeProperty(handle, REPLY_TIME_POS, "time-pos", MpvFormat.DOUBLE)
                    timePosObserved = true
                } else if (!visible && timePosObserved) {
                    MpvNative.unobserveProperty(handle, REPLY_TIME_POS)
                    timePosObserved = false
                }
            }
        }
    }

    fun attachSurface(surface: Surface) {
        executeCommand {
            assertCommandThread()
            attachedSurface = surface
            if (handle != 0L) {
                MpvNative.attachSurface(handle, surface)
                val restoreVid = savedVid ?: "auto"
                MpvNative.setPropertyString(handle, "vid", restoreVid)
                Log.i("VP", "Surface attached to mpv handle; restored vid=$restoreVid")
                PerfLog.mark("surface-attached")
                savedVid = null
                checkVulkanVoInitialization()
                checkGlesInitialization()
            }
        }
    }

    /**
     * @param surface the Surface being destroyed. When given and it is not the attached one (a
     * surface replaced after a stall recovery), nothing is detached.
     */
    fun detachSurface(surface: Surface? = null) {
        if (released) return
        try {
            val future = commandExecutor.submit {
                assertCommandThread()
                if (surface != null && attachedSurface !== surface) {
                    Log.i("VP", "detachSurface ignored: not the attached surface")
                    return@submit
                }
                attachedSurface = null
                if (handle != 0L) {
                    val currentVid = MpvNative.getPropertyString(handle, "vid")
                    if (!currentVid.isNullOrEmpty() && currentVid != "no") {
                        savedVid = currentVid
                    }
                    MpvNative.detachSurface(handle)
                    MpvNative.setPropertyString(handle, "vid", "no")
                    Log.i("VP", "Surface detached from mpv handle; vid set to no (savedVid=$savedVid)")
                }
            }
            // Called from surfaceDestroyed on the main thread. Bounded: if the command thread is
            // stuck behind a blocked mpv core, an unbounded wait is an ANR. On timeout the task
            // stays queued, so the surface reference is dropped only when the detach really runs.
            future.get(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e("VP", "detachSurface timed out after $DETACH_TIMEOUT_MS ms (command thread busy); detach stays queued")
        } catch (e: Exception) {
            Log.e("VP", "detachSurface failed: ${e.message}", e)
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setSurfaceSize(handle, width, height)
                Log.i("VP", "Surface size updated to ${width}x$height")
            }
        }
    }

    fun load(
        path: String,
        startPositionMs: Long = 0L,
        audioTrackId: Int? = null,
        audioEnabled: Boolean = true,
        subTrackId: Int? = null,
        subEnabled: Boolean = true,
        speed: Double = 1.0,
        aspect: String? = null,
        subDelayMs: Int = 0,
        audioDelayMs: Int = 0,
        identity: String? = null,
        /** What the format detection learned about the video track; null when unknown. */
        clip: ClipInfo? = null
    ) {
        val fileIdentity = identity ?: path
        val hadOverride = speedOverride != null
        speedOverride = null // P3-09: a new file never inherits a hold
        _position.value = 0L
        _state.update { it.copy(durationMs = 0L, tracks = emptyList(), selectedAudioTrackId = null, selectedSubTrackId = null, subDelayMs = SyncDelay.clamp(subDelayMs), audioDelayMs = SyncDelay.clamp(audioDelayMs)) }
        val request = LoadRequest(path, startPositionMs, audioTrackId, audioEnabled, subTrackId, subEnabled, speed, aspect, subDelayMs, audioDelayMs, fileIdentity, clip)
        lastLoad = request
        val serial = loadSerial.incrementAndGet()
        latestOpenSerial = serial
        // B-08: if the command thread is stuck in a stalled decoder, this load never runs; the
        // watchdog then abandons the stuck handle (next/previous during a stall).
        schedulePendingLoadCheck(serial, android.os.SystemClock.elapsedRealtime(), StallPolicy.LOAD_START_CHECK_MS)
        scope.launch {
            if (hadOverride && handle != 0L) MpvNative.setPropertyDouble(handle, "speed", 1.0)
            // A reconfigure queued before this open reloads the previous file when it runs and sets
            // lastLoad to it; the newest open takes it back so a stall recovery or a save uses this file.
            if (latestOpenSerial == serial && !isStaleCommandThread()) lastLoad = request
            loadInternal(
                path = path,
                startPositionMs = startPositionMs,
                audioTrackId = audioTrackId,
                audioEnabled = audioEnabled,
                subTrackId = subTrackId,
                subEnabled = subEnabled,
                speed = speed,
                aspect = aspect,
                subDelayMs = subDelayMs,
                audioDelayMs = audioDelayMs,
                paused = false,
                identity = fileIdentity,
                clip = clip
            )
            if (!isStaleCommandThread()) markIssued(serial)
        }
    }

    private fun loadInternal(
        path: String,
        startPositionMs: Long = 0L,
        audioTrackId: Int? = null,
        audioEnabled: Boolean = true,
        subTrackId: Int? = null,
        subEnabled: Boolean = true,
        speed: Double = 1.0,
        aspect: String? = null,
        subDelayMs: Int = 0,
        audioDelayMs: Int = 0,
        paused: Boolean = false,
        identity: String = path,
        clip: ClipInfo? = null
    ) {
        assertCommandThread()
        if (isStaleCommandThread()) {
            Log.w("VP", "loadInternal: command thread was abandoned; $path is not loaded here")
            return
        }
        loadInternalCount++
        currentFilePath = path
        currentFileIdentity = identity
        // B-08: one place every load passes through (open, reconfigure, fallbacks, stall reload).
        val wasRecovering = softwareRecoveryIdentity
        softwareRecoveryIdentity = StallPolicy.recoveryAfterLoad(softwareRecoveryIdentity, identity)
        if (wasRecovering != null && softwareRecoveryIdentity == null) {
            Log.i("VP", "B-08 recovery over: $identity is another file, hardware decoding allowed again")
        }
        fileLoaded = false
        pendingSubtitle?.let {
            closeFdPath(it.fdPath)
            pendingSubtitle = null
        }
        lastPlaybackRestartMs = 0L
        fallbackCheckJob?.cancel()
        fallbackCheckJob = null
        _zoomState.value = 0.0
        currentPanX = 0.0
        currentPanY = 0.0
        _state.update {
            it.copy(
                videoCodec = null,
                fallback = false,
                aspect = aspect,
                rendererInUse = if (playbackConfig.mode == PlaybackMode.QUALITY) playbackConfig.renderer.name else "GLES",
                lastVulkanError = lastVulkanErrorText
            )
        }
        if (handle != 0L) {
            val options = mutableListOf<String>()
            // P5-03: no surface (background audio) means no video decoding; vid is restored on attach.
            if (attachedSurface == null) {
                savedVid = "auto"
                options.add("vid=no")
            } else {
                savedVid = null
            }
            if (startPositionMs > 0L) {
                val sec = String.format(java.util.Locale.US, "%.3f", startPositionMs / 1000.0)
                options.add("start=$sec")
            }
            if (!audioEnabled) {
                options.add("aid=no")
            } else if (audioTrackId != null) {
                options.add("aid=$audioTrackId")
            }

            if (!subEnabled) {
                options.add("sid=no")
            } else if (subTrackId != null) {
                options.add("sid=$subTrackId")
            }

            // Always written: setSpeed() changes mpv's global speed, so a file without its own
            // speed (1.0) would otherwise inherit the previous file's.
            options.add("speed=" + String.format(java.util.Locale.US, "%.2f", speed))

            // P4-13: the delays are per file; a new file must not inherit the previous file's values,
            // so the defaults are written explicitly. The subtitle style is global.
            options.add("sub-delay=${SyncDelay.toSeconds(subDelayMs)}")
            options.add("audio-delay=${SyncDelay.toSeconds(audioDelayMs)}")
            subtitleStyle.mpvOptions().forEach { (k, v) -> options.add("$k=$v") }

            if (paused) {
                options.add("pause=yes")
                pendingPauseOnLoad = true
            } else {
                pendingPauseOnLoad = false
                // pause is global in mpv and survives loadfile: a session paused by the user (or by
                // keep-open at the end) would open the next file paused while FILE_LOADED reports playing.
                MpvNative.setPropertyBoolean(handle, "pause", false)
            }

            val normAspect = aspect ?: "fit"
            when (normAspect.lowercase()) {
                "16:9" -> {
                    MpvNative.setPropertyString(handle, "keepaspect", "yes")
                    MpvNative.setPropertyString(handle, "video-aspect-override", "16:9")
                    MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    options.add("keepaspect=yes")
                    options.add("video-aspect-override=16:9")
                    options.add("panscan=0.0")
                }
                "4:3" -> {
                    MpvNative.setPropertyString(handle, "keepaspect", "yes")
                    MpvNative.setPropertyString(handle, "video-aspect-override", "4:3")
                    MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    options.add("keepaspect=yes")
                    options.add("video-aspect-override=4:3")
                    options.add("panscan=0.0")
                }
                "crop" -> {
                    MpvNative.setPropertyString(handle, "keepaspect", "yes")
                    MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                    MpvNative.setPropertyDouble(handle, "panscan", 1.0)
                    options.add("keepaspect=yes")
                    options.add("video-aspect-override=-1")
                    options.add("panscan=1.0")
                }
                "stretch" -> {
                    MpvNative.setPropertyString(handle, "keepaspect", "no")
                    MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                    MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    options.add("keepaspect=no")
                    options.add("video-aspect-override=-1")
                    options.add("panscan=0.0")
                }
                else -> {
                    MpvNative.setPropertyString(handle, "keepaspect", "yes")
                    MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                    MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    options.add("keepaspect=yes")
                    options.add("video-aspect-override=-1")
                    options.add("panscan=0.0")
                }
            }

            applyShaderPresetInternal()

            val software = StallPolicy.softwareAfterStall(softwareRecoveryIdentity, identity)
            if (software && !hwdecForcedSoftware) {
                MpvNative.setPropertyString(handle, "hwdec", "no")
                hwdecForcedSoftware = true
                handleHwdec = "no"
                Log.w("VP", "B-08: $identity stalled with hardware decoding earlier; loading it with hwdec=no")
            } else if (!software && hwdecForcedSoftware) {
                playbackConfig.getOptionTable()["hwdec"]?.let {
                    MpvNative.setPropertyString(handle, "hwdec", it)
                    handleHwdec = it
                }
                hwdecForcedSoftware = false
            }

            // P6-08 fix: MediaCodec clock hint, per file (after the B-08 decision: never for software), only
            // when mpv will open a MediaCodec decoder with the handle's hwdec / hwdec-codecs / gpu-api: every
            // other decoder rejects the AVOption ("AVOption 'operating_rate' not found").
            val hwdecForFile = if (hwdecForcedSoftware) "no" else handleHwdec
            val handleOptions = playbackConfig.getOptionTable()
            val clipFormat = clip?.format
            val ratePolicy = if (clipFormat != null) FormatProfileTable.getProfile(clipFormat).operatingRate else OperatingRatePolicy.OFF
            val rate = OperatingRate.hint(ratePolicy, hwdecForFile, clip, handleOptions["hwdec-codecs"], handleOptions["gpu-api"])
            val rateOption = OperatingRate.loadOption(rate, playbackConfig.debugMpvOptions)
            if (rateOption != null) {
                options.add(rateOption)
            } else if (rate != null) {
                Log.i("VP", "Operating-rate hint $rate not set: debug mpv_opts set vd-lavc-o")
            }
            if (clip != null) {
                Log.i("VP", "Operating rate for ${clip.format.formatKey} fps=${clip.fps} ${clip.width}x${clip.height} tenBit=${clip.tenBit} hwdec=$hwdecForFile hwdec-codecs=${handleOptions["hwdec-codecs"]} gpu-api=${handleOptions["gpu-api"]} policy=${ratePolicy.token} -> ${rate ?: "none"}")
            }

            val cmd = if (options.isNotEmpty()) {
                val optStr = options.joinToString(",")
                Log.i("VP", "Loading $path with options: $optStr")
                arrayOf("loadfile", path, "replace", "-1", optStr)
            } else {
                Log.i("VP", "Loading $path from start without options")
                arrayOf("loadfile", path, "replace")
            }
            PerfLog.mark("loadfile-issued")
            val res = MpvNative.command(handle, cmd)
            Log.i("VP", "loadfile res=$res for $path")
            if (paused) {
                MpvNative.setPropertyBoolean(handle, "pause", true)
            }
        }
    }

    /**
     * Loads issued on the command thread. A reload waiting for a fresh descriptor is dropped when this
     * changed: its serial is taken when its task runs, so an open queued before it has a lower serial
     * and the serial check alone would load the old file over the newer one.
     */
    @Volatile private var loadInternalCount = 0

    private fun loadRequest(request: LoadRequest, paused: Boolean) {
        loadInternal(
            path = request.path,
            startPositionMs = request.startPositionMs,
            audioTrackId = request.audioTrackId,
            audioEnabled = request.audioEnabled,
            subTrackId = request.subTrackId,
            subEnabled = request.subEnabled,
            speed = request.speed,
            aspect = request.aspect,
            subDelayMs = request.subDelayMs,
            audioDelayMs = request.audioDelayMs,
            paused = paused,
            identity = request.identity,
            clip = request.clip
        )
    }

    /**
     * Loads [request] again on the current handle as load number [serial] (already counted in
     * [loadSerial]). An fdclose:// path gets a fresh descriptor from [pathReopener] on the IO
     * dispatcher first; the descriptor is closed again on every path that does not reach loadfile
     * (a newer load came in, the engine was released, the handle could not be created).
     * Runs on the command thread.
     */
    private fun reloadOnNewHandle(
        request: LoadRequest,
        serial: Int,
        paused: Boolean,
        why: String,
        /** B-08: the old handle is leaked, not destroyed, so its descriptor is still open and usable. */
        reuseOpenFd: Boolean = false
    ) {
        assertCommandThread()
        val plan = ReloadPolicy.plan(request.path, request.identity, pathReopener != null)
        when {
            plan == ReloadPolicy.Plan.Same || (plan == ReloadPolicy.Plan.Impossible && reuseOpenFd) -> {
                lastLoad = request
                loadRequest(request, paused)
            }
            plan == ReloadPolicy.Plan.Impossible -> {
                Log.w("VP", "$why: ${request.path} cannot be reopened on the new handle; reopen the file to continue")
            }
            plan is ReloadPolicy.Plan.Reopen -> {
                val reopener = pathReopener ?: return
                Log.i("VP", "$why: resolving a fresh descriptor for ${plan.identity}")
                // Now, not when the descriptor arrives: until then lastKnownPositionMs() (a save) and the
                // watchdogs must see this load (its start position), not the previous one.
                lastLoad = request
                val loadsBefore = loadInternalCount
                ioScope.launch {
                    val fresh = try {
                        reopener(plan.identity)
                    } catch (t: Throwable) {
                        Log.w("VP", "$why: reopening ${plan.identity} failed: ${t.message}")
                        null
                    }
                    if (fresh == null) {
                        Log.w("VP", "$why: ${plan.identity} could not be reopened; reopen the file to continue")
                        return@launch
                    }
                    // Dropped unrun when this thread is abandoned meanwhile: the descriptor is closed then.
                    val task = FdOwningTask(fresh) {
                        if (released || serial != loadSerial.get() || loadInternalCount != loadsBefore || handle == 0L) {
                            Log.i("VP", "$why: reopened descriptor not used (newer load or no handle); closing it")
                            closeFdPath(fresh)
                            return@FdOwningTask
                        }
                        val reopened = request.copy(path = fresh)
                        lastLoad = reopened
                        loadRequest(reopened, paused)
                    }
                    try {
                        if (released) closeFdPath(fresh) else commandExecutor.execute(task)
                    } catch (_: RejectedExecutionException) {
                        closeFdPath(fresh)
                    }
                }
            }
        }
    }

    fun setShaderDirectory(dir: String) {
        shaderDirectory = dir
    }

    fun setShaderPreset(preset: ShaderPreset, dir: String? = null) {
        if (dir != null) {
            shaderDirectory = dir
        }
        currentShaderPreset = preset
        executeCommand {
            assertCommandThread()
            applyShaderPresetInternal()
        }
    }

    private fun applyShaderPresetInternal() {
        assertCommandThread()
        if (handle == 0L) return
        // The renderer of the current handle: a Vulkan -> GLES fallback creates a new handle and applies
        // the preset again, so the GLES chain (without Clamp_Highlights, P8-02) is picked up there.
        val renderer = playbackConfig.renderer
        val glslShadersValue = currentShaderPreset.toGlslShadersProperty(playbackConfig.mode, shaderDirectory, renderer)
        MpvNative.setPropertyString(handle, "glsl-shaders", glslShadersValue)
        Log.i("VP", "Applied shader preset $currentShaderPreset (mode=${playbackConfig.mode}, renderer=$renderer): $glslShadersValue")
    }

    fun setHwdec(hwdec: String) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyString(handle, "hwdec", hwdec)
                handleHwdec = hwdec
                Log.i("VP", "hwdec set to $hwdec")
            }
        }
    }

    /**
     * @param reloadCurrentFile false when the caller loads another file right after (openVideo): the
     * old file is then not reloaded on the new handle (a wasted loadfile whose pause state would
     * carry over to the next file).
     */
    fun reconfigure(newConfig: PlaybackConfig, reloadCurrentFile: Boolean = true) {
        requestedConfig = newConfig
        executeCommand {
            assertCommandThread()
            if (playbackConfig == newConfig) return@executeCommand
            Log.i("VP", "Reconfiguring engine: ${playbackConfig.mode}/${playbackConfig.renderer} -> ${newConfig.mode}/${newConfig.renderer}")
            val curPath = currentFilePath
            // Before file-loaded (e.g. a Vulkan failure at VO init) time-pos is unset and the position
            // flow still reads 0: keep the pending load's start position (resume point) instead.
            val pendingStart = lastLoad?.takeIf { !fileLoaded && it.path == curPath }?.startPositionMs
            val curPos = pendingStart ?: if (handle != 0L) {
                MpvNative.getPropertyDouble(handle, "time-pos")?.let { (it * 1000.0).toLong() } ?: _position.value
            } else {
                _position.value
            }
            val curIdentity = currentFileIdentity.ifEmpty { curPath }
            val wasPaused = _state.value.paused
            val curAid = _state.value.selectedAudioTrackId
            val curAudioEnabled = curAid != null || _state.value.tracks.none { it.type == "audio" }
            val curSid = _state.value.selectedSubTrackId
            val curSubEnabled = curSid != null
            val curSpeed = _state.value.speed
            val curAspect = _state.value.aspect
            val curSubDelay = _state.value.subDelayMs
            val curAudioDelay = _state.value.audioDelayMs
            val surface = attachedSurface

            // The time-pos read above blocks on a stuck core; if it returned only after an abandon,
            // handle is the new thread's and must not be destroyed from here.
            val old = synchronized(this) {
                if (isStaleCommandThread()) return@executeCommand
                handle
            }
            if (old != 0L) {
                MpvNative.detachSurface(old)
                MpvNative.destroy(old)
            }
            // A destroy (or create) that took longer than STUCK_COMMAND_MS may have been abandoned by the
            // B-08 watchdog: the new command thread owns handle and config from then on.
            synchronized(this) {
                if (isStaleCommandThread()) {
                    Log.w("VP", "reconfigure: command thread was abandoned while destroying the old handle; leaving it to the new one")
                    return@executeCommand
                }
                handle = 0L
            }
            fileLoaded = false

            playbackConfig = newConfig
            // P3-09: the new mpv handle starts at speed 1.0; a hold in progress ends with the reconfigure
            // (curSpeed above is the file's own speed, never the hold override).
            speedOverride = null
            if (!initHandle()) return@executeCommand

            if (surface != null && handle != 0L) {
                MpvNative.attachSurface(handle, surface)
                savedVid = null
            }
            // P5-03: the new handle starts at full volume; keep an audio-focus duck in progress.
            if (isDucked && handle != 0L) {
                MpvNative.setPropertyDouble(handle, "volume", (preDuckVolume ?: 100.0) * 0.3)
            }

            if (curPath.isNotEmpty() && reloadCurrentFile) {
                // A new load for the B-08 watchdog: the previous serial already saw its playback-restart.
                // fdclose://N: mpv closed N with the old handle; reopening it would read a dead or
                // reused fd, so a fresh one is resolved from the file's content:// identity.
                val serial = loadSerial.incrementAndGet()
                val curClip = lastLoad?.takeIf { it.identity == curIdentity }?.clip
                val request = LoadRequest(curPath, curPos, curAid, curAudioEnabled, curSid, curSubEnabled, curSpeed, curAspect, curSubDelay, curAudioDelay, curIdentity, curClip)
                reloadOnNewHandle(request, serial, wasPaused, "reconfigure")
            }
        }
    }

    private var vulkanCheckJob: Job? = null
    /** file-loaded seen for the current loadfile (reset by every loadfile). */
    @Volatile private var fileLoaded = false

    private fun checkVulkanVoInitialization() {
        if (playbackConfig.renderer != Renderer.VULKAN) {
            return
        }
        vulkanCheckJob?.cancel()
        vulkanCheckJob = scope.launch {
            // The failure belongs to this handle and this file: a reconfigure (another handle) or an
            // open of the next file (openVideo switches currentFormat before its load) during the
            // window must not be blamed, since the verdict is remembered per format or driver build.
            val checkedHandle = handle
            val checkedFormat = currentFormat
            if (BuildConfig.DEBUG && debugForceVulkanFailureOnce) {
                debugForceVulkanFailureOnce = false
                handleVulkanFailure("Debug forced Vulkan failure", forcedScope = VulkanFailureScope.FORMAT, format = checkedFormat)
                return@launch
            }
            val startTime = System.currentTimeMillis()
            var voConfigured = false
            while (isActive && System.currentTimeMillis() - startTime < 3200L) {
                delay(200L)
                if (handle == 0L || handle != checkedHandle) break
                val currentVo = MpvNative.getPropertyString(handle, "current-vo")
                val voParams = MpvNative.getPropertyString(handle, "video-out-params")
                if (!currentVo.isNullOrEmpty() && currentVo != "null" && currentVo != "none") {
                    voConfigured = true
                    break
                }
                if (!voParams.isNullOrEmpty() && voParams != "null") {
                    voConfigured = true
                    break
                }
            }
            val vid = if (!voConfigured && isActive && handle != 0L) MpvNative.getPropertyString(handle, "vid") else null
            if (isActive && handle != 0L && handle == checkedHandle && playbackConfig.renderer == Renderer.VULKAN &&
                isVulkanVoFailure(fileLoaded, attachedSurface != null, vid, voConfigured)
            ) {
                handleVulkanFailure("Vulkan VO failed to initialise (no current-vo/video-out-params within 3s)", format = checkedFormat)
            }
        }
    }

    private var glesCheckJob: Job? = null

    private fun checkGlesInitialization() {
        if (playbackConfig.renderer != Renderer.GLES) {
            return
        }
        glesCheckJob?.cancel()
        glesCheckJob = scope.launch {
            if (BuildConfig.DEBUG && debugForceGlesFailureOnce) {
                debugForceGlesFailureOnce = false
                handleGlesFailure("Debug forced OpenGL ES failure")
                return@launch
            }
        }
    }

    fun handleVulkanFailure(reason: String, forcedScope: VulkanFailureScope? = null, format: VideoFormat? = currentFormat) {
        sessionVulkanFailed = true
        val mpvLines = dumpMpvLogSince(handleCreatedAtMs)
        val classified = VulkanFailureClassifier.classify(mpvLines, reason)
        val failure = if (forcedScope != null) classified.copy(scope = forcedScope) else classified
        val errorLines = mpvLines.filter(::isErrorLine).takeLast(10)
        Log.e("VP", "VULKAN-FAIL: $reason (scope=${failure.scope}${failure.evidence?.let { ", evidence: $it" } ?: ""})")
        for (err in errorLines) {
            Log.e("VP", "VULKAN-FAIL: $err")
        }
        val errSummary = if (errorLines.isNotEmpty()) errorLines.joinToString("\n") else reason
        lastVulkanErrorText = errSummary
        _state.update { it.copy(lastVulkanError = errSummary) }
        _onScreenMessage.tryEmit("Vulkan failed, using OpenGL ES")
        Log.w("VP", "Vulkan failed, falling back to OpenGL ES")
        try {
            onVulkanFailureListener?.invoke(format, failure)
        } catch (t: Throwable) {
            Log.w("VP", "Error notifying onVulkanFailureListener: ${t.message}")
        }
        reconfigure(glesFallbackConfig(playbackConfig))
    }

    /**
     * The OpenGL ES configuration after a Vulkan failure. A configuration picked from the format table is
     * resolved again without Vulkan, so a Vulkan row lands on its measured GLES values (e.g. H.264:
     * copy-back with vo=gpu, never gpu-next carried over); anything else (probes, overrides) only
     * switches the renderer.
     */
    private fun glesFallbackConfig(current: PlaybackConfig): PlaybackConfig {
        val fromTable = current.formatProfile?.takeIf { it.vo == current.voOverride && it.renderer == current.renderer }
            ?: return current.copy(renderer = Renderer.GLES)
        val resolved = FormatProfileTable.resolve(fromTable.format, current.mode, RendererSetting.AUTO, isVulkanAvailableForFormat = false)
        setCurrentFormatProfile(resolved)
        return current.copy(
            renderer = Renderer.GLES,
            voOverride = resolved.vo,
            hwdecOverride = if (current.hwdecOverride == "no") "no" else resolved.decodeMode.hwdec,
            formatProfile = resolved
        )
    }

    fun handleGlesFailure(reason: String) {
        Log.e("VP", "GLES-FAIL: $reason")
        _onScreenMessage.tryEmit("OpenGL ES failed, using software decoding")
        Log.w("VP", "OpenGL ES failed, falling back to software decoding")
        reconfigure(playbackConfig.copy(hwdecOverride = "no"))
    }

    fun setCurrentFormatProfile(config: ResolvedConfiguration) {
        currentFormat = config.format
        currentResolvedConfig = config
        _state.update {
            it.copy(
                formatProfileRow = config.chosenRowDescription,
                formatProfileReason = config.reason,
                detectedFormat = config.format
            )
        }
        _stats.update {
            it.copy(
                formatProfileRow = config.chosenRowDescription,
                formatProfileReason = config.reason,
                detectedFormat = config.format
            )
        }
    }

    private fun isErrorLine(l: String): Boolean =
        l.contains("E/") || l.contains("W/") || l.contains("vulkan", ignoreCase = true) ||
            l.contains("Error", ignoreCase = true) || l.contains("Failed", ignoreCase = true)

    /**
     * mpv log lines (tag "mpv") of this process since [sinceWallMs]. Bounded to this process and to
     * the current handle, so a line of an earlier handle, file or process cannot decide the scope.
     */
    private fun dumpMpvLogSince(sinceWallMs: Long): List<String> {
        val lines = mutableListOf<String>()
        try {
            val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(if (sinceWallMs > 0L) sinceWallMs else System.currentTimeMillis()))
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-d", "-v", "brief", "--pid=${android.os.Process.myPid()}", "-t", since, "-s", "mpv"
            ))
            process.inputStream.bufferedReader().useLines { logLines -> logLines.forEach { lines.add(it) } }
        } catch (e: Exception) {
            Log.w("VP", "Failed to dump logcat: ${e.message}")
        }
        return lines
    }

    /**
     * @param isVulkanAvailableForFormat the caller's driver-cache answer for [currentFormat]
     * (remembered per-format failures); null falls back to libmpv support and this session.
     */
    fun togglePlaybackMode(
        userRendererSetting: RendererSetting = RendererSetting.AUTO,
        isVulkanAvailableForFormat: Boolean? = null
    ) {
        executeCommand {
            assertCommandThread()
            val current = playbackConfig
            val newMode = if (current.mode == PlaybackMode.QUALITY) PlaybackMode.EFFICIENT else PlaybackMode.QUALITY
            val isVulkanAvailable = (isVulkanAvailableForFormat
                ?: availableGpuApis.any { it.equals("vulkan", ignoreCase = true) }) && !sessionVulkanFailed
            val resolved = FormatProfileTable.resolve(
                format = currentFormat,
                mode = newMode,
                rendererSetting = userRendererSetting,
                isVulkanAvailableForFormat = isVulkanAvailable
            )
            setCurrentFormatProfile(resolved)
            val newConfig = current.copy(
                mode = newMode,
                renderer = resolved.renderer,
                voOverride = resolved.vo,
                hwdecOverride = resolved.decodeMode.hwdec
            )
            reconfigure(newConfig)
        }
    }

    fun play() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyBoolean(handle, "pause", false)
                Log.i("VP", "play")
            }
        }
    }

    fun pause() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyBoolean(handle, "pause", true)
                Log.i("VP", "pause")
            }
        }
    }

    fun togglePause() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.command(handle, arrayOf("cycle", "pause"))
                Log.i("VP", "togglePause")
            }
        }
    }

    data class PendingTrackSelection(
        val fileChoice: FileTrackChoice?,
        val folderChoice: FolderTrackChoice?,
        val preferences: LanguagePreferences
    )

    private var pendingTrackSelection: PendingTrackSelection? = null

    fun setPendingTrackSelection(
        fileChoice: FileTrackChoice?,
        folderChoice: FolderTrackChoice?,
        preferences: LanguagePreferences
    ) {
        val selection = PendingTrackSelection(fileChoice, folderChoice, preferences)
        requestedTrackSelection = selection
        executeCommand {
            assertCommandThread()
            pendingTrackSelection = selection
            Log.i("VP", "Pending track selection set: file=$fileChoice, folder=$folderChoice, prefs=$preferences")
        }
    }

    fun updateTracks(allowPendingSelection: Boolean = false) {
        executeCommand {
            assertCommandThread()
            if (handle == 0L) return@executeCommand
            val count = MpvNative.getPropertyLong(handle, "track-list/count") ?: 0L
            val trackList = mutableListOf<Track>()
            for (i in 0 until count) {
                val id = MpvNative.getPropertyLong(handle, "track-list/$i/id")?.toInt() ?: continue
                val type = MpvNative.getPropertyString(handle, "track-list/$i/type") ?: continue
                val title = MpvNative.getPropertyString(handle, "track-list/$i/title")
                val lang = MpvNative.getPropertyString(handle, "track-list/$i/lang")
                val codec = MpvNative.getPropertyString(handle, "track-list/$i/codec")
                val channels = MpvNative.getPropertyLong(handle, "track-list/$i/audio-channels")?.toInt()
                val selected = MpvNative.getPropertyBoolean(handle, "track-list/$i/selected") ?: false
                val external = MpvNative.getPropertyBoolean(handle, "track-list/$i/external") ?: false
                val forced = MpvNative.getPropertyBoolean(handle, "track-list/$i/forced") ?: false
                trackList.add(
                    Track(
                        id = id,
                        type = type,
                        title = title,
                        lang = lang,
                        codec = codec,
                        channels = channels,
                        external = external,
                        selected = selected,
                        forced = forced
                    )
                )
            }
            val currentAid = MpvNative.getPropertyString(handle, "aid")?.toIntOrNull()
            val currentSid = MpvNative.getPropertyString(handle, "sid")?.toIntOrNull()
            if (isStaleCommandThread()) return@executeCommand
            _state.update {
                it.copy(
                    tracks = trackList,
                    selectedAudioTrackId = currentAid,
                    selectedSubTrackId = currentSid
                )
            }
            Log.i("VP", "Updated tracks (${trackList.size}): aid=$currentAid, sid=$currentSid")
            for (t in trackList) {
                Log.i("VP-Track", "id=${t.id} type=${t.type} lang=${t.lang} title=${t.title} forced=${t.forced} kind=${TrackSelector.getSubtitleKind(t)}")
            }

            val pending = if (allowPendingSelection) pendingTrackSelection else null
            if (pending != null && trackList.isNotEmpty()) {
                pendingTrackSelection = null
                val result = TrackSelector.selectTracks(
                    tracks = trackList,
                    fileChoice = pending.fileChoice,
                    folderChoice = pending.folderChoice,
                    preferences = pending.preferences
                )
                Log.i("VP", "TrackSelector resolved: aid=${result.selectedAudioTrackId} (audioEnabled=${result.audioEnabled}), sid=${result.selectedSubTrackId} (subEnabled=${result.subEnabled})")

                var changed = false
                if (!result.audioEnabled) {
                    if (currentAid != null) {
                        MpvNative.setPropertyString(handle, "aid", "no")
                        changed = true
                    }
                } else if (result.selectedAudioTrackId != null && result.selectedAudioTrackId != currentAid) {
                    MpvNative.setPropertyString(handle, "aid", result.selectedAudioTrackId.toString())
                    changed = true
                }

                if (!result.subEnabled) {
                    if (currentSid != null) {
                        MpvNative.setPropertyString(handle, "sid", "no")
                        changed = true
                    }
                } else if (result.selectedSubTrackId != null && result.selectedSubTrackId != currentSid) {
                    MpvNative.setPropertyString(handle, "sid", result.selectedSubTrackId.toString())
                    changed = true
                }

                if (changed) {
                    val newAid = MpvNative.getPropertyString(handle, "aid")?.toIntOrNull()
                    val newSid = MpvNative.getPropertyString(handle, "sid")?.toIntOrNull()
                    _state.update {
                        it.copy(
                            selectedAudioTrackId = newAid,
                            selectedSubTrackId = newSid
                        )
                    }
                }
            }
        }
    }

    fun setAudioTrack(trackId: Int?) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                val value = trackId?.toString() ?: "no"
                MpvNative.setPropertyString(handle, "aid", value)
                Log.i("VP", "setAudioTrack: aid=$value")
                updateTracks()
            }
        }
    }

    fun setSubtitleTrack(trackId: Int?) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                val value = trackId?.toString() ?: "no"
                MpvNative.setPropertyString(handle, "sid", value)
                Log.i("VP", "setSubtitleTrack: sid=$value")
                updateTracks()
            }
        }
    }

    /**
     * P4-14: Adds an external subtitle file to the player via mpv `sub-add`.
     * If the video file is already loaded, executes immediately on the command thread.
     * Otherwise, queues as [pendingSubtitle] to be applied once FILE_LOADED fires.
     * Uses [FdOwningTask] so descriptor in [fdPath] is closed if the executor drops it.
     */
    fun addSubtitleTrack(fdPath: String, title: String, select: Boolean = true) {
        if (released) {
            closeFdPath(fdPath)
            return
        }
        val task = FdOwningTask(fdPath) {
            assertCommandThread()
            if (released || handle == 0L) {
                closeFdPath(fdPath)
                return@FdOwningTask
            }
            if (fileLoaded) {
                val cmd = SubtitleCommands.buildSubAddArgs(fdPath, title, select)
                val res = MpvNative.command(handle, cmd)
                Log.i("VP", "sub-add: path=$fdPath, title=$title, select=$select, res=$res")
                updateTracks()
            } else {
                pendingSubtitle?.let { closeFdPath(it.fdPath) }
                pendingSubtitle = PendingSubtitle(fdPath, title, select)
                Log.i("VP", "Queued pending subtitle: path=$fdPath, title=$title, select=$select")
            }
        }
        try {
            if (released) closeFdPath(fdPath) else commandExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            closeFdPath(fdPath)
        }
    }

    fun setSpeed(speed: Double) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                val clamped = speed.coerceIn(0.25, 4.0)
                // During a hold the new user speed is remembered and applied when the hold ends.
                if (speedOverride == null) MpvNative.setPropertyDouble(handle, "speed", clamped)
                Log.i("VP", "setSpeed: speed=$clamped")
                _state.update { it.copy(speed = clamped) }
            }
        }
    }

    /**
     * P3-09: temporarily plays at [factor] (null restores the file's own speed in [PlayerState.speed]).
     * Runs on the command thread; pitch correction (audio-pitch-correction) is left untouched.
     */
    fun setSpeedOverride(factor: Double?) {
        val target = factor?.coerceIn(0.25, 4.0)
        speedOverride = target
        executeCommand {
            assertCommandThread()
            if (handle != 0L && speedOverride == target) {
                MpvNative.setPropertyDouble(handle, "speed", target ?: _state.value.speed)
                Log.i("VP", "setSpeedOverride: ${target ?: "cleared"}")
            }
        }
    }

    /** P4-13: live subtitle delay in ms (clamped); returns the applied value through [PlayerState.subDelayMs]. */
    fun setSubDelay(ms: Int) {
        val v = SyncDelay.clamp(ms)
        executeCommand {
            assertCommandThread()
            if (handle != 0L) MpvNative.setPropertyString(handle, "sub-delay", SyncDelay.toSeconds(v))
            _state.update { it.copy(subDelayMs = v) }
        }
    }

    fun setAudioDelay(ms: Int) {
        val v = SyncDelay.clamp(ms)
        executeCommand {
            assertCommandThread()
            if (handle != 0L) MpvNative.setPropertyString(handle, "audio-delay", SyncDelay.toSeconds(v))
            _state.update { it.copy(audioDelayMs = v) }
        }
    }

    /** P4-13: global subtitle style; applies to the current file now and to every later load. */
    fun setSubtitleStyle(style: SubtitleStyle) {
        val s = style.normalized()
        if (s == subtitleStyle) return // P7-02: no extra mpv work when loadfile already applied it
        subtitleStyle = s
        executeCommand {
            assertCommandThread()
            if (handle != 0L) s.mpvOptions().forEach { (k, v) -> MpvNative.setPropertyString(handle, k, v) }
        }
    }

    fun setAspect(aspect: String?) {
        executeCommand {
            assertCommandThread()
            _state.update { it.copy(aspect = aspect) }
            if (handle != 0L) {
                when (aspect?.lowercase()) {
                    "16:9" -> {
                        MpvNative.setPropertyString(handle, "keepaspect", "yes")
                        MpvNative.setPropertyString(handle, "video-aspect-override", "16:9")
                        MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    }
                    "4:3" -> {
                        MpvNative.setPropertyString(handle, "keepaspect", "yes")
                        MpvNative.setPropertyString(handle, "video-aspect-override", "4:3")
                        MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    }
                    "crop" -> {
                        MpvNative.setPropertyString(handle, "keepaspect", "yes")
                        MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                        MpvNative.setPropertyDouble(handle, "panscan", 1.0)
                    }
                    "stretch" -> {
                        MpvNative.setPropertyString(handle, "keepaspect", "no")
                        MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                        MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    }
                    else -> { // "fit" or null
                        MpvNative.setPropertyString(handle, "keepaspect", "yes")
                        MpvNative.setPropertyString(handle, "video-aspect-override", "-1")
                        MpvNative.setPropertyDouble(handle, "panscan", 0.0)
                    }
                }
                Log.i("VP", "setAspect: $aspect")
            }
        }
    }

    @Volatile private var isDucked = false
    @Volatile private var preDuckVolume: Double? = null

    /**
     * Lowers mpv volume to 30% of its current value on transient audio focus loss (CAN_DUCK),
     * and restores it when focus is regained.
     */
    fun setDuck(duck: Boolean) {
        executeCommand {
            assertCommandThread()
            if (handle == 0L) return@executeCommand
            if (duck) {
                if (isDucked) return@executeCommand
                val cur = MpvNative.getPropertyDouble(handle, "volume") ?: 100.0
                preDuckVolume = cur
                isDucked = true
                val ducked = cur * 0.3
                MpvNative.setPropertyDouble(handle, "volume", ducked)
                Log.i("VP", "PlayerEngine ducking audio: volume $cur -> $ducked")
            } else {
                if (!isDucked) return@executeCommand
                val restore = preDuckVolume ?: 100.0
                MpvNative.setPropertyDouble(handle, "volume", restore)
                Log.i("VP", "PlayerEngine unducking audio: volume restored to $restore")
                preDuckVolume = null
                isDucked = false
            }
        }
    }

    @Volatile
    private var lastKeyframeSeekTime: Long = 0L

    private val _zoomState = MutableStateFlow(0.0)
    val zoomState: StateFlow<Double> = _zoomState.asStateFlow()

    @Volatile private var currentPanX = 0.0
    @Volatile private var currentPanY = 0.0

    @Volatile
    private var lastZoomUpdateTime = 0L

    fun setVideoZoom(zoom: Double, commit: Boolean = false) {
        val clampedZoom = zoom.coerceIn(0.0, 3.0)
        _zoomState.value = clampedZoom
        val now = android.os.SystemClock.uptimeMillis()
        if (!commit && now - lastZoomUpdateTime < 25L) {
            return
        }
        lastZoomUpdateTime = now
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyDouble(handle, "video-zoom", clampedZoom)
                Log.i("VP", "video-zoom set to $clampedZoom (commit=$commit)")
            }
        }
    }

    @Volatile
    private var lastPanUpdateTime = 0L

    fun setVideoPan(panX: Double, panY: Double, commit: Boolean = false) {
        currentPanX = panX.coerceIn(-1.5, 1.5)
        currentPanY = panY.coerceIn(-1.5, 1.5)
        val now = android.os.SystemClock.uptimeMillis()
        if (!commit && now - lastPanUpdateTime < 25L) {
            return
        }
        lastPanUpdateTime = now
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyDouble(handle, "video-pan-x", currentPanX)
                MpvNative.setPropertyDouble(handle, "video-pan-y", currentPanY)
                Log.i("VP", "video-pan set to $currentPanX, $currentPanY (commit=$commit)")
            }
        }
    }

    fun resetZoomAndPan() {
        _zoomState.value = 0.0
        currentPanX = 0.0
        currentPanY = 0.0
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyDouble(handle, "video-zoom", 0.0)
                MpvNative.setPropertyDouble(handle, "video-pan-x", 0.0)
                MpvNative.setPropertyDouble(handle, "video-pan-y", 0.0)
                Log.i("VP", "resetZoomAndPan executed")
            }
        }
    }

    fun seekTo(positionMs: Long, exact: Boolean = false) {
        lastPlaybackRestartMs = System.currentTimeMillis()
        if (!exact) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastKeyframeSeekTime < 50) {
                return
            }
            lastKeyframeSeekTime = now
        }
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                val sec = (positionMs.toDouble() / 1000.0).toString()
                val flag = if (exact) "exact" else "keyframes"
                MpvNative.command(handle, arrayOf("seek", sec, "absolute+$flag"))
                Log.i("VP", "seekTo $positionMs ms ($flag)")
            }
        }
    }

    /**
     * Current position from mpv, bounded: during a decoder stall (B-08) the command thread is blocked
     * and never answers, so after [timeoutMs] the last known position is returned instead
     * ([StallPolicy.fallbackPositionMs]: the start position of a load that never started playing).
     * Callable from any thread; never blocks one.
     */
    suspend fun currentPositionMs(timeoutMs: Long = POSITION_READ_TIMEOUT_MS): Long {
        if (!released) {
            val read = BoundedCall.onExecutor(commandExecutor, timeoutMs) {
                if (released || handle == 0L) return@onExecutor null
                val posSec = MpvNative.getPropertyDouble(handle, "time-pos")
                if (posSec != null && !posSec.isNaN()) (posSec * 1000.0).toLong() else null
            }
            if (read != null) {
                _position.value = read
                return read
            }
        }
        return lastKnownPositionMs().also { Log.w("VP", "currentPositionMs: mpv did not answer within $timeoutMs ms, using $it ms") }
    }

    /**
     * Non-blocking position for saving: the last reported one, or the start position of a load
     * that has not started playing yet (a stalled or still opening file; [_position] is 0 then).
     */
    fun lastKnownPositionMs(): Long = StallPolicy.fallbackPositionMs(
        playbackStarted = restartSeenSerial == loadSerial.get(),
        knownMs = _position.value,
        loadStartMs = lastLoad?.startPositionMs
    )

    fun seekRelative(deltaSeconds: Double, exact: Boolean = false) {
        lastPlaybackRestartMs = System.currentTimeMillis()
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                val sec = String.format(java.util.Locale.US, "%.3f", deltaSeconds)
                val flag = if (exact) "relative+exact" else "relative"
                MpvNative.command(handle, arrayOf("seek", sec, flag))
                Log.i("VP", "seekRelative $sec s ($flag)")
            }
        }
    }

    fun release(sync: Boolean = false) {
        // Under the abandon lock: an abandon either finished (its executor is the one shut down here)
        // or sees released and does nothing; it can no longer swap in a thread nobody shuts down.
        val executor = synchronized(this) {
            if (released) return
            released = true
            commandExecutor
        }
        speedOverride = null
        savedVid = null
        isDucked = false
        preDuckVolume = null
        pendingSubtitle?.let {
            closeFdPath(it.fdPath)
            pendingSubtitle = null
        }
        watchdog.shutdownNow()
        fallbackCheckJob?.cancel()
        fallbackCheckJob = null
        statsJob?.cancel()
        statsJob = null
        checkScope.cancel()
        ioScope.cancel()
        val task = Runnable {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setEventCallback(handle, null)
                MpvNative.destroy(handle)
                handle = 0L
                Log.i("VP", "PlayerEngine handle destroyed")
            }
        }
        try {
            if (sync && Looper.myLooper() != Looper.getMainLooper()) {
                val future = executor.submit(task)
                future.get(5, TimeUnit.SECONDS)
                executor.shutdown()
            } else {
                executor.submit(task)
                executor.shutdown()
            }
        } catch (e: RejectedExecutionException) {
            Log.w("VP", "Command executor already stopped during release: ${e.message}")
        } catch (e: java.util.concurrent.TimeoutException) {
            // sync release behind a blocked command thread: do not throw at the caller; the destroy stays queued.
            Log.w("VP", "release: handle destroy did not finish within 5 s")
            executor.shutdown()
        }
    }
}
