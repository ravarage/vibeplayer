package com.vibeplayer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigration4To5Test {

    @Test
    fun migrationExecutesAlterTableAddColumn() {
        val executedSql = mutableListOf<String>()
        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_4_5.migrate(dbProxy)

        assertEquals(1, executedSql.size)
        assertEquals("ALTER TABLE file_settings ADD COLUMN externalSubUri TEXT", executedSql[0])
    }

    @Test
    fun fileSettingsDefaultExternalSubUriIsNull() {
        val settings = FileSettings(key = "/test.mp4:100")
        assertNull(settings.externalSubUri)
    }

    @Test
    fun fileSettingsPreservesExternalSubUriOnCopy() {
        val settings = FileSettings(
            key = "/test.mp4:100",
            externalSubUri = "content://com.android.providers.media.documents/document/123"
        )
        assertEquals("content://com.android.providers.media.documents/document/123", settings.externalSubUri)
        val copied = settings.copy(subDelayMs = 100)
        assertEquals("content://com.android.providers.media.documents/document/123", copied.externalSubUri)
        assertEquals(100, copied.subDelayMs)
    }
}
