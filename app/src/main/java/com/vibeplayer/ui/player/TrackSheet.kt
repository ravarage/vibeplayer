package com.vibeplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.vibeplayer.player.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(
    tracks: List<Track>,
    selectedTrackId: Int?,
    onSelectTrack: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    TrackRecomposition("AudioTrackSheet")
    val audioTracks = remember(tracks) { tracks.filter { it.type == "audio" } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully: in landscape the half-expanded sheet shows only the first row.
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
                text = "Audio Tracks",
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
                    val isMuted = (selectedTrackId == null)
                    TrackRow(
                        title = "Mute / Off",
                        subtitle = "Disable audio track",
                        isSelected = isMuted,
                        onClick = {
                            onSelectTrack(null)
                            onDismiss()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "audio_track_off"
                            testTag = "audio_track_off"
                        }
                    )
                }

                items(audioTracks, key = { it.id }) { track ->
                    val isSelected = (track.id == selectedTrackId) || track.selected
                    val lang = track.lang?.uppercase() ?: "UNKNOWN"
                    val codec = track.codec?.uppercase() ?: ""
                    val ch = when (track.channels) {
                        null -> ""
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1 Surround"
                        8 -> "7.1 Surround"
                        else -> "${track.channels} ch"
                    }
                    val details = listOf(lang, codec, ch).filter { it.isNotEmpty() }.joinToString(" • ")
                    val title = track.title ?: "Track #${track.id}"

                    TrackRow(
                        title = title,
                        subtitle = details,
                        isSelected = isSelected,
                        onClick = {
                            onSelectTrack(track.id)
                            onDismiss()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "audio_track_${track.id}"
                            testTag = "audio_track_${track.id}"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleTrackSheet(
    tracks: List<Track>,
    selectedTrackId: Int?,
    onSelectTrack: (Int?) -> Unit,
    onDismiss: () -> Unit,
    onOpenSync: (() -> Unit)? = null,
    onLoadSubtitle: (() -> Unit)? = null
) {
    TrackRecomposition("SubtitleTrackSheet")
    val subTracks = remember(tracks) { tracks.filter { it.type == "sub" } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully: in landscape the half-expanded sheet shows only the first row.
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
                text = "Subtitles",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onLoadSubtitle != null) {
                    item {
                        TrackRow(
                            title = "Load subtitle file…",
                            subtitle = "Open subtitle from storage",
                            isSelected = false,
                            onClick = {
                                onLoadSubtitle()
                                onDismiss()
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "load_subtitle_file"
                                testTag = "load_subtitle_file"
                            }
                        )
                    }
                }

                if (onOpenSync != null) {
                    item {
                        TrackRow(
                            title = "Sync & delay",
                            subtitle = "Subtitle and audio delay",
                            isSelected = false,
                            onClick = onOpenSync
                        )
                    }
                }
                item {
                    val isOff = (selectedTrackId == null)
                    TrackRow(
                        title = "Off",
                        subtitle = "Disable subtitles",
                        isSelected = isOff,
                        onClick = {
                            onSelectTrack(null)
                            onDismiss()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "sub_track_off"
                            testTag = "sub_track_off"
                        }
                    )
                }

                items(subTracks, key = { it.id }) { track ->
                    val isSelected = (selectedTrackId != null && track.id == selectedTrackId)
                    val lang = track.lang?.uppercase() ?: ""
                    val rawCodec = track.codec
                    val codec = when (rawCodec?.lowercase()) {
                        "subrip" -> "SRT"
                        "ass" -> "ASS"
                        "ssa" -> "SSA"
                        "hdmv_pgs_subtitle", "pgssub" -> "PGS"
                        "vtt", "webvtt" -> "VTT"
                        null -> ""
                        else -> rawCodec.uppercase()
                    }
                    val ext = if (track.external) "External" else ""
                    val details = listOf(lang, codec, ext).filter { it.isNotEmpty() }.joinToString(" • ")
                    val title = track.title ?: "Subtitle #${track.id}"

                    TrackRow(
                        title = title,
                        subtitle = details,
                        isSelected = isSelected,
                        onClick = {
                            onSelectTrack(track.id)
                            onDismiss()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "sub_track_${track.id}"
                            testTag = "sub_track_${track.id}"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

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
            .background(
                color = if (isSelected) Color(0x33BB86FC) else Color(0x11FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color(0xFFBB86FC) else Color.White
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = if (isSelected) Color(0xFFDDD0FF) else Color(0xFFAAAAAA)
                )
            }
        }
        if (isSelected) {
            Text(
                text = "✓",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFBB86FC)
            )
        }
    }
}
