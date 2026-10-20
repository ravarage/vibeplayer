package com.vibeplayer.ui.player

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.vibeplayer.player.Track

@Composable
fun AudioTrackSheet(
    tracks: List<Track>,
    selectedTrackId: Int?,
    onSelectTrack: (Int?) -> Unit,
    onDismiss: () -> Unit,
    nightMode: Boolean = false,
    onToggleNightMode: (() -> Unit)? = null
) {
    TrackRecomposition("AudioTrackSheet")
    val audioTracks = remember(tracks) { tracks.filter { it.type == "audio" } }

    PlayerPanel(
        title = "Audio Tracks",
        onDismiss = onDismiss,
        headerActions = if (onToggleNightMode != null) {
            {
                PanelChip(
                    text = if (nightMode) "🌙 Night on" else "🌙 Night",
                    onClick = onToggleNightMode,
                    selected = nightMode,
                    tag = "night_mode"
                )
            }
        } else null
    ) {
        items(audioTracks, key = { it.id }) { track ->
            val isSelected = (track.id == selectedTrackId) || track.selected
            val lang = track.lang?.uppercase() ?: "UNKNOWN"
            val codec = track.codec?.uppercase() ?: ""
            val ch = when (track.channels) {
                null -> ""
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> "${track.channels} ch"
            }
            val details = listOf(lang, codec, ch).filter { it.isNotEmpty() }.joinToString(" · ")
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
        item {
            TrackRow(
                title = "Mute / Off",
                subtitle = "",
                isSelected = selectedTrackId == null,
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
    }
}

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

    PlayerPanel(
        title = "Subtitles",
        onDismiss = onDismiss,
        headerActions = {
            if (onOpenSync != null) {
                PanelChip(text = "⏱ Sync", onClick = onOpenSync, tag = "subtitle_sync")
            }
            if (onLoadSubtitle != null) {
                PanelChip(
                    text = "📂 Load",
                    onClick = {
                        onLoadSubtitle()
                        onDismiss()
                    },
                    tag = "load_subtitle_file"
                )
            }
        }
    ) {
        item {
            TrackRow(
                title = "Off",
                subtitle = "",
                isSelected = selectedTrackId == null,
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
            val extra = listOfNotNull(
                if (track.forced) "Forced" else null,
                if (track.external) "External" else null
            )
            val details = (listOf(lang, codec) + extra).filter { it.isNotEmpty() }.joinToString(" · ")
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
}
