package com.vibeplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.PlayerEngine
import java.util.Locale

@Composable
fun DebugOverlay(
    playerEngine: PlayerEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        playerEngine.setStatsEnabled(true)
        onDispose {
            playerEngine.setStatsEnabled(false)
        }
    }

    val stats by playerEngine.stats.collectAsState()

    Box(
        modifier = modifier
            .widthIn(min = 220.dp, max = 320.dp)
            .background(Color(0xDD121212), shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEBUG STATS",
                    color = Color(0xFF00E676),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "✕",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            DebugRow("VO", stats.vo.ifEmpty { "unknown" })
            DebugRow("GPU API", stats.gpuApi.ifEmpty { "unknown" })
            DebugRow("GPU CTX", stats.gpuContext.ifEmpty { "unknown" })
            DebugRow("HWDEC", stats.hwdecCurrent.ifEmpty { "unknown" })
            DebugRow("MODE", stats.mode.ifEmpty { "unknown" })
            DebugRow("THERMAL", stats.thermalStatus ?: "none")
            DebugRow("ADPF", stats.adpfStatus ?: "off")
            val fpsText = if (stats.containerFps != null) {

                String.format(Locale.US, "%.2f (container: %.3f)", stats.fps, stats.containerFps)
            } else {
                String.format(Locale.US, "%.2f", stats.fps)
            }
            DebugRow("FPS", fpsText)

            DebugRow("DROPS (VO)", "${stats.frameDropCount}")
            DebugRow("DROPS (DEC)", "${stats.decoderFrameDropCount}")
            val vkErr = stats.lastVulkanError
            if (!vkErr.isNullOrEmpty()) {
                DebugRow("VK ERR", vkErr)
            }
            val chosenRow = stats.formatProfileRow
            if (!chosenRow.isNullOrEmpty()) {
                DebugRow("ROW", chosenRow)
            }
            val reason = stats.formatProfileReason
            if (!reason.isNullOrEmpty()) {
                DebugRow("REASON", reason)
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
