package com.vibeplayer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigration6To7Test {
    @Test
    fun migrationCreatesNetworkSources() {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, a ->
            if (m.name == "execSQL") sql.add(a[0] as String)
            null
        } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_6_7.migrate(db)
        // Must match the createSql of schemas/.../7.json.
        assertEquals(
            listOf("CREATE TABLE IF NOT EXISTS `network_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `path` TEXT NOT NULL, `username` TEXT NOT NULL, `secret` TEXT, `createdAt` INTEGER NOT NULL)"),
            sql
        )
    }
}
