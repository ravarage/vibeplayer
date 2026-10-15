package com.vibeplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class PlaybackModeOption(val displayName: String, val description: String) {
    EFFICIENT("Efficient", "Hardware decoding via MediaCodec, lowest battery, path B (GLES)"),
    QUALITY("Quality", "Copy-back decoding for custom shaders and high quality scaling")
}

enum class RendererOption(val displayName: String, val description: String) {
    AUTO("Auto (Recommended)", "Best configuration per format, chosen before opening"),
    VULKAN("Vulkan (Experimental)", "Vulkan API renderer (experimental driver)"),
    OPENGL("OpenGL ES", "Standard GLES 3.2 renderer")
}

enum class ShaderPresetOption(val displayName: String, val description: String) {
    NONE("None", "No post-processing shaders"),
    ANIME4K_A("Anime4K A (Fast)", "Restore CNN + Upscale CNN x2 for fast perceptual anime upscaling (Quality mode only)"),
    ANIME4K_B("Anime4K B (Fast)", "Restore CNN Soft + Upscale CNN x2 for softer line reconstruction (Quality mode only)"),
    ANIME4K_C("Anime4K C (Fast)", "Upscale Denoise + Upscale CNN x2 for sharp high-detail anime (Quality mode only)"),
    SNAPDRAGON_GSR("Snapdragon GSR", "Qualcomm Snapdragon Game Super Resolution spatial upscaler (Quality mode only)")
}

enum class SubtitlePreferenceMode(val displayName: String, val description: String) {
    FORCED_ONLY("Forced / Signs & Songs only", "Only show forced or signs/songs subtitles when audio is in a preferred language"),
    FULL("Full subtitles", "Always show full subtitles in the preferred language"),
    OFF("Off", "Turn off subtitles when audio is in a preferred language")
}

data class AppSettings(
    val playbackMode: PlaybackModeOption = PlaybackModeOption.EFFICIENT,
    val renderer: RendererOption = RendererOption.AUTO,
    val gesturesEnabled: Boolean = true,
    val skipSeconds: Int = 10,
    val holdSpeed: Double = 2.0,
    val backgroundAudio: Boolean = false,
    val autoPlayNext: Boolean = true,
    val shaderPreset: ShaderPresetOption = ShaderPresetOption.NONE,
    val preferredAudioLanguages: List<String> = listOf("en"),
    val preferredSubtitleLanguages: List<String> = listOf("en"),
    val subtitleMode: SubtitlePreferenceMode = SubtitlePreferenceMode.FORCED_ONLY,
    val subtitleScale: Double = 1.0,
    val subtitlePos: Int = 100,
    val subtitleAssOverride: Boolean = false,
    val matchDisplayRefreshRate: Boolean = true,
    /** P10-06: ADPF performance hint session while playing (experimental, default off). */
    val performanceHints: Boolean = false,
    /** P10-07: Collect system profiles via ProfilingManager triggers (default off). */
    val collectSystemProfiles: Boolean = false,
    /** P10-09: HDR output for HDR content on Vulkan gpu-next (experimental, default off). */
    val hdrOutput: Boolean = false
) {

    fun subtitleStyle() = com.vibeplayer.player.SubtitleStyle(subtitleScale, subtitlePos, subtitleAssOverride).normalized()
}

object SettingsMigrationPolicy {
    fun resolveMigratedRenderer(rawRenderer: String?, isMigrated: Boolean): RendererOption {
        return when {
            !isMigrated -> {
                if (rawRenderer == null || rawRenderer == "OPENGL") {
                    RendererOption.AUTO
                } else {
                    try {
                        RendererOption.valueOf(rawRenderer)
                    } catch (_: Exception) {
                        RendererOption.AUTO
                    }
                }
            }
            rawRenderer == null -> RendererOption.AUTO
            else -> {
                try {
                    RendererOption.valueOf(rawRenderer)
                } catch (_: Exception) {
                    RendererOption.AUTO
                }
            }
        }
    }

