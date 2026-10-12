package com.vibeplayer.ui.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playerEngine = PlayerEngine()
    private val db = AppDatabase.getInstance(application)
    private val dao = db.fileSettingsDao()

    private var currentKey: String? = null
    private var currentVideoPath: String? = null
    private var periodicSaveJob: Job? = null

    init {
        // Periodic save every 10 seconds while playing
        periodicSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                saveCurrentPosition()
            }
        }

        // Save when playback pauses
        viewModelScope.launch {
            var wasPaused = false
            playerEngine.state.collect { state ->
                if (state.paused && !wasPaused) {
                    saveCurrentPosition()
                }
                wasPaused = state.paused
            }
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

    fun openVideo(path: String, sizeBytes: Long = 0L) {
        currentVideoPath = path
        val key = computeKey(path, sizeBytes)
        currentKey = key

        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) {
                dao.getByKey(key) ?: dao.getByKey(path)
            }

            val startPositionMs = if (settings != null) {
                val dur = settings.durationMs
                val pos = settings.positionMs
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

            playerEngine.load(path, startPositionMs)
        }
    }

    fun saveCurrentPosition(forceSync: Boolean = false) {
        val key = currentKey ?: return
        val pos = playerEngine.position.value
        val state = playerEngine.state.value
        val dur = state.durationMs

        if (pos <= 0L && dur <= 0L) return

        val saveTask = suspend {
            withContext(Dispatchers.IO) {
                val existing = dao.getByKey(key)
                val finalDuration = if (dur > 0L) dur else (existing?.durationMs ?: 0L)
                val updated = existing?.copy(
                    positionMs = pos,
                    durationMs = finalDuration,
                    lastPlayedAt = System.currentTimeMillis()
                ) ?: FileSettings(
                    key = key,
                    positionMs = pos,
                    durationMs = finalDuration,
                    lastPlayedAt = System.currentTimeMillis()
                )
                dao.upsert(updated)
                Log.i("VP", "Saved position $pos ms (duration $finalDuration ms) for key $key")
            }
        }

        if (forceSync) {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                saveTask()
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                saveTask()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        periodicSaveJob?.cancel()
        saveCurrentPosition(forceSync = true)
        playerEngine.release()
    }
}
