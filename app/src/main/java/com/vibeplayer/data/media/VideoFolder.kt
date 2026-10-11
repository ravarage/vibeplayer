package com.vibeplayer.data.media

data class VideoFolder(
    val bucketId: String,
    val name: String,
    val videoCount: Int,
    val firstVideoPath: String? = null
)