    fun shouldUpdateRawRendererOnMigration(rawRenderer: String?, isMigrated: Boolean): Boolean {
        if (isMigrated) return false
        return rawRenderer == null || rawRenderer == "OPENGL"
    }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class AppSettingsRepository(private val context: Context) {
    companion object {
        private val KEY_PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        private val KEY_RENDERER = stringPreferencesKey("renderer")
        private val KEY_RENDERER_MIGRATED_TO_AUTO = booleanPreferencesKey("renderer_migrated_to_auto_v1")
        private val KEY_GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
        private val KEY_SKIP_SECONDS = intPreferencesKey("skip_seconds")
        private val KEY_HOLD_SPEED = doublePreferencesKey("hold_speed")
        private val KEY_BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val KEY_SHADER_PRESET = stringPreferencesKey("shader_preset")
        private val KEY_PREFERRED_AUDIO_LANGS = stringPreferencesKey("preferred_audio_langs")
        private val KEY_PREFERRED_SUB_LANGS = stringPreferencesKey("preferred_sub_langs")
        private val KEY_SUBTITLE_MODE = stringPreferencesKey("subtitle_mode")
        private val KEY_SUB_SCALE = doublePreferencesKey("sub_scale")
        private val KEY_SUB_POS = intPreferencesKey("sub_pos")
        private val KEY_SUB_ASS_OVERRIDE = booleanPreferencesKey("sub_ass_override")
        private val KEY_NOTIFICATION_PERMISSION_DENIED = booleanPreferencesKey("notification_permission_denied")
        private val KEY_MATCH_DISPLAY_REFRESH_RATE = booleanPreferencesKey("match_display_refresh_rate")
        private val KEY_PERFORMANCE_HINTS = booleanPreferencesKey("performance_hints")
        private val KEY_HDR_OUTPUT = booleanPreferencesKey("hdr_output")
        private val KEY_COLLECT_SYSTEM_PROFILES = booleanPreferencesKey("collect_system_profiles")
    }


    val notificationPermissionDeniedFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            prefs[KEY_NOTIFICATION_PERMISSION_DENIED] ?: false
        }

