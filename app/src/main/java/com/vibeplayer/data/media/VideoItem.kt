package com.vibeplayer.data.media

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val displayName: String,
    val bucketId: String,
    val bucketName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int
)
