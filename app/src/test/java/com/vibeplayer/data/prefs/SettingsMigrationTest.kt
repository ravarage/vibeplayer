package com.vibeplayer.data.prefs
 
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
 
class SettingsMigrationTest {
 
    @Test
    fun `default renderer in AppSettings is AUTO`() {
        val settings = AppSettings()
        assertEquals(RendererOption.AUTO, settings.renderer)
    }
 
    @Test
    fun `unmigrated null raw renderer resolves to AUTO`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = null, isMigrated = false)
        assertEquals(RendererOption.AUTO, resolved)
    }
 
    @Test
    fun `unmigrated OPENGL resolves to AUTO`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = "OPENGL", isMigrated = false)
        assertEquals(RendererOption.AUTO, resolved)
    }
 
    @Test
    fun `unmigrated VULKAN stays VULKAN`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = "VULKAN", isMigrated = false)
        assertEquals(RendererOption.VULKAN, resolved)
    }
 
    @Test
    fun `migrated OPENGL stays OPENGL`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = "OPENGL", isMigrated = true)
        assertEquals(RendererOption.OPENGL, resolved)
    }
 
    @Test
    fun `migrated AUTO stays AUTO`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = "AUTO", isMigrated = true)
        assertEquals(RendererOption.AUTO, resolved)
    }
 
    @Test
    fun `migrated VULKAN stays VULKAN`() {
        val resolved = SettingsMigrationPolicy.resolveMigratedRenderer(rawRenderer = "VULKAN", isMigrated = true)
        assertEquals(RendererOption.VULKAN, resolved)
    }
 
    @Test
    fun `shouldUpdateRawRendererOnMigration only updates unmigrated default or OPENGL`() {
        assertTrue(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = null, isMigrated = false))
        assertTrue(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = "OPENGL", isMigrated = false))
        assertFalse(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = "VULKAN", isMigrated = false))
        assertFalse(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = "AUTO", isMigrated = false))
        assertFalse(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = null, isMigrated = true))
        assertFalse(SettingsMigrationPolicy.shouldUpdateRawRendererOnMigration(rawRenderer = "OPENGL", isMigrated = true))
    }
}
