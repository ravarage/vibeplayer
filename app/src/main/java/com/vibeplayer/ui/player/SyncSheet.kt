package com.vibeplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.SyncDelay

/** P4-13: subtitle and audio delay in 50 ms steps. Values apply live; the caller persists them per file. */
@Composable
fun SyncSheet(
    subDelayMs: Int,
    audioDelayMs: Int,
    onSubDelay: (Int) -> Unit,
    onAudioDelay: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    TrackRecomposition("SyncSheet")
    PlayerPanel(title = "Sync", onDismiss = onDismiss) {
        item { DelayRow("Subtitle delay", subDelayMs, onSubDelay) }
        item { DelayRow("Audio delay", audioDelayMs, onAudioDelay) }
    }
}

@Composable
private fun DelayRow(label: String, valueMs: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelSectionLabel(label, modifier = Modifier.weight(1f))
            // Reset sits on the label line so the value never moves when it appears.
            if (valueMs != 0) {
                Text(
                    text = "Reset",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = PanelColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onChange(0) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PanelChip(text = "−50", onClick = { onChange(SyncDelay.step(valueMs, -1)) })
            Text(
                text = SyncDelay.format(valueMs),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (valueMs != 0) PanelColors.accent else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            PanelChip(text = "+50", onClick = { onChange(SyncDelay.step(valueMs, 1)) })
        }
    }
}
