package com.vibeplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanVoCheckTest {

    @Test
    fun `no VO after file-loaded with a video track and a surface is a failure`() {
        assertTrue(PlayerEngine.isVulkanVoFailure(fileLoaded = true, surfaceAttached = true, vid = "1", voConfigured = false))
    }

    @Test
    fun `configured VO is never a failure`() {
        assertFalse(PlayerEngine.isVulkanVoFailure(fileLoaded = true, surfaceAttached = true, vid = "1", voConfigured = true))
    }

    @Test
    fun `surface attached before the file has loaded is not a failure`() {
        assertFalse(PlayerEngine.isVulkanVoFailure(fileLoaded = false, surfaceAttached = true, vid = "auto", voConfigured = false))
    }

    @Test
    fun `audio-only file or background playback is not a failure`() {
        assertFalse(PlayerEngine.isVulkanVoFailure(fileLoaded = true, surfaceAttached = true, vid = "no", voConfigured = false))
        assertFalse(PlayerEngine.isVulkanVoFailure(fileLoaded = true, surfaceAttached = true, vid = null, voConfigured = false))
        assertFalse(PlayerEngine.isVulkanVoFailure(fileLoaded = true, surfaceAttached = false, vid = "1", voConfigured = false))
    }
}
