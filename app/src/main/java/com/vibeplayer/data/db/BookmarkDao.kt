package com.vibeplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE fileKey = :fileKey ORDER BY positionMs")
    fun observe(fileKey: String): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    /** A renamed file keeps its bookmarks. */
    @Query("UPDATE bookmarks SET fileKey = :newKey WHERE fileKey = :oldKey")
    suspend fun moveKey(oldKey: String, newKey: String)

    @Query("DELETE FROM bookmarks WHERE fileKey = :fileKey")
    suspend fun deleteForKey(fileKey: String)
}
