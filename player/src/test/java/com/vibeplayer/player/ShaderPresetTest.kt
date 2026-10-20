package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderPresetTest {

    @Test
    fun efficientModeAlwaysReturnsEmptyListForAllPresets() {
        for (preset in ShaderPreset.entries) {
            val files = preset.getShaderFiles(PlaybackMode.EFFICIENT)
            assertTrue("Expected empty files for preset $preset in EFFICIENT mode", files.isEmpty())

            val paths = preset.getShaderPaths(PlaybackMode.EFFICIENT, "/app/files/shaders")
            assertTrue("Expected empty paths for preset $preset in EFFICIENT mode", paths.isEmpty())

            val prop = preset.toGlslShadersProperty(PlaybackMode.EFFICIENT, "/app/files/shaders")
            assertEquals("", prop)
        }
    }

    @Test
    fun qualityModeNoneReturnsEmptyList() {
        val files = ShaderPreset.NONE.getShaderFiles(PlaybackMode.QUALITY)
        assertTrue(files.isEmpty())

        val paths = ShaderPreset.NONE.getShaderPaths(PlaybackMode.QUALITY, "/app/files/shaders")
        assertTrue(paths.isEmpty())

        val prop = ShaderPreset.NONE.toGlslShadersProperty(PlaybackMode.QUALITY, "/app/files/shaders")
        assertEquals("", prop)
    }

    @Test
    fun qualityModeAnime4KPresetsReturnExactChains() {
        val expectedA = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )
        assertEquals(expectedA, ShaderPreset.ANIME4K_A.getShaderFiles(PlaybackMode.QUALITY))

        val expectedB = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )
        assertEquals(expectedB, ShaderPreset.ANIME4K_B.getShaderFiles(PlaybackMode.QUALITY))

        val expectedC = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )
        assertEquals(expectedC, ShaderPreset.ANIME4K_C.getShaderFiles(PlaybackMode.QUALITY))
    }

    @Test
    fun qualityModeSnapdragonGSRReturnsExpectedChain() {
        val expectedGSR = listOf("SGSR.glsl")
        assertEquals(expectedGSR, ShaderPreset.SNAPDRAGON_GSR.getShaderFiles(PlaybackMode.QUALITY))
    }

    @Test
    fun getShaderPathsPrependsDirectoryProperly() {
        val baseDir = "/data/user/0/com.vibeplayer/files/shaders"
        val paths = ShaderPreset.SNAPDRAGON_GSR.getShaderPaths(PlaybackMode.QUALITY, baseDir)
        assertEquals(listOf("/data/user/0/com.vibeplayer/files/shaders/SGSR.glsl"), paths)

        // Trailing slash should be trimmed cleanly
        val pathsTrailingSlash = ShaderPreset.SNAPDRAGON_GSR.getShaderPaths(PlaybackMode.QUALITY, "$baseDir/")
        assertEquals(listOf("/data/user/0/com.vibeplayer/files/shaders/SGSR.glsl"), pathsTrailingSlash)

        // Empty base directory returns relative filenames
        val relativePaths = ShaderPreset.SNAPDRAGON_GSR.getShaderPaths(PlaybackMode.QUALITY, "")
        assertEquals(listOf("SGSR.glsl"), relativePaths)
    }

    @Test
    fun toGlslShadersPropertyFormatsColonSeparatedString() {
        val baseDir = "/shaders"
        val propA = ShaderPreset.ANIME4K_A.toGlslShadersProperty(PlaybackMode.QUALITY, baseDir)
        val expectedA = listOf(
            "/shaders/Anime4K_Clamp_Highlights.glsl",
            "/shaders/Anime4K_Restore_CNN_M.glsl",
            "/shaders/Anime4K_Upscale_CNN_x2_M.glsl",
            "/shaders/Anime4K_AutoDownscalePre_x2.glsl",
            "/shaders/Anime4K_AutoDownscalePre_x4.glsl",
            "/shaders/Anime4K_Upscale_CNN_x2_S.glsl"
        ).joinToString(":")
        assertEquals(expectedA, propA)
    }

    @Test
    fun glesRunsTheFullAnime4kChainIncludingClampHighlights() {
        for (preset in listOf(ShaderPreset.ANIME4K_A, ShaderPreset.ANIME4K_B, ShaderPreset.ANIME4K_C)) {
            val vulkan = preset.getShaderFiles(PlaybackMode.QUALITY, Renderer.VULKAN)
            val gles = preset.getShaderFiles(PlaybackMode.QUALITY, Renderer.GLES)
            assertEquals(ShaderPreset.CLAMP_HIGHLIGHTS, vulkan.first())
            assertEquals(vulkan, gles)
            assertEquals(vulkan, preset.getShaderFiles(PlaybackMode.QUALITY))
        }
    }

    @Test
    fun glesKeepsGsrAndEfficientStaysEmpty() {
        assertEquals(listOf("SGSR.glsl"), ShaderPreset.SNAPDRAGON_GSR.getShaderFiles(PlaybackMode.QUALITY, Renderer.GLES))
        for (preset in ShaderPreset.entries) {
            assertTrue(preset.getShaderFiles(PlaybackMode.EFFICIENT, Renderer.GLES).isEmpty())
        }
        assertEquals("", ShaderPreset.NONE.toGlslShadersProperty(PlaybackMode.QUALITY, "/shaders", Renderer.GLES))
    }

    @Test
    fun glesPropertyStartsWithClampHighlights() {
        val prop = ShaderPreset.ANIME4K_A.toGlslShadersProperty(PlaybackMode.QUALITY, "/shaders", Renderer.GLES)
        assertTrue(prop.startsWith("/shaders/Anime4K_Clamp_Highlights.glsl:"))
    }
}
