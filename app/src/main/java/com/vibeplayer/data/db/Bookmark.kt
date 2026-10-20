package com.vibeplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved point in a file (P12-03), keyed like [FileSettings] (path + size). */
@Entity(tableName = "bookmarks", indices = [Index("fileKey")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileKey: String,
    val positionMs: Long,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
