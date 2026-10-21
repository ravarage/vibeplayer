package com.vibeplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileSettings::class, FolderSettings::class, Bookmark::class, com.vibeplayer.net.NetSource::class],
    version = AppDatabase.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileSettingsDao(): FileSettingsDao
    abstract fun folderSettingsDao(): FolderSettingsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun netSourceDao(): com.vibeplayer.net.NetSourceDao

    companion object {
        /** Schema version (also the newest a backup may have, P12-10). */
        const val VERSION = 7

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_settings ADD COLUMN audioTrackTitle TEXT")
                db.execSQL("ALTER TABLE file_settings ADD COLUMN audioTrackLang TEXT")
                db.execSQL("ALTER TABLE file_settings ADD COLUMN audioEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE file_settings ADD COLUMN subTrackTitle TEXT")
                db.execSQL("ALTER TABLE file_settings ADD COLUMN subTrackLang TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `folder_settings` (
                        `folderPath` TEXT NOT NULL,
                        `audioLang` TEXT,
                        `audioTitle` TEXT,
                        `subLang` TEXT,
                        `subTitle` TEXT,
                        `subKind` TEXT,
                        `subEnabled` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`folderPath`)
                    )
                """.trimIndent())
            }
        }

        /** P4-13: per-file subtitle and audio delay (ms). Existing history rows keep their data, delays start at 0. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_settings ADD COLUMN subDelayMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE file_settings ADD COLUMN audioDelayMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** P4-14: content URI of an external subtitle loaded via SAF. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_settings ADD COLUMN externalSubUri TEXT")
            }
        }

        /** P12-03 bookmarks, P12-04 per-folder (show) speed and auto-skip. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `label` TEXT, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_fileKey` ON `bookmarks` (`fileKey`)")
                db.execSQL("ALTER TABLE folder_settings ADD COLUMN speed REAL")
                db.execSQL("ALTER TABLE folder_settings ADD COLUMN autoSkipChapters INTEGER")
            }
        }

        /** P12-10: closes the database before a backup replaces its file (the app restarts right after). */
        fun closeForRestore() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /** P13: saved network sources (password encrypted, see com.vibeplayer.net.Secrets). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `network_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `path` TEXT NOT NULL, `username` TEXT NOT NULL, `secret` TEXT, `createdAt` INTEGER NOT NULL)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vibeplayer.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { INSTANCE = it }
            }
        }
    }
}
