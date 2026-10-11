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
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerEngine {

    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VP-Command").apply { isDaemon = true }
    }
    private val commandDispatcher = commandExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(commandDispatcher + SupervisorJob())

    private var handle: Long = 0L

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _stats = MutableStateFlow(PlayerStats())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    private val eventCallback = object : MpvNative.EventCallback {
        override fun onPropertyChange(name: String, value: Any?) {
            when (name) {
                "pause" -> {
                    val paused = when (value) {
                        is Boolean -> value
                        is Number -> value.toLong() != 0L
                        is String -> value == "yes"
                        else -> false
                    }
                    _state.update { it.copy(paused = paused) }
                    Log.i("VP", "Flow update - pause: $paused")
                }
                "duration" -> {
                    val durationMs = when (value) {
                        is Number -> (value.toDouble() * 1000).toLong()
                        is String -> (value.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                        else -> 0L
                    }
                    _state.update { it.copy(durationMs = durationMs) }
                    Log.i("VP", "Flow update - duration: $durationMs ms")
                }
                "time-pos" -> {
                    val posMs = when (value) {
                        is Number -> (value.toDouble() * 1000).toLong()
                        is String -> (value.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                        else -> 0L
                    }
                    _position.value = posMs
                    Log.i("VP", "Flow update - position: $posMs ms")
                }
                "hwdec-current" -> {
                    val hwdec = (value as? String) ?: "no"
                    val fallback = hwdec == "no"
                    _state.update { it.copy(hwdecCurrent = hwdec, fallback = fallback) }
                    Log.i("VP", "Flow update - hwdec-current: $hwdec")
                }
                "eof-reached" -> {
                    val eof = when (value) {
                        is Boolean -> value
                        is String -> value == "yes"
                        else -> false
                    }
                    _state.update { it.copy(eof = eof) }
                    Log.i("VP", "Flow update - eof: $eof")
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
            }
        }

        override fun onEvent(id: Int) {
            when (id) {
                MpvEvent.PLAYBACK_RESTART -> {
                    Log.i("VP-Perf", "playback-restart at ${System.currentTimeMillis()}")
                }
                MpvEvent.END_FILE -> {
                    Log.i("VP", "mpv event end-file")
                }
                MpvEvent.FILE_LOADED -> {
                    Log.i("VP", "mpv event file-loaded")
                }
                else -> {
                    Log.d("VP", "onEvent: $id")
                }
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
        handle = MpvNative.create()
        if (handle == 0L) {
            Log.e("VP", "Failed to create mpv handle")
            return
        }

        MpvNative.setEventCallback(handle, eventCallback)

        // Default to Path B (efficient mode default per ARCHITECTURE.md)
        MpvNative.setOptionString(handle, "vo", "gpu")
        MpvNative.setOptionString(handle, "gpu-context", "android")
        MpvNative.setOptionString(handle, "hwdec", "mediacodec")
        MpvNative.setOptionString(handle, "ao", "audiotrack")
        MpvNative.setOptionString(handle, "keep-open", "yes")
        MpvNative.setOptionString(handle, "vd-lavc-threads", "0")
        MpvNative.setOptionString(handle, "hwdec-codecs", "h264,hevc,vp9")

        val res = MpvNative.initialize(handle)
        Log.i("VP", "MpvNative.initialize returned $res")

        MpvNative.observeProperty(handle, "pause", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, "duration", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, "time-pos", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, "hwdec-current", MpvFormat.STRING)
        MpvNative.observeProperty(handle, "eof-reached", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, "paused-for-cache", MpvFormat.FLAG)
        MpvNative.observeProperty(handle, "speed", MpvFormat.DOUBLE)
        MpvNative.observeProperty(handle, "dwidth", MpvFormat.INT64)
        MpvNative.observeProperty(handle, "dheight", MpvFormat.INT64)
    }

    fun attachSurface(surface: Surface) {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.attachSurface(handle, surface)
                Log.i("VP", "Surface attached to mpv handle")
            }
        }
    }

    fun detachSurface() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.detachSurface(handle)
                Log.i("VP", "Surface detached from mpv handle")
            }
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setSurfaceSize(handle, width, height)
                Log.i("VP", "Surface size updated to ${width}x$height")
            }
        }
    }

    fun load(path: String) {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                val res = MpvNative.command(handle, arrayOf("loadfile", path))
                Log.i("VP", "loadfile res=$res for $path")
            }
        }
    }

    fun play() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyBoolean(handle, "pause", false)
            }
        }
    }

    fun pause() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setPropertyBoolean(handle, "pause", true)
            }
        }
    }

    fun togglePause() {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.command(handle, arrayOf("cycle", "pause"))
            }
        }
    }

    fun seekTo(positionMs: Long, exact: Boolean = false) {
        scope.launch {
            assertCommandThread()
            if (handle != 0L) {
                val sec = (positionMs.toDouble() / 1000.0).toString()
                val flag = if (exact) "exact" else "keyframes"
                MpvNative.command(handle, arrayOf("seek", sec, "absolute+$flag"))
            }
        }
    }

    fun release(sync: Boolean = false) {
        val task = Runnable {
            assertCommandThread()
            if (handle != 0L) {
                MpvNative.setEventCallback(handle, null)
                MpvNative.destroy(handle)
                handle = 0L
                Log.i("VP", "PlayerEngine handle destroyed")
            }
        }
        if (sync && Looper.myLooper() != Looper.getMainLooper()) {
            val future = commandExecutor.submit(task)
            try {
                future.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w("VP", "Exception waiting for handle destroy: ${e.message}")
            }
            commandExecutor.shutdown()
        } else {
            commandExecutor.submit(task)
            commandExecutor.shutdown()
        }
    }
}
