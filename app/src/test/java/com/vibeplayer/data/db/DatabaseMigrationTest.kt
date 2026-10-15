package com.vibeplayer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigrationTest {

    private fun createDbProxy(executedSql: MutableList<String>): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase
    }

    @Test
    fun migration1To2AddsTrackMemoryColumns() {
        val executedSql = mutableListOf<String>()
        val db = createDbProxy(executedSql)

        AppDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(5, executedSql.size)
        assertTrue(executedSql.any { it.contains("ADD COLUMN audioTrackTitle TEXT") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN audioTrackLang TEXT") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN audioEnabled INTEGER NOT NULL DEFAULT 1") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN subTrackTitle TEXT") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN subTrackLang TEXT") })
    }

    @Test
    fun migration2To3CreatesFolderSettingsTable() {
        val executedSql = mutableListOf<String>()
        val db = createDbProxy(executedSql)

        AppDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(1, executedSql.size)
        val sql = executedSql[0]
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `folder_settings`"))
        assertTrue(sql.contains("`folderPath` TEXT NOT NULL"))
        assertTrue(sql.contains("`audioLang` TEXT"))
        assertTrue(sql.contains("`subLang` TEXT"))
        assertTrue(sql.contains("`subEnabled` INTEGER NOT NULL"))
        assertTrue(sql.contains("PRIMARY KEY(`folderPath`)"))
    }

    @Test
    fun migration3To4AddsDelayColumns() {
        val executedSql = mutableListOf<String>()
        val db = createDbProxy(executedSql)

        AppDatabase.MIGRATION_3_4.migrate(db)

        assertEquals(2, executedSql.size)
        assertTrue(executedSql.any { it.contains("ADD COLUMN subDelayMs INTEGER NOT NULL DEFAULT 0") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN audioDelayMs INTEGER NOT NULL DEFAULT 0") })
    }

    @Test
    fun migration4To5AddsExternalSubUri() {
        val executedSql = mutableListOf<String>()
        val db = createDbProxy(executedSql)

        AppDatabase.MIGRATION_4_5.migrate(db)

        assertEquals(1, executedSql.size)
        assertTrue(executedSql[0].contains("ADD COLUMN externalSubUri TEXT"))
    }

    @Test
    fun migrationAllVersions1To5InSequence() {
        val executedSql = mutableListOf<String>()
        val db = createDbProxy(executedSql)

        AppDatabase.MIGRATION_1_2.migrate(db)
        AppDatabase.MIGRATION_2_3.migrate(db)
        AppDatabase.MIGRATION_3_4.migrate(db)
        AppDatabase.MIGRATION_4_5.migrate(db)

        // 5 (1->2) + 1 (2->3) + 2 (3->4) + 1 (4->5) = 9 total migrations
        assertEquals(9, executedSql.size)
    }

    @Test
    fun folderSettingsDefaults() {
        val settings = FolderSettings(
            folderPath = "/storage/emulated/0/Movies",
            subEnabled = true,
            updatedAt = 12345L
        )
        assertEquals("/storage/emulated/0/Movies", settings.folderPath)
        assertNull(settings.audioLang)
        assertNull(settings.subLang)
        assertTrue(settings.subEnabled)
        assertEquals(12345L, settings.updatedAt)
    }

    @Test
    fun fileSettingsAllVersionsDefaults() {
        val settings = FileSettings(key = "/test.mp4:100")
        assertEquals(0L, settings.positionMs)
        assertEquals(0L, settings.durationMs)
        assertEquals(1.0f, settings.speed)
        assertEquals(0, settings.subDelayMs)
        assertEquals(0, settings.audioDelayMs)
        assertNull(settings.externalSubUri)
        assertNull(settings.audioTrackTitle)
        assertNull(settings.audioTrackLang)
        assertTrue(settings.audioEnabled)
        assertNull(settings.subTrackTitle)
        assertNull(settings.subTrackLang)
        assertTrue(settings.subEnabled)
    }
}
