package com.vibeplayer.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.vibeplayer.data.db.AppDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * P12-10: one zip with the watch history and bookmarks (Room database) and the settings (DataStore files), to
 * keep them across a reinstall (for example after losing the signing key). Restoring replaces them and
 * restarts the app.
 */
object Backup {
    const val DB_ENTRY = "vibeplayer.db"
    private const val DB_NAME = "vibeplayer.db"
    private val DATASTORE_FILES = listOf("app_settings.preferences_pb", "browser_preferences.preferences_pb")
    private const val INFO_ENTRY = "backup.txt"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** Entries a backup may contain; anything else in a zip is ignored. */
    val ALLOWED: Set<String> = setOf(DB_ENTRY) + DATASTORE_FILES

    fun fileName(nowMillis: Long): String =
        "vibeplayer-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(nowMillis)) + ".zip"

    /** True for an SQLite file header ("SQLite format 3" and a NUL). */
    fun isSqlite(header: ByteArray): Boolean =
        header.size >= 16 && String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3" && header[15] == 0.toByte()

    /** Writes the backup; returns the number of files in it. */
    fun export(context: Context, out: OutputStream, appVersion: String): Int {
        val db = AppDatabase.getInstance(context)
        // Everything in the WAL goes into the main file first, so the copy is complete.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        var count = 0
        ZipOutputStream(out).use { zip ->
            val files = listOf(DB_ENTRY to context.getDatabasePath(DB_NAME)) +
                DATASTORE_FILES.map { it to File(context.filesDir, "datastore/$it") }
            for ((entry, file) in files) {
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            zip.putNextEntry(ZipEntry(INFO_ENTRY))
            zip.write("Vibe Player $appVersion backup, database version ${db.openHelper.readableDatabase.version}\n".toByteArray())
            zip.closeEntry()
        }
        Log.i("VP", "Backup written: $count files")
        return count
    }

    /** What a backup zip holds, unpacked into [dir]; null with the reason when it cannot be restored. */
    data class Checked(val dir: File, val files: Set<String>)

    fun unpack(input: InputStream, dir: File, maxDbVersion: Int): Pair<Checked?, String?> {
        dir.deleteRecursively()
        dir.mkdirs()
        val found = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name !in ALLOWED) continue
                val target = File(dir, e.name)
                var written = 0L
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > MAX_ENTRY_BYTES) return null to "${e.name} is too large"
                        out.write(buf, 0, n)
                    }
                }
                found += e.name
            }
        }
        if (DB_ENTRY !in found) return null to "not a Vibe Player backup (no $DB_ENTRY)"
        val dbFile = File(dir, DB_ENTRY)
        val header = dbFile.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (!isSqlite(header)) return null to "$DB_ENTRY is not a database"
        val version = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (e: Exception) {
            return null to "$DB_ENTRY cannot be opened: ${e.message}"
        }
        if (version > maxDbVersion) return null to "made by a newer Vibe Player (database $version, this app $maxDbVersion)"
        return Checked(dir, found) to null
    }

    /** Replaces the database and settings with an unpacked backup, then restarts the app. */
    fun restoreAndRestart(context: Context, checked: Checked) {
        AppDatabase.closeForRestore()
        val dbFile = context.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        File(checked.dir, DB_ENTRY).copyTo(dbFile, overwrite = true)
        for (name in DATASTORE_FILES) {
            val src = File(checked.dir, name)
            if (src.isFile) src.copyTo(File(context.filesDir, "datastore/$name"), overwrite = true)
        }
        checked.dir.deleteRecursively()
        Log.i("VP", "Backup restored (${checked.files.sorted().joinToString()}); restarting")
        // DataStore and Room hold the old data in memory: start a fresh process.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
