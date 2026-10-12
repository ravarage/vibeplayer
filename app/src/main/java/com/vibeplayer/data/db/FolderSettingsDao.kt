package com.vibeplayer.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FolderSettingsDao {
    @Query("SELECT * FROM folder_settings WHERE folderPath = :folderPath LIMIT 1")
    suspend fun getByPath(folderPath: String): FolderSettings?

    @Upsert
    suspend fun upsert(settings: FolderSettings)

    @Query("DELETE FROM folder_settings WHERE folderPath = :folderPath")
    suspend fun delete(folderPath: String)
}
