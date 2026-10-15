package com.vibeplayer.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vibeplayer.player.PlayerEngine

/**
 * Adapts [PlayerEngine] to AndroidX Media3 [Player] using [SimpleBasePlayer].
 * Exposes playback state, duration, position, speed, metadata, and handles
 * play/pause, seek, 10s skip, and previous/next neighbour navigation.
 */
class VibePlayerAdapter(
    looper: Looper,
    private val engine: PlayerEngine,
    private val service: PlaybackService
) : SimpleBasePlayer(looper) {

    fun notifyStateChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val state = engine.state.value
        val path = service.currentPath.value
        val title = service.currentVideoTitle.value

        val playbackState = when {
            state.durationMs <= 0L -> Player.STATE_IDLE
            state.eof -> Player.STATE_ENDED
            state.buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA
            )
            .build()

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(path ?: "vibeplayer")
            .setMediaMetadata(mediaMetadata)
            .build()

        val durationUs = (state.durationMs * 1000L).coerceAtLeast(0L)
        val mediaItemData = MediaItemData.Builder(path ?: "vibeplayer")
            .setMediaItem(mediaItem)
            .setMediaMetadata(mediaMetadata)
            .setIsSeekable(true)
            .setDurationUs(durationUs)
            .build()

        // Media3 derives next/previous (headset buttons, system media controls) from the playlist, so
        // the folder neighbours are represented as placeholder items around the current file.
        val neighbours = service.neighbours.value
        val playlist = buildList {
            if (neighbours.previous) add(placeholder(PREVIOUS_UID))
            add(mediaItemData)
            if (neighbours.next) add(placeholder(NEXT_UID))
        }
        val currentIndex = if (neighbours.previous) 1 else 0

        val currentPos = engine.position.value.coerceAtLeast(0L)

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(!state.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackParameters(PlaybackParameters(state.speed.toFloat()))
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs(currentPos)
            .build()
    }

    private fun placeholder(uid: String): MediaItemData =
        MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
            .setIsSeekable(false)
            .build()

    private companion object {
        const val PREVIOUS_UID = "vibeplayer_previous"
        const val NEXT_UID = "vibeplayer_next"
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            service.prepareAudioResume()
            engine.play()
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val currentIndex = if (service.neighbours.value.previous) 1 else 0
        when {
            seekCommand == Player.COMMAND_SEEK_BACK -> engine.seekRelative(-10.0, exact = true)
            seekCommand == Player.COMMAND_SEEK_FORWARD -> engine.seekRelative(10.0, exact = true)
            // A different playlist index is a folder neighbour; the same index is a seek in this file
            // (including "previous" pressed late in a file, which restarts it).
            mediaItemIndex > currentIndex -> service.skipToNeighbour(forward = true)
            mediaItemIndex < currentIndex -> service.skipToNeighbour(forward = false)
            else -> engine.seekTo(positionMs, exact = true)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        engine.setSpeed(playbackParameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }
}
