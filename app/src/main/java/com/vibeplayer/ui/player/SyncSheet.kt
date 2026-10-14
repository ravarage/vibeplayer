package com.vibeplayer.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.SyncDelay

/** P4-13: subtitle and audio delay in 50 ms steps. Values apply live; the caller persists them per file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(
    subDelayMs: Int,
    audioDelayMs: Int,
    onSubDelay: (Int) -> Unit,
    onAudioDelay: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Sync", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            DelayRow("Subtitle delay", subDelayMs, onSubDelay)
            DelayRow("Audio delay", audioDelayMs, onAudioDelay)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DelayRow(label: String, valueMs: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 15.sp, color = Color(0xFFB0B0B0))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = { onChange(SyncDelay.step(valueMs, -1)) }) { Text("-50") }
            Text(
                text = SyncDelay.format(valueMs),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (valueMs != 0) Color(0xFFBB86FC) else Color.White,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalButton(onClick = { onChange(SyncDelay.step(valueMs, 1)) }) { Text("+50") }
            TextButton(onClick = { onChange(0) }, enabled = valueMs != 0) { Text("Reset") }
        }
    }
}
