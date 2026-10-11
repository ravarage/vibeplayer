package com.vibeplayer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FileSettingsDao {

    @Upsert
    suspend fun upsert(settings: FileSettings)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: FileSettings)

    @Query("SELECT * FROM file_settings WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): FileSettings?

    @Query("SELECT * FROM file_settings ORDER BY lastPlayedAt DESC")
    suspend fun getAllHistory(): List<FileSettings>

    @Query("SELECT * FROM file_settings ORDER BY lastPlayedAt DESC")
    fun observeHistory(): Flow<List<FileSettings>>

    @Delete
    suspend fun delete(settings: FileSettings)

    @Query("DELETE FROM file_settings WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM file_settings")
    suspend fun clearAll()
}
