package com.vibeplayer.playback

import android.media.AudioManager

enum class FocusAction {
    NONE,
    PAUSE_TRANSIENT, // Pause and resume when focus returns
    PAUSE_PERMANENT, // Pause and do not resume automatically
    RESUME
}

object AudioFocusPolicy {

    /**
     * Determines what playback action to take when audio focus changes.
     */
    fun handleFocusChange(
        focusChange: Int,
        isPlaying: Boolean,
        resumeOnGain: Boolean
    ): FocusAction {
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (isPlaying) FocusAction.PAUSE_PERMANENT else FocusAction.NONE
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying) FocusAction.PAUSE_TRANSIENT else FocusAction.NONE
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain) FocusAction.RESUME else FocusAction.NONE
            }
            else -> FocusAction.NONE
        }
    }
}
