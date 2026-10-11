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

data class AppSettings(
    val playbackMode: PlaybackModeOption = PlaybackModeOption.EFFICIENT,
    val renderer: RendererOption = RendererOption.OPENGL,
    val gesturesEnabled: Boolean = true,
    val skipSeconds: Int = 10,
    val backgroundAudio: Boolean = false,
    val shaderPreset: ShaderPresetOption = ShaderPresetOption.NONE
)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) {
    companion object {
        private val KEY_PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        private val KEY_RENDERER = stringPreferencesKey("renderer")
        private val KEY_GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
        private val KEY_SKIP_SECONDS = intPreferencesKey("skip_seconds")
        private val KEY_BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
        private val KEY_SHADER_PRESET = stringPreferencesKey("shader_preset")
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

        AppSettings(
            playbackMode = playbackMode,
            renderer = renderer,
            gesturesEnabled = prefs[KEY_GESTURES_ENABLED] ?: true,
            skipSeconds = prefs[KEY_SKIP_SECONDS] ?: 10,
            backgroundAudio = prefs[KEY_BACKGROUND_AUDIO] ?: false,
            shaderPreset = shaderPreset
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

    suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLAYBACK_MODE] = settings.playbackMode.name
            prefs[KEY_RENDERER] = settings.renderer.name
            prefs[KEY_GESTURES_ENABLED] = settings.gesturesEnabled
            prefs[KEY_SKIP_SECONDS] = settings.skipSeconds
            prefs[KEY_BACKGROUND_AUDIO] = settings.backgroundAudio
            prefs[KEY_SHADER_PRESET] = settings.shaderPreset.name
        }
    }
}
