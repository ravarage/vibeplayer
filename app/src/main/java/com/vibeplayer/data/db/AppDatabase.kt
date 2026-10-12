package com.vibeplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileSettings::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileSettingsDao(): FileSettingsDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vibeplayer.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
        }
    }
}
