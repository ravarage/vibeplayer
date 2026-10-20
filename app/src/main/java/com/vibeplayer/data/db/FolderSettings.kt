package com.vibeplayer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folder_settings")
data class FolderSettings(
    @PrimaryKey
    val folderPath: String,
    val audioLang: String? = null,
    val audioTitle: String? = null,
    val subLang: String? = null,
    val subTitle: String? = null,
    val subKind: String? = null,
    val subEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    /** P12-04: speed for the files of this folder (a show) that have no speed of their own; null = 1x. */
    val speed: Float? = null,
    /** P12-04: auto-skip openings and endings here; null follows the app setting. */
    val autoSkipChapters: Boolean? = null
)
