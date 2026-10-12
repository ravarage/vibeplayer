package com.vibeplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PlaybackModeOption(val displayName: String, val description: String) {
    EFFICIENT("Efficient", "Hardware decoding via MediaCodec, lowest battery, path B (GLES)"),
    QUALITY("Quality", "Copy-back decoding for custom shaders and high quality scaling")
}

enum class RendererOption(val displayName: String, val description: String) {
    OPENGL("OpenGL ES", "Standard GLES 3.2 renderer"),
    VULKAN("Vulkan", "Vulkan API renderer (P6 benchmark)")
}

enum class ShaderPresetOption(val displayName: String, val description: String) {
    NONE("None", "No post-processing shaders"),
    ANIME4K("Anime4K Light", "Upscaling and line reconstruction for animation"),
    GSR("Snapdragon GSR", "Qualcomm Snapdragon Game Super Resolution")
}

enum class SubtitlePreferenceMode(val displayName: String, val description: String) {
    FORCED_ONLY("Forced / Signs & Songs only", "Only show forced or signs/songs subtitles when audio is in a preferred language"),
    FULL("Full subtitles", "Always show full subtitles in the preferred language"),
    OFF("Off", "Turn off subtitles when audio is in a preferred language")
}

data class AppSettings(
    val playbackMode: PlaybackModeOption = PlaybackModeOption.EFFICIENT,
    val renderer: RendererOption = RendererOption.OPENGL,
    val gesturesEnabled: Boolean = true,
    val skipSeconds: Int = 10,
    val backgroundAudio: Boolean = false,
    val autoPlayNext: Boolean = true,
    val shaderPreset: ShaderPresetOption = ShaderPresetOption.NONE,
    val preferredAudioLanguages: List<String> = listOf("en"),
    val preferredSubtitleLanguages: List<String> = listOf("en"),
    val subtitleMode: SubtitlePreferenceMode = SubtitlePreferenceMode.FORCED_ONLY
)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) {
    companion object {
        private val KEY_PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        private val KEY_RENDERER = stringPreferencesKey("renderer")
        private val KEY_GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
        private val KEY_SKIP_SECONDS = intPreferencesKey("skip_seconds")
        private val KEY_BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val KEY_SHADER_PRESET = stringPreferencesKey("shader_preset")
        private val KEY_PREFERRED_AUDIO_LANGS = stringPreferencesKey("preferred_audio_langs")
        private val KEY_PREFERRED_SUB_LANGS = stringPreferencesKey("preferred_sub_langs")
        private val KEY_SUBTITLE_MODE = stringPreferencesKey("subtitle_mode")
    }

    val appSettingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val playbackMode = try {
            PlaybackModeOption.valueOf(prefs[KEY_PLAYBACK_MODE] ?: PlaybackModeOption.EFFICIENT.name)
        } catch (_: Exception) {
            PlaybackModeOption.EFFICIENT
        }

        val renderer = try {
            RendererOption.valueOf(prefs[KEY_RENDERER] ?: RendererOption.OPENGL.name)
        } catch (_: Exception) {
            RendererOption.OPENGL
        }

        val shaderPreset = try {
            ShaderPresetOption.valueOf(prefs[KEY_SHADER_PRESET] ?: ShaderPresetOption.NONE.name)
        } catch (_: Exception) {
            ShaderPresetOption.NONE
        }

        val audioLangsStr = prefs[KEY_PREFERRED_AUDIO_LANGS] ?: "en"
        val preferredAudioLanguages = audioLangsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("en") }

        val subLangsStr = prefs[KEY_PREFERRED_SUB_LANGS] ?: "en"
        val preferredSubtitleLanguages = subLangsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("en") }

        val subtitleMode = try {
            SubtitlePreferenceMode.valueOf(prefs[KEY_SUBTITLE_MODE] ?: SubtitlePreferenceMode.FORCED_ONLY.name)
        } catch (_: Exception) {
            SubtitlePreferenceMode.FORCED_ONLY
        }

        AppSettings(
            playbackMode = playbackMode,
            renderer = renderer,
            gesturesEnabled = prefs[KEY_GESTURES_ENABLED] ?: true,
            skipSeconds = prefs[KEY_SKIP_SECONDS] ?: 10,
            backgroundAudio = prefs[KEY_BACKGROUND_AUDIO] ?: false,
            autoPlayNext = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
            shaderPreset = shaderPreset,
            preferredAudioLanguages = preferredAudioLanguages,
            preferredSubtitleLanguages = preferredSubtitleLanguages,
            subtitleMode = subtitleMode
        )
    }

    suspend fun setPlaybackMode(mode: PlaybackModeOption) {
        context.settingsDataStore.edit { it[KEY_PLAYBACK_MODE] = mode.name }
    }

    suspend fun setRenderer(renderer: RendererOption) {
        context.settingsDataStore.edit { it[KEY_RENDERER] = renderer.name }
    }

    suspend fun setGesturesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_GESTURES_ENABLED] = enabled }
    }

    suspend fun setSkipSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[KEY_SKIP_SECONDS] = seconds }
    }

    suspend fun setBackgroundAudio(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_BACKGROUND_AUDIO] = enabled }
    }

    suspend fun setShaderPreset(preset: ShaderPresetOption) {
        context.settingsDataStore.edit { it[KEY_SHADER_PRESET] = preset.name }
    }

    suspend fun setPreferredAudioLanguages(langs: List<String>) {
        context.settingsDataStore.edit { it[KEY_PREFERRED_AUDIO_LANGS] = langs.joinToString(",") }
    }

    suspend fun setPreferredSubtitleLanguages(langs: List<String>) {
        context.settingsDataStore.edit { it[KEY_PREFERRED_SUB_LANGS] = langs.joinToString(",") }
    }

    suspend fun setSubtitleMode(mode: SubtitlePreferenceMode) {
        context.settingsDataStore.edit { it[KEY_SUBTITLE_MODE] = mode.name }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLAYBACK_MODE] = settings.playbackMode.name
            prefs[KEY_RENDERER] = settings.renderer.name
            prefs[KEY_GESTURES_ENABLED] = settings.gesturesEnabled
            prefs[KEY_SKIP_SECONDS] = settings.skipSeconds
            prefs[KEY_BACKGROUND_AUDIO] = settings.backgroundAudio
            prefs[KEY_AUTO_PLAY_NEXT] = settings.autoPlayNext
            prefs[KEY_SHADER_PRESET] = settings.shaderPreset.name
            prefs[KEY_PREFERRED_AUDIO_LANGS] = settings.preferredAudioLanguages.joinToString(",")
            prefs[KEY_PREFERRED_SUB_LANGS] = settings.preferredSubtitleLanguages.joinToString(",")
            prefs[KEY_SUBTITLE_MODE] = settings.subtitleMode.name
        }
    }
}
