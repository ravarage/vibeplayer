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
import com.vibeplayer.playback.SleepTimer
import kotlinx.coroutines.delay

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
    quickActions: List<QuickAction> = emptyList()
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
