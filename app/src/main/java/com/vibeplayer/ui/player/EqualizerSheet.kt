package com.vibeplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.player.Equalizer
import kotlin.math.roundToInt

/** Built-in 10-band equalizer: on/off, presets, one slider per band (live while dragging, saved on release). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(
    value: Equalizer,
    onPreview: (Equalizer) -> Unit,
    onSave: (Equalizer) -> Unit,
    onDismiss: () -> Unit
) {
    TrackRecomposition("EqualizerSheet")
    var current by remember(value) { mutableStateOf(value.normalized()) }
    fun save(e: Equalizer) {
        current = e
        onSave(e)
    }

    PlayerPanel(
        title = "Equalizer",
        onDismiss = onDismiss,
        headerActions = {
            Switch(
                checked = current.enabled,
                onCheckedChange = { save(current.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = PanelColors.accent),
                modifier = Modifier
                    .scale(0.8f)
                    .semantics {
                        contentDescription = "eq_enabled"
                        testTag = "eq_enabled"
                    }
            )
        }
    ) {
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val preset = current.presetName()
                Equalizer.PRESETS.forEach { (name, gains) ->
                    PanelChip(
                        text = name,
                        selected = current.enabled && preset == name,
                        tag = "eq_preset_${name.lowercase().replace(' ', '_')}",
                        onClick = { save(Equalizer(enabled = true, gains = gains)) }
                    )
                }
                if (current.enabled && preset == null) PanelChip(text = "Custom", selected = true, onClick = {})
            }
        }
        Equalizer.BANDS.forEachIndexed { i, hz ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = Equalizer.bandLabel(hz), fontSize = 12.sp, color = Color.White, modifier = Modifier.width(40.dp))
                    PanelSlider(
                        value = current.gains[i].toFloat(),
                        onValueChange = {
                            val g = current.gains.toMutableList().also { l -> l[i] = it.roundToInt() }
                            current = Equalizer(enabled = true, gains = g)
                            onPreview(current)
                        },
                        onValueChangeFinished = { onSave(current) },
                        valueRange = Equalizer.MIN_DB.toFloat()..Equalizer.MAX_DB.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    val g = current.gains[i]
                    Text(
                        text = if (g > 0) "+$g dB" else "$g dB",
                        fontSize = 12.sp,
                        color = if (g != 0 && current.enabled) PanelColors.accent else PanelColors.secondaryText,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}
