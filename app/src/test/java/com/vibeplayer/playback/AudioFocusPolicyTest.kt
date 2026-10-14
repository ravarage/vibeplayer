package com.vibeplayer.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun testPermanentLossWhilePlaying() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_LOSS,
            isPlaying = true,
            resumeOnGain = false
        )
        assertEquals(FocusAction.PAUSE_PERMANENT, action)
    }

    @Test
    fun testPermanentLossWhilePaused() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_LOSS,
            isPlaying = false,
            resumeOnGain = false
        )
        assertEquals(FocusAction.NONE, action)
    }

    @Test
    fun testTransientLossWhilePlaying() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            isPlaying = true,
            resumeOnGain = false
        )
        assertEquals(FocusAction.PAUSE_TRANSIENT, action)
    }

    @Test
    fun testTransientCanDuckWhilePlaying() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            isPlaying = true,
            resumeOnGain = false
        )
        assertEquals(FocusAction.DUCK, action)
    }

    @Test
    fun testTransientCanDuckWhilePaused() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            isPlaying = false,
            resumeOnGain = false
        )
        assertEquals(FocusAction.NONE, action)
    }

    @Test
    fun testGainWhenDucked() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isPlaying = true,
            resumeOnGain = false,
            isDucked = true
        )
        assertEquals(FocusAction.UNDUCK, action)
    }

    @Test
    fun testGainWhenDuckedAndResumeOnGain() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isPlaying = false,
            resumeOnGain = true,
            isDucked = true
        )
        assertEquals(FocusAction.UNDUCK_AND_RESUME, action)
    }

    @Test
    fun testGainWhenResumeFlagTrue() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isPlaying = false,
            resumeOnGain = true
        )
        assertEquals(FocusAction.RESUME, action)
    }

    @Test
    fun testGainWhenResumeFlagFalse() {
        val action = AudioFocusPolicy.handleFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isPlaying = false,
            resumeOnGain = false
        )
        assertEquals(FocusAction.NONE, action)
    }
}
