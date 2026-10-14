package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererResolverTest {

    private val choicesWithVulkan = listOf("auto", "opengl", "vulkan")
    private val choicesWithoutVulkan = listOf("auto", "opengl")

    @Test
    fun `quality mode with vulkan requested and available selects vulkan`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.VULKAN,
            mode = PlaybackMode.QUALITY,
            gpuApiChoices = choicesWithVulkan,
            sessionVulkanFailed = false
        )
        assertEquals(Renderer.VULKAN, result)
    }

    @Test
    fun `efficient mode always selects GLES even if vulkan is requested`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.VULKAN,
            mode = PlaybackMode.EFFICIENT,
            gpuApiChoices = choicesWithVulkan,
            sessionVulkanFailed = false
        )
        assertEquals(Renderer.GLES, result)
    }

    @Test
    fun `vulkan requested but not in libmpv choices falls back to GLES`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.VULKAN,
            mode = PlaybackMode.QUALITY,
            gpuApiChoices = choicesWithoutVulkan,
            sessionVulkanFailed = false
        )
        assertEquals(Renderer.GLES, result)
    }

    @Test
    fun `vulkan requested but session failure recorded falls back to GLES`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.VULKAN,
            mode = PlaybackMode.QUALITY,
            gpuApiChoices = choicesWithVulkan,
            sessionVulkanFailed = true
        )
        assertEquals(Renderer.GLES, result)
    }

    @Test
    fun `gles requested in quality mode selects GLES`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.GLES,
            mode = PlaybackMode.QUALITY,
            gpuApiChoices = choicesWithVulkan,
            sessionVulkanFailed = false
        )
        assertEquals(Renderer.GLES, result)
    }

    @Test
    fun `gles requested in efficient mode selects GLES`() {
        val result = RendererResolver.resolveRenderer(
            requestedRenderer = Renderer.GLES,
            mode = PlaybackMode.EFFICIENT,
            gpuApiChoices = choicesWithVulkan,
            sessionVulkanFailed = false
        )
        assertEquals(Renderer.GLES, result)
    }

    @Test
    fun `parseGpuApiChoices parses JSON and comma formats`() {
        val jsonParsed = RendererResolver.parseGpuApiChoices("[\"auto\", \"opengl\", \"vulkan\"]")
        assertEquals(listOf("auto", "opengl", "vulkan"), jsonParsed)

        val csvParsed = RendererResolver.parseGpuApiChoices("auto, opengl, vulkan")
        assertEquals(listOf("auto", "opengl", "vulkan"), csvParsed)

        val emptyParsed = RendererResolver.parseGpuApiChoices(null)
        assertTrue(emptyParsed.isEmpty())
    }
}
