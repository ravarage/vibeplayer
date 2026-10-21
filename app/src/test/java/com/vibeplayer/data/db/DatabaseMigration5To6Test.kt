package com.vibeplayer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigration5To6Test {

    @Test
    fun migrationCreatesBookmarksAndAddsFolderColumns() {
        val executedSql = mutableListOf<String>()
        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executedSql.add(args[0] as String)
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_5_6.migrate(dbProxy)

        // Must match the createSql of schemas/.../6.json, or Room rejects the migrated database.
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `label` TEXT, `createdAt` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_bookmarks_fileKey` ON `bookmarks` (`fileKey`)",
                "ALTER TABLE folder_settings ADD COLUMN speed REAL",
                "ALTER TABLE folder_settings ADD COLUMN autoSkipChapters INTEGER"
            ),
            executedSql
        )
    }

    @Test
    fun folderDefaultsFollowTheApp() {
        val folder = FolderSettings(folderPath = "/sdcard/Anime/Bocchi")
        assertNull(folder.speed)
        assertNull(folder.autoSkipChapters)
    }
}
