package com.vibeplayer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_settings")
data class FileSettings(
    @PrimaryKey
    val key: String, // path + size
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioTrackId: Int? = null,
    val subTrackId: Int? = null,
    val subEnabled: Boolean = true,
    val speed: Float = 1.0f,
    val aspect: String? = null,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
