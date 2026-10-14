package com.vibeplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLifecyclePolicyTest {

    @Test
    fun testKeepAliveWhenClientsBound() {
        // When at least 1 client is bound, service must not stop, regardless of pause, eof, or bgAudio setting
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 1, backgroundAudioEnabled = false, isPaused = true, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 1, backgroundAudioEnabled = true, isPaused = true, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 1, backgroundAudioEnabled = false, isPaused = false, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 2, backgroundAudioEnabled = false, isPaused = false, isEof = true))
    }

    @Test
    fun testStopWhenNoClientsAndBackgroundAudioDisabled() {
        // When no clients bound and background audio is off, service must stop immediately
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = false, isPaused = false, isEof = false))
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = false, isPaused = true, isEof = false))
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = false, isPaused = false, isEof = true))
    }

    @Test
    fun testBackgroundAudioPlaybackScenarios() {
        // When no clients bound and background audio is ON:
        // Actively playing -> must NOT stop
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = true, isPaused = false, isEof = false))

        // Paused -> must stop
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = true, isPaused = true, isEof = false))

        // Reached EOF -> must stop
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = true, isPaused = false, isEof = true))
    }

    @Test
    fun testOnTaskRemovedPolicy() {
        // Only keep playing if background audio is on and actively playing
        assertTrue(PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(backgroundAudioEnabled = true, isPaused = false, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(backgroundAudioEnabled = false, isPaused = false, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(backgroundAudioEnabled = true, isPaused = true, isEof = false))
        assertFalse(PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(backgroundAudioEnabled = true, isPaused = false, isEof = true))
        assertFalse(PlaybackLifecyclePolicy.shouldKeepPlayingOnTaskRemoved(backgroundAudioEnabled = true, isPaused = true, isEof = true))
    }

    @Test
    fun testMultipleClientsUnbindingEdgeCases() {
        // As long as boundClients > 0, shouldStopService is always false
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 3, backgroundAudioEnabled = false, isPaused = true, isEof = true))
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 2, backgroundAudioEnabled = false, isPaused = true, isEof = true))
        assertFalse(PlaybackLifecyclePolicy.shouldStopService(boundClients = 1, backgroundAudioEnabled = false, isPaused = true, isEof = true))

        // Once boundClients hits 0:
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = false, isPaused = true, isEof = true))
        assertTrue(PlaybackLifecyclePolicy.shouldStopService(boundClients = 0, backgroundAudioEnabled = true, isPaused = true, isEof = true))
    }
}
