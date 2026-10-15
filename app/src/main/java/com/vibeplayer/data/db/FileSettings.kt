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
    val audioTrackTitle: String? = null,
    val audioTrackLang: String? = null,
    val audioEnabled: Boolean = true,
    val subTrackId: Int? = null,
    val subTrackTitle: String? = null,
    val subTrackLang: String? = null,
    val subEnabled: Boolean = true,
    val speed: Float = 1.0f,
    val aspect: String? = null,
    val subDelayMs: Int = 0,
    val audioDelayMs: Int = 0,
    val externalSubUri: String? = null,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
