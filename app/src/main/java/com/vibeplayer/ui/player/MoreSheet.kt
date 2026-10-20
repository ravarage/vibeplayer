package com.vibeplayer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.playback.SleepTimer
import kotlinx.coroutines.delay

/** Sleep timer, A-B repeat, loop and rotation. */
@OptIn(ExperimentalMaterial3Api::class)
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
    onDismiss: () -> Unit
) {
    TrackRecomposition("MoreSheet")
    var showSleepChoices by remember { mutableStateOf(false) }
    // The countdown in the sleep row ticks once a second while the sheet is open.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sleepTimer) {
        while (sleepTimer?.endsAtElapsedMs != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "More",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    TrackRow(
                        title = "Sleep timer",
                        subtitle = SleepTimer.label(sleepTimer, now),
                        isSelected = sleepTimer != null,
                        onClick = { showSleepChoices = !showSleepChoices },
                        modifier = Modifier.semantics {
                            contentDescription = "sleep_timer"
                            testTag = "sleep_timer"
                        }
                    )
                }
                if (showSleepChoices) {
                    val choices = listOf<Pair<String, () -> SleepTimer?>>("Off" to { null }) +
                        SleepTimer.MINUTE_CHOICES.map { m -> "$m minutes" to { SleepTimer.inMinutes(m, SystemClock.elapsedRealtime()) } } +
                        listOf("At the end of this video" to { SleepTimer.endOfFile() })
                    choices.forEach { (label, make) ->
                        item {
                            TrackRow(
                                title = "    $label",
                                subtitle = "",
                                isSelected = false,
                                onClick = {
                                    onSetSleepTimer(make())
                                    showSleepChoices = false
                                }
                            )
                        }
                    }
                }
                item {
                    TrackRow(
                        title = "A-B repeat",
                        subtitle = when {
                            abLoopAMs == null -> "Tap to set A at the current position"
                            abLoopBMs == null -> "A at ${formatTime(abLoopAMs)}: tap again to set B"
                            else -> "Repeating ${formatTime(abLoopAMs)} to ${formatTime(abLoopBMs)}: tap to stop"
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
                        subtitle = if (loopFile) "On: plays again from the start" else "Off",
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
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
