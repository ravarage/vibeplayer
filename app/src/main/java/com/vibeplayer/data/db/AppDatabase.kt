package com.vibeplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileSettings::class, FolderSettings::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileSettingsDao(): FileSettingsDao
    abstract fun folderSettingsDao(): FolderSettingsDao

    companion object {
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vibeplayer.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { INSTANCE = it }
            }
        }
    }
}
