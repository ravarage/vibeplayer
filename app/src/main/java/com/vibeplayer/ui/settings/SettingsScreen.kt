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
import androidx.compose.runtime.collectAsState
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
    var showHoldSpeedDialog by remember { mutableStateOf(false) }
    var showShaderDialog by remember { mutableStateOf(false) }
    var showAudioLangDialog by remember { mutableStateOf(false) }
    var showSubLangDialog by remember { mutableStateOf(false) }
    var showSubtitleModeDialog by remember { mutableStateOf(false) }
    var showVolumeBoostDialog by remember { mutableStateOf(false) }
    var showSubEncodingDialog by remember { mutableStateOf(false) }
    var showSubFoldersDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Subtitle folders: a persisted read grant per folder (SAF), so files next to videos can be listed.
    val pickSubtitleFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val folders = (settings.subtitleFolders + uri.toString()).distinct()
                onUpdateSettings(settings.copy(subtitleFolders = folders))
            } catch (e: SecurityException) {
                android.util.Log.w("VP", "Subtitle folder grant failed for $uri: ${e.message}")
            }
        }
    }

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

            SettingsSwitchRow(
                title = "Auto-skip openings and endings",
                description = "Jump past chapters named Opening, OP, Intro, Ending, ED or Credits as soon as they start (once per file)",
                checked = settings.autoSkipChapters,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(autoSkipChapters = checked))
                }
            )

            SettingsSwitchRow(
                title = "Match display refresh rate",
                description = "Seamlessly switch display refresh rate to match video frame rate",
                checked = settings.matchDisplayRefreshRate,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(matchDisplayRefreshRate = checked))
                }
            )

            SettingsSwitchRow(
                title = "HDR output (experimental)",
                description = "Show HDR10 and HLG videos in HDR when the renderer is Vulkan (quality mode); otherwise they are tone-mapped to SDR",
                checked = settings.hdrOutput,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(hdrOutput = checked))
                }
            )

            SettingsSwitchRow(
                title = "Performance hints (experimental)",
                description = "Tell Android how much time each frame needs (ADPF) while a video plays",
                checked = settings.performanceHints,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(performanceHints = checked))
                }
            )

            SettingsSwitchRow(
                title = "Collect system profiles",
                description = "Register system profiling triggers (ANR, anomaly, app-fully-drawn) via ProfilingManager",
                checked = settings.collectSystemProfiles,
                onCheckedChange = { checked ->
                    onUpdateSettings(settings.copy(collectSystemProfiles = checked))
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

            SettingsSwitchRow(
                title = "Night mode",
                description = "Even out loud music and quiet dialogue; also in the player's audio menu",
                checked = settings.nightMode,
                onCheckedChange = { onUpdateSettings(settings.copy(nightMode = it)) }
            )

            SettingsClickableRow(
                title = "Volume boost",
                subtitle = volumeBoostLabel(settings.volumeBoostMax),
                description = "Lets the volume swipe and the volume keys go past 100 % when the media volume is at its maximum",
                onClick = { showVolumeBoostDialog = true }
            )

            SettingsClickableRow(
                title = "Hold Speed",
                subtitle = com.vibeplayer.ui.player.HoldGesture.label(settings.holdSpeed),
                description = "Speed while pressing and holding the right half of the video",
                onClick = { showHoldSpeedDialog = true }
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

            SettingsSliderRow(
                title = "Subtitle size",
                valueLabel = String.format(java.util.Locale.US, "%.2fx", settings.subtitleScale),
                value = settings.subtitleScale.toFloat(),
                valueRange = com.vibeplayer.player.SubtitleStyle.MIN_SCALE.toFloat()..com.vibeplayer.player.SubtitleStyle.MAX_SCALE.toFloat(),
                steps = 13,
                onCommit = { onUpdateSettings(settings.copy(subtitleScale = com.vibeplayer.player.SubtitleStyle.normalizeScale(it.toDouble()))) }
            )

            SettingsSliderRow(
                title = "Subtitle position",
                valueLabel = "${settings.subtitlePos} (0 top, 100 bottom)",
                value = settings.subtitlePos.toFloat(),
                valueRange = 0f..100f,
                steps = 0,
                onCommit = { onUpdateSettings(settings.copy(subtitlePos = it.toInt())) }
            )

            SettingsSwitchRow(
                title = "Override ASS style",
                description = "Off keeps styled (ASS) subtitles exactly as authored. On applies the size and position above to them too.",
                checked = settings.subtitleAssOverride,
                onCheckedChange = { onUpdateSettings(settings.copy(subtitleAssOverride = it)) }
            )

            SettingsClickableRow(
                title = "Subtitle folders",
                subtitle = if (settings.subtitleFolders.isEmpty()) "None" else "${settings.subtitleFolders.size} folder(s)",
                description = "Subtitle files next to a video in these folders (and their subfolders) load automatically, e.g. Ep01.srt or Ep01.en.ass for Ep01.mkv",
                onClick = {
                    if (settings.subtitleFolders.isEmpty()) pickSubtitleFolder.launch(null) else showSubFoldersDialog = true
                }
            )

            SettingsClickableRow(
                title = "Subtitle encoding",
                subtitle = com.vibeplayer.data.subs.SubtitleCharset.label(settings.subtitleEncoding),
                description = "For external subtitle files that are not UTF-8 (garbled letters in old SRT files)",
                onClick = { showSubEncodingDialog = true }
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

            // Category: Exit Diagnostics (P10-05)
            val recentExits by com.vibeplayer.diagnostics.ExitDiagnostics.recentExits.collectAsState()
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            SettingsSectionHeader(title = "Process Exit Diagnostics")
            if (recentExits.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "No prior exit reasons recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                recentExits.take(5).forEach { exit ->
                    val dateStr = remember(exit.timestampMs) {
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(exit.timestampMs))
                    }
                    val pssMb = exit.pssBytes / (1024 * 1024)
                    val rssMb = exit.rssBytes / (1024 * 1024)
                    val descStr = if (!exit.description.isNullOrBlank()) " • ${exit.description}" else ""
                    SettingsInfoRow(
                        title = "${exit.reasonName}$descStr",
                        subtitle = dateStr,
                        description = "PSS: ${pssMb}MB • RSS: ${rssMb}MB (code ${exit.reasonCode})"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showSubEncodingDialog) {
        SettingsChoiceDialog(
            title = "Subtitle encoding",
            options = com.vibeplayer.data.subs.SubtitleCharset.CHOICES.map { it.first },
            selected = settings.subtitleEncoding,
            label = { com.vibeplayer.data.subs.SubtitleCharset.label(it) },
            description = { if (it == com.vibeplayer.data.subs.SubtitleCharset.DEFAULT) "UTF-8 and UTF-16 files are always read as they are" else null },
            onSelect = { onUpdateSettings(settings.copy(subtitleEncoding = it)) },
            onDismiss = { showSubEncodingDialog = false }
        )
    }

    if (showSubFoldersDialog) {
        AlertDialog(
            onDismissRequest = { showSubFoldersDialog = false },
            title = { Text("Subtitle folders") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    settings.subtitleFolders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = subtitleFolderLabel(folder),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                try {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(folder), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } catch (_: SecurityException) {
                                }
                                val left = settings.subtitleFolders - folder
                                onUpdateSettings(settings.copy(subtitleFolders = left))
                                if (left.isEmpty()) showSubFoldersDialog = false
                            }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubFoldersDialog = false
                    pickSubtitleFolder.launch(null)
                }) {
                    Text("Add folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubFoldersDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showVolumeBoostDialog) {
        SettingsChoiceDialog(
            title = "Volume boost",
            options = com.vibeplayer.player.VolumeBoost.CHOICES,
            selected = settings.volumeBoostMax,
            label = ::volumeBoostLabel,
            description = { if (it > 100) "Loud: can distort on the phone speaker" else null },
            onSelect = { onUpdateSettings(settings.copy(volumeBoostMax = it)) },
            onDismiss = { showVolumeBoostDialog = false }
        )
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

    if (showHoldSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showHoldSpeedDialog = false },
            title = { Text("Hold Speed") },
            text = {
                Column {
                    com.vibeplayer.ui.player.HoldGesture.SPEEDS.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(holdSpeed = speed))
                                    showHoldSpeedDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (speed == settings.holdSpeed),
                                onClick = {
                                    onUpdateSettings(settings.copy(holdSpeed = speed))
                                    showHoldSpeedDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = com.vibeplayer.ui.player.HoldGesture.label(speed), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHoldSpeedDialog = false }) { Text("Close") }
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

@Composable
fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember(value) { mutableStateOf(value) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (dragging == value) valueLabel else String.format(java.util.Locale.US, "%.2f", dragging),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        androidx.compose.material3.Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onCommit(dragging) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
fun SettingsInfoRow(
    title: String,
    subtitle: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

private fun volumeBoostLabel(max: Int): String = if (max <= 100) "Off" else "Up to $max %"

/** A radio list that closes on a choice; shared by the newer settings. */
@Composable
fun <T> SettingsChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = {
                                onSelect(option)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = label(option), fontWeight = FontWeight.SemiBold)
                            description(option)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/** "Movies/Anime" for a tree URI of primary:Movies/Anime. */
private fun subtitleFolderLabel(treeUri: String): String = try {
    val id = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))
    val volume = id.substringBefore(':')
    val rel = id.substringAfter(':')
    when {
        volume == "primary" && rel.isEmpty() -> "Internal storage"
        volume == "primary" -> rel
        else -> "$volume: $rel"
    }
} catch (_: Exception) {
    treeUri
}
