package com.vibeplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SpeedSheet(
    currentSpeed: Double,
    onSpeedChanged: (Double) -> Unit,
    onSpeedChangeFinished: (Double) -> Unit = {},
    onDismiss: () -> Unit
) {
    TrackRecomposition("SpeedSheet")
    val presets = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0)
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed.toFloat()) }

    fun step(delta: Double) {
        val next = (((currentSpeed + delta) * 100).roundToInt() / 100.0).coerceIn(0.25, 4.0)
        onSpeedChanged(next)
        onSpeedChangeFinished(next)
    }

    PlayerPanel(
        title = "Playback Speed",
        onDismiss = onDismiss,
        headerActions = {
            Text(
                text = String.format(Locale.US, "%.2fx", sliderSpeed),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PanelColors.accent
            )
        }
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundStepButton("−") { step(-0.1) }
                Slider(
                    value = sliderSpeed,
                    onValueChange = { newFloat ->
                        val speed = ((newFloat * 20).roundToInt() / 20.0).coerceIn(0.25, 4.0) // 0.05 steps
                        sliderSpeed = speed.toFloat()
                        onSpeedChanged(speed)
                    },
                    onValueChangeFinished = { onSpeedChangeFinished(sliderSpeed.toDouble()) },
                    valueRange = 0.25f..4.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = PanelColors.accent,
                        activeTrackColor = PanelColors.accent,
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                RoundStepButton("+") { step(0.1) }
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { speed ->
                            PanelChip(
                                text = HoldGesture.label(speed),
                                selected = abs(currentSpeed - speed) < 0.02,
                                tag = "speed_$speed",
                                onClick = {
                                    onSpeedChanged(speed)
                                    onSpeedChangeFinished(speed)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Keep the last row's chips the same width as the others.
                        repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundStepButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(PanelColors.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
