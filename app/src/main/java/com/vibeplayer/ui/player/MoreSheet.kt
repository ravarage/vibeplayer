package com.vibeplayer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.PictureAdjust
import com.vibeplayer.playback.SleepTimer
import kotlinx.coroutines.delay

/** P12-04: speed and auto-skip saved for the folder (a show), and the setters. */
data class FolderShowControls(
    val show: com.vibeplayer.playback.FolderShow = com.vibeplayer.playback.FolderShow(),
    val currentSpeed: Double = 1.0,
    val onSetSpeed: (Float?) -> Unit = {},
    val onSetAutoSkip: (Boolean?) -> Unit = {}
)

/** P12-08: switch the shader preset while watching and compare with the original picture. */
data class ShaderControls(
    val ui: com.vibeplayer.player.ShaderUi = com.vibeplayer.player.ShaderUi(),
    val qualityMode: Boolean = false,
    val vulkan: Boolean = false,
    val onSelect: (com.vibeplayer.player.ShaderPreset) -> Unit = {},
    val onCompare: (Boolean) -> Unit = {},
    val onSwitchToQuality: (() -> Unit)? = null
)

/** A button that moves into the More panel when the top bar is narrow (portrait). */
data class QuickAction(val label: String, val tag: String, val selected: Boolean = false, val onClick: () -> Unit)

