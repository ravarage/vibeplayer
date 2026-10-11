package com.vibeplayer.data.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ThumbnailCache(
    private val context: Context,
    private val generator: ThumbnailGenerator = ThumbnailGenerator()
) {
    private val cacheDir = File(context.cacheDir, "thumbnails").apply {
        if (!exists()) mkdirs()
    }

    // Memory LRU Cache: 1/8th of available runtime memory in KB
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(1024)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return (bitmap.byteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun makeCacheKey(path: String, sizeBytes: Long, mtime: Long): String {
        val raw = "$path:$sizeBytes:$mtime"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getFromMemory(key: String): Bitmap? {
        return memoryCache.get(key)
    }

    suspend fun getThumbnail(
        path: String,
        sizeBytes: Long,
        mtime: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = makeCacheKey(path, sizeBytes, mtime)

        // 1. Memory cache check
        memoryCache.get(key)?.let {
            return@withContext it
        }

        // 2. Disk cache check
        val diskFile = File(cacheDir, "$key.webp")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    Log.d("VP", "Thumbnail disk cache HIT for $path")
                    memoryCache.put(key, bitmap)
                    return@withContext bitmap
                }
            } catch (e: Exception) {
                Log.w("VP", "Failed to decode cached thumbnail: ${diskFile.name}", e)
                diskFile.delete()
            }
        }

        // 3. Disk cache MISS -> extract and save
        Log.d("VP", "Thumbnail cache MISS for $path, extracting frame...")
        val generated = generator.generateThumbnail(path)
        if (generated != null) {
            memoryCache.put(key, generated)
            try {
                FileOutputStream(diskFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        generated.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    } else {
                        generated.compress(Bitmap.CompressFormat.PNG, 85, out)
                    }
                }
                Log.d("VP", "Saved thumbnail to disk cache: ${diskFile.name}")
            } catch (e: Exception) {
                Log.w("VP", "Failed to write thumbnail to disk cache for $path", e)
            }
        }

        generated
    }
}

@Composable
fun AsyncThumbnail(
    path: String,
    sizeBytes: Long,
    mtime: Long,
    cache: ThumbnailCache,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit
) {
    val key = remember(path, sizeBytes, mtime) { cache.makeCacheKey(path, sizeBytes, mtime) }
    var bitmap by remember(key) { mutableStateOf(cache.getFromMemory(key)) }

    LaunchedEffect(key) {
        if (bitmap == null && path.isNotEmpty()) {
            bitmap = cache.getThumbnail(path, sizeBytes, mtime)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    } else {
        placeholder()
    }
}
