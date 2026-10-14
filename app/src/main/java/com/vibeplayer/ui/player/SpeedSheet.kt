package com.vibeplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    currentSpeed: Double,
    onSpeedChanged: (Double) -> Unit,
    onSpeedChangeFinished: (Double) -> Unit = {},
    onDismiss: () -> Unit
) {
    val presets = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0)
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Speed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = String.format(Locale.US, "%.2fx", sliderSpeed),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBB86FC),
                    modifier = Modifier
                        .background(Color(0x33BB86FC), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slider with - and + buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val next = ((currentSpeed - 0.1) * 100).roundToInt() / 100.0
                        onSpeedChanged(next.coerceIn(0.25, 4.0))
                        onSpeedChangeFinished(next.coerceIn(0.25, 4.0))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(text = "−", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = sliderSpeed,
                    onValueChange = { newFloat ->
                        val rounded = (newFloat * 20).roundToInt() / 20.0 // 0.05 increments
                        val speed = rounded.coerceIn(0.25, 4.0)
                        sliderSpeed = speed.toFloat()
                        onSpeedChanged(speed)
                    },
                    onValueChangeFinished = { onSpeedChangeFinished(sliderSpeed.toDouble()) },
                    valueRange = 0.25f..4.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFBB86FC),
                        activeTrackColor = Color(0xFFBB86FC),
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        val next = ((currentSpeed + 0.1) * 100).roundToInt() / 100.0
                        onSpeedChanged(next.coerceIn(0.25, 4.0))
                        onSpeedChangeFinished(next.coerceIn(0.25, 4.0))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(text = "+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preset Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Split presets into 2 rows for clean layout
                val row1 = presets.take(6)
                val row2 = presets.drop(6)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row1.forEach { speed ->
                        val isSelected = abs(currentSpeed - speed) < 0.02
                        SpeedChip(
                            speed = speed,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSpeedChanged(speed)
                                onSpeedChangeFinished(speed)
                                onDismiss()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row2.forEach { speed ->
                        val isSelected = abs(currentSpeed - speed) < 0.02
                        SpeedChip(
                            speed = speed,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSpeedChanged(speed)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpeedChip(
    speed: Double,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val text = if (speed % 1.0 == 0.0) {
        "${speed.toInt()}x"
    } else {
        "${speed}x"
    }
    val tag = "speed_$speed"

    Box(
        modifier = modifier
            .semantics {
                contentDescription = tag
                testTag = tag
            }
            .background(
                color = if (isSelected) Color(0xFFBB86FC) else Color(0x1FFFFFFF),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}
