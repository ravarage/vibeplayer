package com.vibeplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours shared by the player panels. */
internal object PanelColors {
    val background = Color(0xF2161616)
    val accent = Color(0xFFBB86FC)
    val accentSoft = Color(0x26BB86FC)
    val chip = Color(0x1AFFFFFF)
    val secondaryText = Color(0xFF9E9E9E)
    val scrim = Color(0x55000000)
}

/**
 * A compact panel over the video: on the right side in landscape (the video stays visible), a short
 * sheet from the bottom in portrait. Tapping outside closes it; Back is handled by the caller.
 */
@Composable
fun PlayerPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PanelColors.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visible,
            enter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
        ) {
            val shape = if (landscape) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            val size = if (landscape) {
                Modifier.width(minOf(360.dp, maxWidth * 0.45f)).fillMaxHeight()
            } else {
                Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.6f)
            }
            Column(
                modifier = size
                    .clip(shape)
                    .background(PanelColors.background)
                    // Taps inside the panel never reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (headerActions != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = headerActions
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .semantics {
                                contentDescription = "close_panel"
                                testTag = "close_panel"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", fontSize = 14.sp, color = PanelColors.secondaryText)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    content = content
                )
            }
        }
    }
}

/** One choice in a panel: a single-line title, an optional detail line, a ✓ when selected. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) PanelColors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) PanelColors.accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = PanelColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Text(text = "✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PanelColors.accent)
        }
    }
}

/** A small pill button for panel headers and option rows. */
@Composable
fun PanelChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tag: String? = null
) {
    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PanelColors.accent else PanelColors.chip)
            .clickable(onClick = onClick)
            .then(
                if (tag != null) Modifier.semantics {
                    contentDescription = tag
                    testTag = tag
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1
        )
    }
}

/** Small grey label above a group of rows. */
@Composable
fun PanelSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = PanelColors.secondaryText,
        modifier = modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** A slim slider for panels: 4 dp track, small thumb, 32 dp tall. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PanelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = PanelColors.accent,
        activeTrackColor = PanelColors.accent,
        inactiveTrackColor = Color(0x33FFFFFF)
    )
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        interactionSource = interaction,
        colors = colors,
        thumb = {
            androidx.compose.material3.SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 18.dp)
            )
        },
        track = { state ->
            androidx.compose.material3.SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                modifier = Modifier.heightIn(max = 4.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 2.dp
            )
        },
        modifier = modifier.heightIn(max = 32.dp)
    )
}
