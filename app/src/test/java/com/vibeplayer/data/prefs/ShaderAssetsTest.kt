package com.vibeplayer.data.prefs

import com.vibeplayer.player.PlaybackMode
import com.vibeplayer.player.ShaderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every file a shader preset names ships in the APK, and each setting maps to a preset of the same name. */
class ShaderAssetsTest {
    @Test
    fun everyPresetFileIsAnAsset() {
        val dir = File("src/main/assets/shaders")
        for (p in ShaderPreset.entries) {
            for (f in p.getShaderFiles(PlaybackMode.QUALITY)) {
                assertTrue("$f of $p is missing from ${dir.path}", File(dir, f).isFile)
            }
        }
    }

    @Test
    fun settingsAndPresetsMatchByName() {
        assertEquals(ShaderPreset.entries.map { it.name }, ShaderPresetOption.entries.map { it.name })
    }
}
