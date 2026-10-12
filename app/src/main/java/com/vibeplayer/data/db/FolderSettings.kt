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
    val updatedAt: Long = System.currentTimeMillis()
)
