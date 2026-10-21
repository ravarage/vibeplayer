package com.vibeplayer.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @Test
    fun recognisesTheSqliteHeader() {
        assertTrue(Backup.isSqlite("SQLite format 3\u0000rest".toByteArray()))
        assertFalse(Backup.isSqlite("SQLite format 3!".toByteArray()))
        assertFalse(Backup.isSqlite(ByteArray(4)))
    }

    @Test
    fun namesTheFileByDate() {
        assertTrue(Backup.fileName(0L).matches(Regex("vibeplayer-backup-\\d{8}-\\d{4}\\.zip")))
    }

    @Test
    fun rejectsAZipWithoutTheDatabaseAndIgnoresOtherEntries() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry()
                z.putNextEntry(ZipEntry("app_settings.preferences_pb")); z.write(2); z.closeEntry()
            }
        }.toByteArray()
        val dir = Files.createTempDirectory("vp-backup").toFile()
        val (checked, reason) = Backup.unpack(ByteArrayInputStream(bytes), dir, maxDbVersion = 6)
        assertNull(checked)
        assertEquals("not a Vibe Player backup (no vibeplayer.db)", reason)
        assertFalse(java.io.File(dir.parentFile, "evil.txt").exists())
        assertEquals(setOf("app_settings.preferences_pb"), dir.list()!!.toSet())
    }
}
