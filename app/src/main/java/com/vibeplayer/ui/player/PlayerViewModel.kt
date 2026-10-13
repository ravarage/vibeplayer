package com.vibeplayer.ui.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.db.FolderSettings
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.data.prefs.ShaderPresetOption
import com.vibeplayer.data.prefs.SubtitlePreferenceMode
import com.vibeplayer.player.FileTrackChoice
import com.vibeplayer.player.FolderTrackChoice
import com.vibeplayer.player.LanguagePreferences
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.ShaderPreset
import com.vibeplayer.player.SubtitleMode
import com.vibeplayer.player.TrackSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    data class Neighbours(val previous: Boolean = false, val next: Boolean = false)

    val playerEngine = PlayerEngine()
    private val db = AppDatabase.getInstance(application)
    private val dao = db.fileSettingsDao()
    private val folderDao = db.folderSettingsDao()
    private val appSettingsRepository = AppSettingsRepository(application)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentKey: String? = null
    @Volatile private var currentVideoPath: String? = null
    @Volatile private var currentFolderPath: String? = null
    private var periodicSaveJob: Job? = null
    private var loadJob: Job? = null
    @Volatile private var loadGeneration = 0L

    // Path of the file being played; changes when auto-play advances to the next file in the folder.
    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()
    private val _neighbours = MutableStateFlow(Neighbours())
    val neighbours: StateFlow<Neighbours> = _neighbours.asStateFlow()

    init {
        // Periodic save only while actively playing; pause transitions save below.
        periodicSaveJob = viewModelScope.launch(Dispatchers.IO) {
            playerEngine.state.map { it.paused }.distinctUntilChanged().collectLatest { paused ->
                if (!paused) {
                    while (isActive) {
                        delay(10_000L)
                        saveCurrentPosition()
                    }
                }
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

        // Auto-play the next file in the folder when the current one reaches its end
        viewModelScope.launch {
            var wasEof = false
            playerEngine.state.collect { state ->
                if (state.eof && !wasEof && state.durationMs > 0L) {
                    onPlaybackEnded()
                }
                wasEof = state.eof
            }
        }

        val shadersDir = File(application.filesDir, "shaders")
        playerEngine.setShaderDirectory(shadersDir.absolutePath)

        viewModelScope.launch(Dispatchers.IO) {
            ensureShadersExtracted(application)
        }

        viewModelScope.launch {
            appSettingsRepository.appSettingsFlow
                .map { it.shaderPreset }
                .distinctUntilChanged()
                .collect { presetOption ->
                    playerEngine.setShaderPreset(mapShaderPreset(presetOption))
                }
        }
    }

    private fun mapShaderPreset(option: ShaderPresetOption): ShaderPreset = when (option) {
        ShaderPresetOption.NONE -> ShaderPreset.NONE
        ShaderPresetOption.ANIME4K_A -> ShaderPreset.ANIME4K_A
        ShaderPresetOption.ANIME4K_B -> ShaderPreset.ANIME4K_B
        ShaderPresetOption.ANIME4K_C -> ShaderPreset.ANIME4K_C
        ShaderPresetOption.SNAPDRAGON_GSR -> ShaderPreset.SNAPDRAGON_GSR
    }

    private fun ensureShadersExtracted(context: android.content.Context): File {
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

    private suspend fun onPlaybackEnded() {
        val endedPath = currentVideoPath ?: return
        if (!appSettingsRepository.appSettingsFlow.first().autoPlayNext) return
        val next = withContext(Dispatchers.IO) { FolderPlaylist.next(endedPath) }
        if (next == null) {
            Log.i("VP", "Auto-play: no next file after $endedPath")
            return
        }
        if (currentVideoPath != endedPath) return // the user opened something else meanwhile
        Log.i("VP", "Auto-play: $endedPath ended -> $next")
        // keep-open pauses at the end of a file, so the next one is told to play after it loads
        openVideo(next, startFromBeginning = true, playAfterLoad = true)
    }

    /** Plays the previous/next file of the current folder; returns false if there is none. */
    fun skipToNeighbour(forward: Boolean) {
        val from = currentVideoPath ?: return
        viewModelScope.launch {
            // Snapshot both identifiers before any suspend point; a later file switch must not
            // redirect this save to the new file's Room key (review item 16).
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
        _neighbours.value = Neighbours()
        viewModelScope.launch(Dispatchers.IO) {
            val files = if (path.startsWith("/")) FolderPlaylist.list(path) else emptyList()
            val index = files.indexOf(File(path).absolutePath)
            val updated = Neighbours(previous = index > 0, next = index >= 0 && index < files.lastIndex)
            withContext(Dispatchers.Main.immediate) {
                if (currentVideoPath == path) _neighbours.value = updated
            }
        }
        val key = computeKey(identityKey ?: path, sizeBytes)

        loadJob = viewModelScope.launch {
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

            playerEngine.setShaderPreset(mapShaderPreset(appSettings.shaderPreset))

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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
        val pos = playerEngine.position.value
        val state = playerEngine.state.value
        if (generation != loadGeneration || key != currentKey) return
        saveScope.launch {
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
                    Log.i("VP", "Saved position $pos ms (dur=$finalDuration ms, aid=$audioTrackId, sid=$subTrackId, speed=$speed) for key $key")
                }
            }
        }
    }

    private suspend fun savePositionSnapshot(key: String, positionMs: Long, state: com.vibeplayer.player.PlayerState) {
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

    override fun onCleared() {
        super.onCleared()
        periodicSaveJob?.cancel()
        saveCurrentPosition()
        playerEngine.release()
    }
}
