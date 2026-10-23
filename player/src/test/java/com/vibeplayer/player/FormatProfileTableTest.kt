package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatProfileTableTest {

    @org.junit.Before
    fun builtIn() = FormatProfileTable.resetToBuiltIn()

    @Test
    fun `h264 8-bit in efficient mode selects mediacodec-copy and vulkan if available`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.H264_8BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(DecodeMode.MEDIACODEC_COPY, resolved.decodeMode)
        assertEquals(Renderer.VULKAN, resolved.renderer)
        assertEquals("gpu-next", resolved.vo)
    }

    @Test
    fun `h264 8-bit falls back to GLES when vulkan is unavailable`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.H264_8BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = false
        )
        assertEquals(DecodeMode.MEDIACODEC_COPY, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu", resolved.vo)
    }

    @Test
    fun `h264 10-bit hi10p in efficient mode selects software and vulkan if available`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.H264_10BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(DecodeMode.SOFTWARE, resolved.decodeMode)
        assertEquals(Renderer.VULKAN, resolved.renderer)
        assertEquals("gpu", resolved.vo)
        assertEquals("no hardware decoder on the 778G", resolved.reason)
    }

    @Test
    fun `hevc in efficient mode selects zero-copy mediacodec and GLES`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.HEVC,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true // even when Vulkan is available, HEVC prefers GLES in Efficient mode
        )
        assertEquals(DecodeMode.MEDIACODEC_ZERO_COPY, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu", resolved.vo)
    }

    @Test
    fun `vp9 in efficient mode selects zero-copy mediacodec and GLES`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.VP9,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(DecodeMode.MEDIACODEC_ZERO_COPY, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu", resolved.vo)
    }

    @Test
    fun `av1 in efficient mode selects software and vulkan vo=gpu if available`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.AV1,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(DecodeMode.SOFTWARE, resolved.decodeMode)
        assertEquals(Renderer.VULKAN, resolved.renderer)
        assertEquals("gpu", resolved.vo)
    }

    @Test
    fun `av1 falls back to GLES when vulkan is unavailable`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.AV1,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = false
        )
        assertEquals(DecodeMode.SOFTWARE, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu", resolved.vo)
    }

    @Test
    fun `other format selects software and GLES safe default`() {
        val resolved = FormatProfileTable.resolve(
            format = VideoFormat.OTHER,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(DecodeMode.SOFTWARE, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu", resolved.vo)
        assertEquals("safe default", resolved.reason)
    }

    @Test
    fun `quality mode overrides renderer column with gpu-next on vulkan else GLES`() {
        // HEVC which is GLES in efficient mode becomes Vulkan with gpu-next in quality mode
        val hevcQualityVk = FormatProfileTable.resolve(
            format = VideoFormat.HEVC,
            mode = PlaybackMode.QUALITY,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(Renderer.VULKAN, hevcQualityVk.renderer)
        assertEquals("gpu-next", hevcQualityVk.vo)
        assertEquals(DecodeMode.MEDIACODEC_COPY, hevcQualityVk.decodeMode)
        assertTrue(hevcQualityVk.isQualityMode)

        // When Vulkan is unavailable in quality mode, it lands on GLES with gpu-next
        val hevcQualityGles = FormatProfileTable.resolve(
            format = VideoFormat.HEVC,
            mode = PlaybackMode.QUALITY,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = false
        )
        assertEquals(Renderer.GLES, hevcQualityGles.renderer)
        assertEquals("gpu-next", hevcQualityGles.vo)
        assertEquals(DecodeMode.MEDIACODEC_COPY, hevcQualityGles.decodeMode)

        // AV1 in quality mode keeps software decoding but uses gpu-next on Vulkan
        val av1Quality = FormatProfileTable.resolve(
            format = VideoFormat.AV1,
            mode = PlaybackMode.QUALITY,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(Renderer.VULKAN, av1Quality.renderer)
        assertEquals("gpu-next", av1Quality.vo)
        assertEquals(DecodeMode.SOFTWARE, av1Quality.decodeMode)
    }

    @Test
    fun `user renderer setting overrides table recommendations`() {
        // User explicitly sets GLES: H.264 8-bit must use GLES even though table prefers Vulkan
        val forcedGles = FormatProfileTable.resolve(
            format = VideoFormat.H264_8BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.GLES,
            isVulkanAvailableForFormat = true
        )
        assertEquals(Renderer.GLES, forcedGles.renderer)

        // User explicitly sets VULKAN: HEVC must use Vulkan when available
        val forcedVulkan = FormatProfileTable.resolve(
            format = VideoFormat.HEVC,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.VULKAN,
            isVulkanAvailableForFormat = true
        )
        assertEquals(Renderer.VULKAN, forcedVulkan.renderer)

        // User explicitly sets VULKAN but Vulkan is not available: falls back to GLES
        val forcedVulkanUnavailable = FormatProfileTable.resolve(
            format = VideoFormat.HEVC,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.VULKAN,
            isVulkanAvailableForFormat = false
        )
        assertEquals(Renderer.GLES, forcedVulkanUnavailable.renderer)
    }

    @Test
    fun `safety chain transitions from vulkan to gles to software`() {
        // Step 1: Start on Vulkan for H.264
        val step1 = FormatProfileTable.resolve(
            format = VideoFormat.H264_8BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = true
        )
        assertEquals(Renderer.VULKAN, step1.renderer)
        assertEquals(DecodeMode.MEDIACODEC_COPY, step1.decodeMode)

        // Step 2: Vulkan fails -> remembered failure or fallback lands on GLES
        val step2 = FormatProfileTable.resolve(
            format = VideoFormat.H264_8BIT,
            mode = PlaybackMode.EFFICIENT,
            rendererSetting = RendererSetting.AUTO,
            isVulkanAvailableForFormat = false // failure recorded for this format
        )
        assertEquals(Renderer.GLES, step2.renderer)
        assertEquals(DecodeMode.MEDIACODEC_COPY, step2.decodeMode)

        // Step 3: GLES fails -> falls back to software decoding (hwdec = "no")
        val step3HwdecOverride = "no"
        assertEquals("no", DecodeMode.SOFTWARE.hwdec)
        assertEquals("no", step3HwdecOverride)
    }

    private val clip4k = ClipInfo(VideoFormat.HEVC, fps = 60.0, width = 3840, height = 2160, tenBit = true)

    @Test
    fun `quality mode plays a 4K hardware clip zero-copy on GLES`() {
        val resolved = FormatProfileTable.resolve(
            VideoFormat.HEVC, PlaybackMode.QUALITY, RendererSetting.AUTO, isVulkanAvailableForFormat = true, clip = clip4k
        )
        assertEquals(DecodeMode.MEDIACODEC_ZERO_COPY, resolved.decodeMode)
        assertEquals(Renderer.GLES, resolved.renderer)
        assertEquals("gpu-next", resolved.vo)
        assertTrue(resolved.isQualityMode)
    }

    @Test
    fun `quality mode keeps copy-back on vulkan up to 1440p and when the size is unknown`() {
        for (clip in listOf(ClipInfo(VideoFormat.HEVC, width = 2560, height = 1440), ClipInfo(VideoFormat.HEVC), null)) {
            val resolved = FormatProfileTable.resolve(
                VideoFormat.HEVC, PlaybackMode.QUALITY, RendererSetting.AUTO, isVulkanAvailableForFormat = true, clip = clip
            )
            assertEquals(DecodeMode.MEDIACODEC_COPY, resolved.decodeMode)
            assertEquals(Renderer.VULKAN, resolved.renderer)
        }
    }

    @Test
    fun `a forced vulkan renderer keeps copy-back for 4K`() {
        val resolved = FormatProfileTable.resolve(
            VideoFormat.HEVC, PlaybackMode.QUALITY, RendererSetting.VULKAN, isVulkanAvailableForFormat = true, clip = clip4k
        )
        assertEquals(DecodeMode.MEDIACODEC_COPY, resolved.decodeMode)
        assertEquals(Renderer.VULKAN, resolved.renderer)
    }

    @Test
    fun `efficient copy-back row goes zero-copy on GLES for 4K, software rows are untouched`() {
        val h264 = FormatProfileTable.resolve(
            VideoFormat.H264_8BIT, PlaybackMode.EFFICIENT, RendererSetting.AUTO, isVulkanAvailableForFormat = true,
            clip = ClipInfo(VideoFormat.H264_8BIT, width = 3840, height = 2160)
        )
        assertEquals(DecodeMode.MEDIACODEC_ZERO_COPY, h264.decodeMode)
        assertEquals(Renderer.GLES, h264.renderer)
        assertFalse(h264.isQualityMode)
        val av1 = FormatProfileTable.resolve(
            VideoFormat.AV1, PlaybackMode.QUALITY, RendererSetting.AUTO, isVulkanAvailableForFormat = true,
            clip = ClipInfo(VideoFormat.AV1, width = 3840, height = 2160)
        )
        assertEquals(DecodeMode.SOFTWARE, av1.decodeMode)
    }
}
