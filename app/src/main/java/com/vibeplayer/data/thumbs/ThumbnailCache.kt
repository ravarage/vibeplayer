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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ThumbnailCache(
    private val context: Context,
    private val generator: ThumbnailGenerator = ThumbnailGenerator()
) {
    private val cacheDir = File(context.cacheDir, "thumbnails")
    private val extractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    companion object {
        const val NEGATIVE_CACHE_VERSION = 2
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

    // Fast in-memory negative cache to prevent repeated disk checks
    private val negativeMemoryCache = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    fun makeCacheKey(path: String, sizeBytes: Long, mtime: Long): String {
        return "$path:$sizeBytes:$mtime"
    }

    private fun diskKey(raw: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getFromMemory(key: String): Bitmap? {
        return memoryCache.get(key)
    }

    fun isNegativeCached(key: String): Boolean {
        return negativeMemoryCache.contains(key)
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int = 320, reqHeight: Int = 180): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            Log.w("VP", "Failed to decode sampled bitmap from ${file.name}", e)
            null
        }
    }

    suspend fun getThumbnail(
        path: String,
        sizeBytes: Long,
        mtime: Long,
        targetWidth: Int = 320,
        targetHeight: Int = 180
    ): Bitmap? {
        val key = makeCacheKey(path, sizeBytes, mtime)
        memoryCache.get(key)?.let {
            return it
        }
        if (negativeMemoryCache.contains(key)) {
            return null
        }
        val deferred = inFlight.computeIfAbsent(key) {
            extractionScope.async { loadThumbnail(key, path, targetWidth, targetHeight) }
        }
        deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
        try {
            return deferred.await()?.also { memoryCache.put(key, it) }
        } finally {
            if (deferred.isCompleted) inFlight.remove(key, deferred)
        }
    }

    private suspend fun loadThumbnail(key: String, path: String, targetWidth: Int, targetHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!cacheDir.exists() && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
            Log.w("VP", "Failed to create thumbnail cache directory")
        }
        val fileKey = diskKey(key)

        // 2. Disk negative cache check (marker file)
        val markerFile = File(cacheDir, "$fileKey.failed")
        if (markerFile.exists()) {
            try {
                val versionStr = markerFile.readText().trim()
                if (versionStr == NEGATIVE_CACHE_VERSION.toString()) {
                    negativeMemoryCache.add(key)
                    return@withContext null
                } else {
                    // Marker version changed or invalid, re-attempt
                    markerFile.delete()
                }
            } catch (e: Exception) {
                markerFile.delete()
            }
        }

        // 3. Disk cache check with display-size decode
        val diskFile = File(cacheDir, "$fileKey.webp")
        if (diskFile.exists() && diskFile.length() > 0) {
            val bitmap = decodeSampledBitmap(diskFile, targetWidth, targetHeight)
            if (bitmap != null) {
                Log.d("VP", "Thumbnail disk cache HIT for $path")
                memoryCache.put(key, bitmap)
                return@withContext bitmap
            } else {
                Log.w("VP", "Failed to decode cached thumbnail: ${diskFile.name}")
                diskFile.delete()
            }
        }

        // 4. Disk cache MISS -> extract and save
        Log.d("VP", "Thumbnail cache MISS for $path, extracting frame...")
        val generated = generator.generateThumbnail(path, targetWidth = targetWidth, targetHeight = targetHeight)
        if (generated != null) {
            memoryCache.put(key, generated)
            try {
                val tempFile = File(cacheDir, "$fileKey.webp.tmp")
                tempFile.delete()
                FileOutputStream(tempFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        generated.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    } else {
                        generated.compress(Bitmap.CompressFormat.PNG, 85, out)
                    }
                }
                if (!tempFile.renameTo(diskFile)) {
                    tempFile.delete()
                    Log.w("VP", "Failed to atomically publish thumbnail cache file: ${diskFile.name}")
                } else {
                    Log.d("VP", "Saved thumbnail to disk cache: ${diskFile.name}")
                }
            } catch (e: Exception) {
                Log.w("VP", "Failed to write thumbnail to disk cache for $path", e)
            }
            generated
        } else {
            // Extraction failed -> write negative cache marker
            try {
                markerFile.writeText(NEGATIVE_CACHE_VERSION.toString())
                negativeMemoryCache.add(key)
                Log.d("VP", "Saved negative cache marker for $path: ${markerFile.name}")
            } catch (e: Exception) {
                Log.w("VP", "Failed to write negative cache marker for $path", e)
            }
            null
        }
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
