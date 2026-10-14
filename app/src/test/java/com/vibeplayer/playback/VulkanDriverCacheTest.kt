package com.vibeplayer.playback

import com.vibeplayer.player.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanDriverCacheTest {

    @Test
    fun `computeDriverKey combines fingerprint and so mtime`() {
        val key = VulkanDriverPolicy.computeDriverKey("redwood-userdebug-17", 1728846540000L)
        assertEquals("redwood-userdebug-17_1728846540000", key)
    }

    @Test
    fun `shouldOfferVulkan returns false when libMpv lacks vulkan`() {
        val available = VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = false,
            driverFailed = false,
            format = VideoFormat.H264_8BIT,
            formatFailures = emptySet()
        )
        assertFalse(available)
    }

    @Test
    fun `shouldOfferVulkan returns false when driver failed globally`() {
        val available = VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = true,
            driverFailed = true,
            format = VideoFormat.H264_8BIT,
            formatFailures = emptySet()
        )
        assertFalse(available)
    }

    @Test
    fun `shouldOfferVulkan returns false when format has remembered failure`() {
        val formatFailures = setOf(VideoFormat.H264_8BIT.formatKey)

        val h264Available = VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = true,
            driverFailed = false,
            format = VideoFormat.H264_8BIT,
            formatFailures = formatFailures
        )
        assertFalse(h264Available)

        // Other formats should still be offered
        val av1Available = VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = true,
            driverFailed = false,
            format = VideoFormat.AV1,
            formatFailures = formatFailures
        )
        assertTrue(av1Available)
    }

    @Test
    fun `shouldOfferVulkan returns true when clean`() {
        val available = VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = true,
            driverFailed = false,
            format = VideoFormat.HEVC,
            formatFailures = emptySet()
        )
        assertTrue(available)
    }
}
