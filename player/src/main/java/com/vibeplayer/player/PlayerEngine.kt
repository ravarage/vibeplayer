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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class PlayerEngine(
    initialConfig: PlaybackConfig = PlaybackConfig()
) {

    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VP-Command").apply { isDaemon = true }
    }
    private val commandDispatcher = commandExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(commandDispatcher + SupervisorJob())
    @Volatile private var released = false
    @Volatile private var timePosObserved = true

    private fun executeCommand(block: () -> Unit) {
        if (released) return
        try {
            commandExecutor.execute { if (!released) block() }
        } catch (_: RejectedExecutionException) {
            // Event callbacks and Compose disposal can race with release().
        }
    }

    private var handle: Long = 0L
    @Volatile var playbackConfig: PlaybackConfig = initialConfig
        private set
    @Volatile private var currentFilePath: String = ""
    private var attachedSurface: Surface? = null
    @Volatile private var shaderDirectory: String = ""
    @Volatile private var currentShaderPreset: ShaderPreset = ShaderPreset.NONE

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _stats = MutableStateFlow(PlayerStats())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    companion object {
        private const val DETACH_TIMEOUT_MS = 1500L
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
                    Log.i("VP-Perf", "playback-restart at ${System.currentTimeMillis()}")
                }
                MpvEvent.END_FILE -> {
                    logStats()
                    Log.i("VP", "mpv event end-file")
                }
                MpvEvent.FILE_LOADED -> {
                    Log.i("VP", "mpv event file-loaded")
                    _state.update { it.copy(paused = false) }
                    updateTracks(allowPendingSelection = true)
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

    private fun computeFallback(hwdec: String, codec: String?, durationMs: Long): Boolean {
        if (currentFilePath.isEmpty() || durationMs <= 0 || codec.isNullOrEmpty()) {
            return false
        }
        val hwdecCodecs = setOf("h264", "hevc", "vp9")
        val isHwdecCodec = hwdecCodecs.any { codec.lowercase().contains(it) }
        return isHwdecCodec && (hwdec == "no" || hwdec.isEmpty())
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
                val modeStr = "${playbackConfig.mode} (${playbackConfig.efficientPath})"
                _stats.value = PlayerStats(
                    vo = vo,
                    hwdecCurrent = hwdec,
                    mode = modeStr,
                    videoW = _state.value.videoWidth,
                    videoH = _state.value.videoHeight,
                    fps = fps,
                    frameDropCount = drops,
                    decoderFrameDropCount = decDrops
                )
                Log.i("VP-Perf", "Stats: vo=$vo hwdec=$hwdec mode=$modeStr drops=$drops decDrops=$decDrops fps=$fps")
            }
        }
    }

    init {
        scope.launch {
            initHandle()
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

    private fun initHandle() {
        assertCommandThread()
        handle = MpvNative.create(BuildConfig.DEBUG)
        if (handle == 0L) {
            Log.e("VP", "Failed to create mpv handle")
            return
        }

        MpvNative.setEventCallback(handle, eventCallback)
        MpvNative.setVo(handle, playbackConfig.vo)

        // Options from PlaybackConfig option table
        for ((key, value) in playbackConfig.getOptionTable()) {
            MpvNative.setOptionString(handle, key, value)
        }

        val res = MpvNative.initialize(handle)
        Log.i("VP", "MpvNative.initialize returned $res (vo=${playbackConfig.vo}, path=${playbackConfig.efficientPath})")

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
                Log.i("VP", "Surface attached to mpv handle")
            }
        }
    }

    fun detachSurface() {
        if (released) return
        try {
            val future = commandExecutor.submit {
                assertCommandThread()
                attachedSurface = null
                if (handle != 0L) {
                    MpvNative.detachSurface(handle)
                    Log.i("VP", "Surface detached from mpv handle")
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
        aspect: String? = null
    ) {
        _position.value = 0L
        _state.update { it.copy(durationMs = 0L, tracks = emptyList(), selectedAudioTrackId = null, selectedSubTrackId = null) }
        scope.launch {
            assertCommandThread()
            currentFilePath = path
            _zoomState.value = 0.0
            currentPanX = 0.0
            currentPanY = 0.0
            _state.update { it.copy(videoCodec = null, fallback = false, aspect = aspect) }
            if (handle != 0L) {
                val options = mutableListOf<String>()
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

                if (speed != 1.0) {
                    val spd = String.format(java.util.Locale.US, "%.2f", speed)
                    options.add("speed=$spd")
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

                val cmd = if (options.isNotEmpty()) {
                    val optStr = options.joinToString(",")
                    Log.i("VP", "Loading $path with options: $optStr")
                    arrayOf("loadfile", path, "replace", "-1", optStr)
                } else {
                    Log.i("VP", "Loading $path from start without options")
                    arrayOf("loadfile", path, "replace")
                }
                val res = MpvNative.command(handle, cmd)
                Log.i("VP", "loadfile res=$res for $path")
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
        val glslShadersValue = currentShaderPreset.toGlslShadersProperty(playbackConfig.mode, shaderDirectory)
        MpvNative.setPropertyString(handle, "glsl-shaders", glslShadersValue)
        Log.i("VP", "Applied shader preset $currentShaderPreset (mode=${playbackConfig.mode}): $glslShadersValue")
    }

    fun setHwdec(hwdec: String) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyString(handle, "hwdec", hwdec)
                Log.i("VP", "hwdec set to $hwdec")
            }
        }
    }

    fun reconfigure(newConfig: PlaybackConfig) {
        executeCommand {
            assertCommandThread()
            if (playbackConfig == newConfig) return@executeCommand
            Log.i("VP", "Reconfiguring engine: ${playbackConfig.efficientPath} -> ${newConfig.efficientPath}")
            val curPos = if (handle != 0L) {
                MpvNative.getPropertyDouble(handle, "time-pos")?.let { (it * 1000.0).toLong() } ?: _position.value
            } else {
                _position.value
            }
            val curPath = currentFilePath
            val wasPaused = _state.value.paused
            val surface = attachedSurface

            if (handle != 0L) {
                MpvNative.detachSurface(handle)
                MpvNative.destroy(handle)
                handle = 0L
            }

            playbackConfig = newConfig
            initHandle()

            if (surface != null) {
                MpvNative.attachSurface(handle, surface)
            }

            if (curPath.isNotEmpty()) {
                val options = mutableListOf<String>()
                if (curPos > 0) {
                    val sec = String.format(java.util.Locale.US, "%.3f", curPos / 1000.0)
                    options.add("start=$sec")
                }
                val curAid = _state.value.selectedAudioTrackId
                if (curAid != null) {
                    options.add("aid=$curAid")
                }
                val curSid = _state.value.selectedSubTrackId
                if (curSid != null) {
                    options.add("sid=$curSid")
                } else {
                    options.add("sid=no")
                }
                val curSpeed = _state.value.speed
                if (curSpeed != 1.0) {
                    options.add("speed=${String.format(java.util.Locale.US, "%.2f", curSpeed)}")
                }
                val curAspect = _state.value.aspect ?: "fit"
                when (curAspect.lowercase()) {
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
                val cmd = if (options.isNotEmpty()) {
                    val optStr = options.joinToString(",")
                    Log.i("VP", "Reconfiguring: loading $curPath with options=$optStr")
                    arrayOf("loadfile", curPath, "replace", "-1", optStr)
                } else {
                    arrayOf("loadfile", curPath, "replace")
                }
                MpvNative.command(handle, cmd)
                if (wasPaused) {
                    MpvNative.setPropertyBoolean(handle, "pause", true)
                }
            }
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
        executeCommand {
            assertCommandThread()
            pendingTrackSelection = PendingTrackSelection(fileChoice, folderChoice, preferences)
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

    fun setSpeed(speed: Double) {
        executeCommand {
            assertCommandThread()
            if (handle != 0L) {
                val clamped = speed.coerceIn(0.25, 4.0)
                MpvNative.setPropertyDouble(handle, "speed", clamped)
                Log.i("VP", "setSpeed: speed=$clamped")
                _state.update { it.copy(speed = clamped) }
            }
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

    suspend fun currentPositionMs(): Long = withContext(commandDispatcher) {
        assertCommandThread()
        if (handle != 0L) {
            val posSec = MpvNative.getPropertyDouble(handle, "time-pos")
            if (posSec != null && !posSec.isNaN()) {
                val ms = (posSec * 1000.0).toLong()
                _position.value = ms
                return@withContext ms
            }
        }
        _position.value
    }

    fun seekRelative(deltaSeconds: Double, exact: Boolean = false) {
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
        if (released) return
        released = true
        statsJob?.cancel()
        statsJob = null
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
                val future = commandExecutor.submit(task)
                future.get(5, TimeUnit.SECONDS)
                commandExecutor.shutdown()
            } else {
                commandExecutor.submit(task)
                commandExecutor.shutdown()
            }
        } catch (e: RejectedExecutionException) {
            Log.w("VP", "Command executor already stopped during release: ${e.message}")
        }
    }
}
