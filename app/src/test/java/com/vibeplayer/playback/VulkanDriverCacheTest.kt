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

    private val stored = VulkanDriverPolicy.Stored("old_1", driverFailed = true, formatFailures = setOf("h264_8bit"))

    @Test
    fun `global failure disables every format for the driver build`() {
        val after = VulkanDriverPolicy.afterFailure(
            VulkanDriverPolicy.Stored("k_1", false, emptySet()), "k_1", VideoFormat.AV1.formatKey, isGlobalDriverFailure = true
        )
        assertTrue(after.driverFailed)
        VideoFormat.values().forEach {
            assertFalse(VulkanDriverPolicy.shouldOfferVulkan(true, after.driverFailed, it, after.formatFailures))
        }
    }

    @Test
    fun `per format failure keeps other formats`() {
        val after = VulkanDriverPolicy.afterFailure(
            VulkanDriverPolicy.Stored("k_1", false, emptySet()), "k_1", VideoFormat.AV1.formatKey, isGlobalDriverFailure = false
        )
        assertFalse(after.driverFailed)
        assertFalse(VulkanDriverPolicy.shouldOfferVulkan(true, after.driverFailed, VideoFormat.AV1, after.formatFailures))
        assertTrue(VulkanDriverPolicy.shouldOfferVulkan(true, after.driverFailed, VideoFormat.HEVC, after.formatFailures))
    }

    @Test
    fun `failures of an older driver build do not carry over to the new one`() {
        val after = VulkanDriverPolicy.afterFailure(stored, "new_2", VideoFormat.AV1.formatKey, isGlobalDriverFailure = false)
        assertEquals("new_2", after.driverKey)
        assertFalse(after.driverFailed)
        assertEquals(setOf(VideoFormat.AV1.formatKey), after.formatFailures)
    }

    @Test
    fun `failures on the same build accumulate`() {
        val after = VulkanDriverPolicy.afterFailure(stored, "old_1", VideoFormat.AV1.formatKey, isGlobalDriverFailure = false)
        assertTrue(after.driverFailed)
        assertEquals(setOf("h264_8bit", VideoFormat.AV1.formatKey), after.formatFailures)
    }
}
