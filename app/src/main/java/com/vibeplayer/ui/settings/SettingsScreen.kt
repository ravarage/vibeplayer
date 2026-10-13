package com.vibeplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.data.prefs.AppSettings
import com.vibeplayer.data.prefs.PlaybackModeOption
import com.vibeplayer.data.prefs.RendererOption
import com.vibeplayer.data.prefs.ShaderPresetOption
import com.vibeplayer.data.prefs.SubtitlePreferenceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var showPlaybackModeDialog by remember { mutableStateOf(false) }
    var showRendererDialog by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var showShaderDialog by remember { mutableStateOf(false) }
    var showAudioLangDialog by remember { mutableStateOf(false) }
    var showSubLangDialog by remember { mutableStateOf(false) }
    var showSubtitleModeDialog by remember { mutableStateOf(false) }

    val availableGpuApis = remember { com.vibeplayer.player.PlayerEngine.getGpuApiChoices() }
    val vulkanSupported = remember(availableGpuApis) {
        availableGpuApis.any { it.equals("vulkan", ignoreCase = true) }
    }
    val rendererOptions = remember(vulkanSupported) {
        RendererOption.entries.filter { it != RendererOption.VULKAN || vulkanSupported }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Vibe Player Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Category: Playback Engine
            SettingsSectionHeader(title = "Playback Engine")

            SettingsClickableRow(
                title = "Playback Mode",
                subtitle = settings.playbackMode.displayName,
                description = settings.playbackMode.description,
                onClick = { showPlaybackModeDialog = true }
            )

            SettingsClickableRow(
                title = "Video Renderer",
                subtitle = settings.renderer.displayName,
                description = settings.renderer.description,
                onClick = { showRendererDialog = true }
            )

            SettingsSwitchRow(
                title = "Background Audio",
                description = "Continue playing audio when app is minimized or screen is off",
                checked = settings.backgroundAudio,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(backgroundAudio = checked))
                }
            )

            SettingsSwitchRow(
                title = "Auto-play Next",
                description = "When a video ends, play the next file in the same folder",
                checked = settings.autoPlayNext,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(autoPlayNext = checked))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Category: Controls & Gestures
            SettingsSectionHeader(title = "Controls & Gestures")

            SettingsSwitchRow(
                title = "Touch Gestures",
                description = "Enable swipe controls for volume, brightness, and seek",
                checked = settings.gesturesEnabled,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(gesturesEnabled = checked))
                }
            )

            SettingsClickableRow(
                title = "Double Tap Skip Interval",
                subtitle = "${settings.skipSeconds} seconds",
                description = "Time skipped when double-tapping left or right screen edges",
                onClick = { showSkipDialog = true }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Category: Languages
            SettingsSectionHeader(title = "Languages")

            SettingsClickableRow(
                title = "Preferred Audio Languages",
                subtitle = settings.preferredAudioLanguages.joinToString(", "),
                description = "Priority order of preferred audio languages (e.g. en, ja)",
                onClick = { showAudioLangDialog = true }
            )

            SettingsClickableRow(
                title = "Preferred Subtitle Languages",
                subtitle = settings.preferredSubtitleLanguages.joinToString(", "),
                description = "Priority order of preferred subtitle languages (e.g. en, ja)",
                onClick = { showSubLangDialog = true }
            )

            SettingsClickableRow(
                title = "Subtitle Mode",
                subtitle = settings.subtitleMode.displayName,
                description = settings.subtitleMode.description,
                onClick = { showSubtitleModeDialog = true }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Category: Shaders & Quality
            SettingsSectionHeader(title = "Post-Processing Shaders")

            SettingsClickableRow(
                title = "Shader Preset",
                subtitle = settings.shaderPreset.displayName,
                description = "${settings.shaderPreset.description}\nNote: Shaders apply in Quality mode only (inactive in Efficient mode; will take effect once Quality mode is selected)",
                onClick = { showShaderDialog = true }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Category: About
            SettingsSectionHeader(title = "About")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Vibe Player ${com.vibeplayer.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Playback Mode Dialog
    if (showPlaybackModeDialog) {
        AlertDialog(
            onDismissRequest = { showPlaybackModeDialog = false },
            title = { Text("Select Playback Mode") },
            text = {
                Column {
                    PlaybackModeOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(playbackMode = option))
                                    showPlaybackModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (option == settings.playbackMode),
                                onClick = {
                                    onUpdateSettings(settings.copy(playbackMode = option))
                                    showPlaybackModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = option.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaybackModeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Renderer Dialog
    if (showRendererDialog) {
        AlertDialog(
            onDismissRequest = { showRendererDialog = false },
            title = { Text("Select Video Renderer") },
            text = {
                Column {
                    rendererOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(renderer = option))
                                    showRendererDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (option == settings.renderer),
                                onClick = {
                                    onUpdateSettings(settings.copy(renderer = option))
                                    showRendererDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = option.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRendererDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Skip Seconds Dialog
    if (showSkipDialog) {
        val skipOptions = listOf(5, 10, 15, 30)
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("Skip Interval") },
            text = {
                Column {
                    skipOptions.forEach { seconds ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(skipSeconds = seconds))
                                    showSkipDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (seconds == settings.skipSeconds),
                                onClick = {
                                    onUpdateSettings(settings.copy(skipSeconds = seconds))
                                    showSkipDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "$seconds seconds", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Shader Preset Dialog
    if (showShaderDialog) {
        AlertDialog(
            onDismissRequest = { showShaderDialog = false },
            title = {
                Column {
                    Text("Shader Preset")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Shaders apply in Quality mode only (inactive in Efficient mode).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column {
                    ShaderPresetOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(shaderPreset = option))
                                    showShaderDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (option == settings.shaderPreset),
                                onClick = {
                                    onUpdateSettings(settings.copy(shaderPreset = option))
                                    showShaderDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = option.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShaderDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Audio Language Dialog
    if (showAudioLangDialog) {
        var textValue by remember { mutableStateOf(settings.preferredAudioLanguages.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showAudioLangDialog = false },
            title = { Text("Preferred Audio Languages") },
            text = {
                Column {
                    Text(
                        text = "Enter comma-separated language codes in priority order:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val langs = textValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .ifEmpty { listOf("en") }
                        onUpdateSettings(settings.copy(preferredAudioLanguages = langs))
                        showAudioLangDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAudioLangDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Subtitle Language Dialog
    if (showSubLangDialog) {
        var textValue by remember { mutableStateOf(settings.preferredSubtitleLanguages.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showSubLangDialog = false },
            title = { Text("Preferred Subtitle Languages") },
            text = {
                Column {
                    Text(
                        text = "Enter comma-separated language codes in priority order:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val langs = textValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .ifEmpty { listOf("en") }
                        onUpdateSettings(settings.copy(preferredSubtitleLanguages = langs))
                        showSubLangDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubLangDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Subtitle Mode Dialog
    if (showSubtitleModeDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleModeDialog = false },
            title = { Text("Subtitle Mode") },
            text = {
                Column {
                    SubtitlePreferenceMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(subtitleMode = option))
                                    showSubtitleModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (option == settings.subtitleMode),
                                onClick = {
                                    onUpdateSettings(settings.copy(subtitleMode = option))
                                    showSubtitleModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = option.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleModeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsClickableRow(
    title: String,
    subtitle: String,
    description: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
