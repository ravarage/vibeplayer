package com.vibeplayer.player

/**
 * Pure Kotlin decision logic for selecting the active video renderer.
 *
 * Rules:
 * 1. Vulkan is offered and selected only if loaded libmpv lists "vulkan" in its gpu-api choices.
 * 2. Vulkan applies to QUALITY mode only (EFFICIENT mode always uses GLES zero-copy).
 * 3. If Vulkan failed during the current app session (sessionVulkanFailed == true), fall back to GLES.
 * 4. Otherwise, use the requested renderer (default is GLES).
 */
object RendererResolver {

    fun resolveRenderer(
        requestedRenderer: Renderer,
        mode: PlaybackMode,
        gpuApiChoices: List<String>,
        sessionVulkanFailed: Boolean
    ): Renderer {
        if (mode == PlaybackMode.EFFICIENT) {
            return Renderer.GLES
        }

        if (requestedRenderer != Renderer.VULKAN) {
            return Renderer.GLES
        }

        val hasVulkanChoice = gpuApiChoices.any { it.equals("vulkan", ignoreCase = true) }
        if (!hasVulkanChoice) {
            return Renderer.GLES
        }

        if (sessionVulkanFailed) {
            return Renderer.GLES
        }

        return Renderer.VULKAN
    }

    /**
     * Parses gpu-api choices from libmpv option-info format
     * (e.g. `["auto", "opengl", "vulkan"]` or `auto,opengl,vulkan`).
     */
    fun parseGpuApiChoices(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim()
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'").lowercase() }
            .filter { it.isNotEmpty() }
    }
}
