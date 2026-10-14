package com.vibeplayer.playback

import android.media.AudioManager

enum class FocusAction {
    NONE,
    PAUSE_TRANSIENT, // Pause and resume when focus returns
    PAUSE_PERMANENT, // Pause and do not resume automatically
    DUCK,            // Lower volume while transient focus is lost
    RESUME,          // Resume playback on focus gain
    UNDUCK,          // Restore volume on focus gain
    UNDUCK_AND_RESUME // Restore volume and resume playback on focus gain
}

object AudioFocusPolicy {

    /**
     * Determines what playback action to take when audio focus changes.
     */
    fun handleFocusChange(
        focusChange: Int,
        isPlaying: Boolean,
        resumeOnGain: Boolean,
        isDucked: Boolean = false
    ): FocusAction {
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (isPlaying || isDucked) FocusAction.PAUSE_PERMANENT else FocusAction.NONE
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying || isDucked) FocusAction.PAUSE_TRANSIENT else FocusAction.NONE
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying) FocusAction.DUCK else FocusAction.NONE
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                when {
                    isDucked && resumeOnGain -> FocusAction.UNDUCK_AND_RESUME
                    isDucked -> FocusAction.UNDUCK
                    resumeOnGain -> FocusAction.RESUME
                    else -> FocusAction.NONE
                }
            }
            else -> FocusAction.NONE
        }
    }
}