/** Sleep timer, A-B repeat, loop and rotation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreSheet(
    sleepTimer: SleepTimer?,
    onSetSleepTimer: (SleepTimer?) -> Unit,
    abLoopAMs: Long?,
    abLoopBMs: Long?,
    onCycleAbLoop: () -> Unit,
    loopFile: Boolean,
    onToggleLoopFile: () -> Unit,
    rotationMode: RotationMode,
    onCycleRotation: () -> Unit,
    onDismiss: () -> Unit,
    quickActions: List<QuickAction> = emptyList(),
    pictureAdjust: PictureAdjust = PictureAdjust(),
    onPreviewPicture: (PictureAdjust) -> Unit = {},
    onSavePicture: (PictureAdjust) -> Unit = {},
    bookmarkCount: Int = 0,
    onOpenBookmarks: (() -> Unit)? = null,
    folderShow: FolderShowControls? = null,
    shaders: ShaderControls? = null
) {
    TrackRecomposition("MoreSheet")
    // The countdown in the sleep row ticks once a second while the panel is open.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sleepTimer) {
        while (sleepTimer?.endsAtElapsedMs != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    PlayerPanel(title = "More", onDismiss = onDismiss) {
        if (quickActions.isNotEmpty()) {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickActions.forEach { a ->
                        PanelChip(text = a.label, selected = a.selected, tag = a.tag, onClick = a.onClick)
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                PanelSectionLabel("Sleep timer: " + SleepTimer.label(sleepTimer, now))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .semantics {
                            contentDescription = "sleep_timer"
                            testTag = "sleep_timer"
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PanelChip(text = "Off", selected = sleepTimer == null, onClick = { onSetSleepTimer(null) })
                    SleepTimer.MINUTE_CHOICES.forEach { m ->
                        PanelChip(
                            text = "$m min",
                            onClick = { onSetSleepTimer(SleepTimer.inMinutes(m, SystemClock.elapsedRealtime())) }
                        )
                    }
                    PanelChip(
                        text = "End of video",
                        selected = sleepTimer?.atEndOfFile == true,
                        onClick = { onSetSleepTimer(SleepTimer.endOfFile()) }
                    )
                }
            }
        }
        item {
            TrackRow(
                title = "A-B repeat",
                subtitle = when {
                    abLoopAMs == null -> "Tap to set A here"
                    abLoopBMs == null -> "A at ${formatTime(abLoopAMs)}: tap to set B"
                    else -> "${formatTime(abLoopAMs)} to ${formatTime(abLoopBMs)}: tap to stop"
                },
                isSelected = abLoopAMs != null,
                onClick = onCycleAbLoop,
                modifier = Modifier.semantics {
                    contentDescription = "ab_repeat"
                    testTag = "ab_repeat"
                }
            )
        }
        item {
            TrackRow(
                title = "Loop this video",
                subtitle = if (loopFile) "On" else "Off",
                isSelected = loopFile,
                onClick = onToggleLoopFile,
                modifier = Modifier.semantics {
                    contentDescription = "loop_file"
                    testTag = "loop_file"
                }
            )
        }
        if (onOpenBookmarks != null) {
            item {
                TrackRow(
                    title = "Bookmarks",
                    subtitle = if (bookmarkCount == 0) "Save spots in this video" else "$bookmarkCount saved",
                    isSelected = false,
                    onClick = onOpenBookmarks,
                    modifier = Modifier.semantics {
                        contentDescription = "bookmarks"
                        testTag = "bookmarks"
                    }
                )
            }
        }
        if (folderShow != null && folderShow.show.folderPath != null) {
            item { FolderShowSection(folderShow) }
        }
        if (shaders != null) {
            item { ShaderSection(shaders) }
        }
        item {
            PictureSection(pictureAdjust, onPreviewPicture, onSavePicture)
        }
        item {
            TrackRow(
                title = "Rotate",
                subtitle = rotationMode.label + if (rotationMode == RotationMode.AUTO) " (follows the video)" else "",
                isSelected = rotationMode != RotationMode.AUTO,
                onClick = onCycleRotation,
                modifier = Modifier.semantics {
                    contentDescription = "rotate"
                    testTag = "rotate"
                }
            )
        }
    }
}

/** Brightness, contrast, saturation and gamma: live while dragging, saved when a slider is let go. */
@Composable
private fun PictureSection(
    value: PictureAdjust,
    onPreview: (PictureAdjust) -> Unit,
    onSave: (PictureAdjust) -> Unit
) {
    var current by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelSectionLabel("Picture", modifier = Modifier.weight(1f))
            if (!current.isDefault) {
                Text(
                    text = "Reset",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = PanelColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            current = PictureAdjust()
                            onSave(current)
                        }
                        .semantics {
                            contentDescription = "picture_reset"
                            testTag = "picture_reset"
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        PictureSlider("Brightness", current.brightness, { current = current.copy(brightness = it); onPreview(current) }) { onSave(current) }
        PictureSlider("Contrast", current.contrast, { current = current.copy(contrast = it); onPreview(current) }) { onSave(current) }
        PictureSlider("Saturation", current.saturation, { current = current.copy(saturation = it); onPreview(current) }) { onSave(current) }
        PictureSlider("Gamma", current.gamma, { current = current.copy(gamma = it); onPreview(current) }) { onSave(current) }
    }
}

@Composable
private fun PictureSlider(label: String, value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp)
            .semantics { contentDescription = "picture_${label.lowercase()}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = Color.White, modifier = Modifier.width(76.dp))
        PanelSlider(
            value = value.toFloat(),
            onValueChange = { onChange((it / 5f).toInt() * 5) },
            onValueChangeFinished = onDone,
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (value > 0) "+$value" else "$value",
            fontSize = 12.sp,
            color = if (value != 0) PanelColors.accent else PanelColors.secondaryText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
    }
}

/** "This folder": a speed and auto-skip for every file of a show (P12-04). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderShowSection(c: FolderShowControls) {
    val folderName = c.show.folderPath?.substringAfterLast('/')?.ifEmpty { null } ?: "this folder"
    val speed = c.currentSpeed.toFloat()
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        PanelSectionLabel("Whole folder: $folderName")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val folderSpeed = c.show.speed
            when {
                folderSpeed != null && folderSpeed == speed ->
                    PanelChip(text = "Speed ${HoldGesture.label(speed.toDouble())} for all ✓", selected = true, tag = "folder_speed_clear",
                        onClick = { c.onSetSpeed(null) })
                speed != 1f ->
                    PanelChip(text = "Use ${HoldGesture.label(speed.toDouble())} for all", tag = "folder_speed_set",
                        onClick = { c.onSetSpeed(speed) })
                folderSpeed != null ->
                    PanelChip(text = "Folder speed ${HoldGesture.label(folderSpeed.toDouble())}: clear", tag = "folder_speed_clear",
                        onClick = { c.onSetSpeed(null) })
                else -> Text(
                    text = "Change the speed, then use it for every file here",
                    fontSize = 11.sp,
                    color = PanelColors.secondaryText,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "Auto-skip intro", fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
            PanelChip(text = "App", selected = c.show.autoSkipChapters == null, tag = "folder_skip_default",
                onClick = { c.onSetAutoSkip(null) })
            PanelChip(text = "On", selected = c.show.autoSkipChapters == true, tag = "folder_skip_on",
                onClick = { c.onSetAutoSkip(true) })
            PanelChip(text = "Off", selected = c.show.autoSkipChapters == false, tag = "folder_skip_off",
                onClick = { c.onSetAutoSkip(false) })
        }
    }
}

/** Shader presets and an original/processed compare (P12-08). Shaders run in Quality mode; Anime4K on Vulkan only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShaderSection(c: ShaderControls) {
    val preset = c.ui.preset
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        PanelSectionLabel("Shaders")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            com.vibeplayer.player.ShaderPreset.entries.forEach { p ->
                val label = when (p) {
                    com.vibeplayer.player.ShaderPreset.NONE -> "Off"
                    com.vibeplayer.player.ShaderPreset.ANIME4K_A -> "Anime4K A"
                    com.vibeplayer.player.ShaderPreset.ANIME4K_B -> "Anime4K B"
                    com.vibeplayer.player.ShaderPreset.ANIME4K_C -> "Anime4K C"
                    com.vibeplayer.player.ShaderPreset.SNAPDRAGON_GSR -> "GSR"
                    com.vibeplayer.player.ShaderPreset.FSRCNNX -> "FSRCNNX"
                    com.vibeplayer.player.ShaderPreset.RAVU -> "RAVU"
                }
                PanelChip(text = label, selected = p == preset, tag = "shader_${p.name.lowercase()}", onClick = { c.onSelect(p) })
            }
            if (preset != com.vibeplayer.player.ShaderPreset.NONE && c.qualityMode) {
                PanelChip(
                    text = if (c.ui.showingOriginal) "◐ Showing original" else "◐ Compare",
                    selected = c.ui.showingOriginal,
                    tag = "shader_compare",
                    onClick = { c.onCompare(!c.ui.showingOriginal) }
                )
            }
        }
        val note = when {
            preset == com.vibeplayer.player.ShaderPreset.NONE -> null
            !c.qualityMode -> "Shaders run in Quality mode only"
            preset.vulkanOnly && !c.vulkan -> "${if (preset.isAnime4K) "Anime4K" else preset.displayName} needs the Vulkan renderer"
            else -> null
        }
        if (note != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = note, fontSize = 11.sp, color = PanelColors.secondaryText, modifier = Modifier.weight(1f))
                if (!c.qualityMode && c.onSwitchToQuality != null) {
                    PanelChip(text = "Switch to Quality", tag = "shader_quality", onClick = c.onSwitchToQuality)
                }
            }
        }
    }
}
