package com.vibeplayer.player

/**
 * Predefined GLSL shader presets for video post-processing and upscaling.
 * Shaders are only applied when [mode] is [PlaybackMode.QUALITY].
 * In [PlaybackMode.EFFICIENT], shader file lists are always empty.
 */
enum class ShaderPreset(val displayName: String) {
    NONE("None"),
    ANIME4K_A("Anime4K A (Fast)"),
    ANIME4K_B("Anime4K B (Fast)"),
    ANIME4K_C("Anime4K C (Fast)"),
    SNAPDRAGON_GSR("Snapdragon GSR");

    /**
     * Returns the ordered list of shader file names for this preset.
     * Empty if [mode] is not [PlaybackMode.QUALITY] or if preset is [NONE].
     *
     * Anime4K is Vulkan-only: on OpenGL ES ([renderer] = [Renderer.GLES]) its chains are empty. The phone's
     * Adreno GLES compiler cannot call libplacebo's texture-offset macro through the HOOKED_ / MAIN_ alias
     * ("'MAINPRESUB_texOff' : no matching overloaded function found", P8-02), which every Anime4K stage that
     * reads the video uses (Clamp_Highlights and the CNN passes); only the downscale steps would be left. The
     * same sources compile with glslang and on Vulkan. Snapdragon GSR runs on both.
     */
    fun getShaderFiles(mode: PlaybackMode, renderer: Renderer = Renderer.VULKAN): List<String> =
        if (renderer == Renderer.GLES && isAnime4K) emptyList() else chain(mode)

    val isAnime4K: Boolean get() = this == ANIME4K_A || this == ANIME4K_B || this == ANIME4K_C

    /** True when this preset is wanted but cannot run on [renderer] in [mode] (Anime4K on OpenGL ES). */
    fun unavailableOn(mode: PlaybackMode, renderer: Renderer): Boolean =
        mode == PlaybackMode.QUALITY && renderer == Renderer.GLES && isAnime4K

    private fun chain(mode: PlaybackMode): List<String> {
        if (mode != PlaybackMode.QUALITY) return emptyList()
        return when (this) {
            NONE -> emptyList()
            ANIME4K_A -> listOf(
                CLAMP_HIGHLIGHTS,
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl"
            )
            ANIME4K_B -> listOf(
                CLAMP_HIGHLIGHTS,
                "Anime4K_Restore_CNN_Soft_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl"
            )
            ANIME4K_C -> listOf(
                CLAMP_HIGHLIGHTS,
                "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl"
            )
            SNAPDRAGON_GSR -> listOf(
                "SGSR.glsl"
            )
        }
    }

    /**
     * Returns the absolute paths of shaders in this preset by prepending [baseDir].
     * Empty if [mode] is not [PlaybackMode.QUALITY] or if preset is [NONE].
     */
    fun getShaderPaths(mode: PlaybackMode, baseDir: String, renderer: Renderer = Renderer.VULKAN): List<String> {
        val files = getShaderFiles(mode, renderer)
        if (files.isEmpty()) return emptyList()
        val dir = baseDir.trimEnd('/')
        return if (dir.isEmpty()) files else files.map { "$dir/$it" }
    }

    /**
     * Formats the shader file paths as a colon-separated string suitable for mpv's
     * `glsl-shaders` property/option, or empty string if no shaders are active.
     */
    fun toGlslShadersProperty(mode: PlaybackMode, baseDir: String, renderer: Renderer = Renderer.VULKAN): String {
        val paths = getShaderPaths(mode, baseDir, renderer)
        return if (paths.isEmpty()) "" else paths.joinToString(":")
    }

    companion object {
        /** Anime4K de-ring stage (hooks MAIN and PREKERNEL); first in the A, B and C chains. */
        const val CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"
    }
}