    suspend fun setNotificationPermissionDenied(denied: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOTIFICATION_PERMISSION_DENIED] = denied }
    }

    suspend fun migrateRendererDefaultIfNeeded() {
        context.settingsDataStore.edit { prefs ->
            val isMigrated = prefs[KEY_RENDERER_MIGRATED_TO_AUTO] ?: false
            if (!isMigrated) {
                val raw = prefs[KEY_RENDERER]
                if (SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(raw, isMigrated)) {
                    prefs[KEY_RENDERER] = RendererOption.AUTO.name
                }
                prefs[KEY_RENDERER_MIGRATED_TO_AUTO] = true
            }
        }
    }

    val appSettingsFlow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
        val playbackMode = try {
            PlaybackModeOption.valueOf(prefs[KEY_PLAYBACK_MODE] ?: PlaybackModeOption.EFFICIENT.name)
        } catch (_: Exception) {
            PlaybackModeOption.EFFICIENT
        }

        val isMigrated = prefs[KEY_RENDERER_MIGRATED_TO_AUTO] ?: false
        val rawRenderer = prefs[KEY_RENDERER]
        val renderer = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer, isMigrated)

        val shaderPreset = try {
            when (val name = prefs[KEY_SHADER_PRESET]) {
                "ANIME4K" -> ShaderPresetOption.ANIME4K_A
                "GSR" -> ShaderPresetOption.SNAPDRAGON_GSR
                null -> ShaderPresetOption.NONE
                else -> ShaderPresetOption.valueOf(name)
            }
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
            holdSpeed = com.vibeplayer.ui.player.HoldGesture.normalize(prefs[KEY_HOLD_SPEED] ?: 2.0),
            backgroundAudio = prefs[KEY_BACKGROUND_AUDIO] ?: false,
            autoPlayNext = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
            shaderPreset = shaderPreset,
            preferredAudioLanguages = preferredAudioLanguages,
            preferredSubtitleLanguages = preferredSubtitleLanguages,
            subtitleMode = subtitleMode,
            subtitleScale = com.vibeplayer.player.SubtitleStyle.normalizeScale(prefs[KEY_SUB_SCALE] ?: 1.0),
            subtitlePos = com.vibeplayer.player.SubtitleStyle.normalizePos(prefs[KEY_SUB_POS] ?: 100),
            subtitleAssOverride = prefs[KEY_SUB_ASS_OVERRIDE] ?: false,
            matchDisplayRefreshRate = prefs[KEY_MATCH_DISPLAY_REFRESH_RATE] ?: true,
            performanceHints = prefs[KEY_PERFORMANCE_HINTS] ?: false,
            collectSystemProfiles = prefs[KEY_COLLECT_SYSTEM_PROFILES] ?: false,
            hdrOutput = prefs[KEY_HDR_OUTPUT] ?: false
        )
    }


    suspend fun setPlaybackMode(mode: PlaybackModeOption) {
        context.settingsDataStore.edit { it[KEY_PLAYBACK_MODE] = mode.name }
    }

    suspend fun setRenderer(renderer: RendererOption) {
        context.settingsDataStore.edit {
            it[KEY_RENDERER] = renderer.name
            it[KEY_RENDERER_MIGRATED_TO_AUTO] = true
        }
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

    suspend fun setSubtitleScale(v: Double) {
        context.settingsDataStore.edit { it[KEY_SUB_SCALE] = com.vibeplayer.player.SubtitleStyle.normalizeScale(v) }
    }

    suspend fun setSubtitlePos(v: Int) {
        context.settingsDataStore.edit { it[KEY_SUB_POS] = com.vibeplayer.player.SubtitleStyle.normalizePos(v) }
    }

    suspend fun setSubtitleAssOverride(on: Boolean) {
        context.settingsDataStore.edit { it[KEY_SUB_ASS_OVERRIDE] = on }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLAYBACK_MODE] = settings.playbackMode.name
            prefs[KEY_RENDERER] = settings.renderer.name
            prefs[KEY_RENDERER_MIGRATED_TO_AUTO] = true
            prefs[KEY_GESTURES_ENABLED] = settings.gesturesEnabled
            prefs[KEY_SKIP_SECONDS] = settings.skipSeconds
            prefs[KEY_HOLD_SPEED] = settings.holdSpeed
            prefs[KEY_BACKGROUND_AUDIO] = settings.backgroundAudio
            prefs[KEY_AUTO_PLAY_NEXT] = settings.autoPlayNext
            prefs[KEY_SHADER_PRESET] = settings.shaderPreset.name
            prefs[KEY_PREFERRED_AUDIO_LANGS] = settings.preferredAudioLanguages.joinToString(",")
            prefs[KEY_PREFERRED_SUB_LANGS] = settings.preferredSubtitleLanguages.joinToString(",")
            prefs[KEY_SUBTITLE_MODE] = settings.subtitleMode.name
            prefs[KEY_SUB_SCALE] = settings.subtitleScale
            prefs[KEY_SUB_POS] = settings.subtitlePos
            prefs[KEY_SUB_ASS_OVERRIDE] = settings.subtitleAssOverride
            prefs[KEY_MATCH_DISPLAY_REFRESH_RATE] = settings.matchDisplayRefreshRate
            prefs[KEY_PERFORMANCE_HINTS] = settings.performanceHints
            prefs[KEY_HDR_OUTPUT] = settings.hdrOutput
            prefs[KEY_COLLECT_SYSTEM_PROFILES] = settings.collectSystemProfiles
        }
    }

    suspend fun setCollectSystemProfiles(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_COLLECT_SYSTEM_PROFILES] = enabled }
    }
}

