package com.vibeplayer.data.thumbs

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.vibeplayer.player.ThumbnailEngine

/**
 * Interface for video frame extraction.
 * Implemented by MediaMetadataRetrieverFrameExtractor, and later by mpv FrameExtractor on main.
 */
interface FrameExtractor {
    suspend fun extractFrame(
        path: String,
        timeUs: Long = 1_000_000L,
        targetWidth: Int = 320,
        targetHeight: Int = 180
    ): Bitmap?
}

/**
 * Android system MediaMetadataRetriever frame extractor.
 */
class MediaMetadataRetrieverFrameExtractor : FrameExtractor {
    override suspend fun extractFrame(
        path: String,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (rawBitmap != null && (rawBitmap.width > targetWidth * 2 || rawBitmap.height > targetHeight * 2)) {
                val scale = minOf(targetWidth.toFloat() / rawBitmap.width, targetHeight.toFloat() / rawBitmap.height)
                val destW = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
                val destH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawBitmap, destW, destH, true).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            Log.w("VP", "MediaMetadataRetriever failed for $path: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }
}

/**
 * Isolated software-only libmpv fallback for formats the platform retriever cannot decode.
 */
class MpvFallbackFrameExtractor : FrameExtractor {
    private val engine = ThumbnailEngine()

    override suspend fun extractFrame(
        path: String,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            engine.extract(path, targetWidth, targetHeight).also { bitmap ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                Log.i("VP-Perf", "software thumbnail path=$path elapsedMs=$elapsed success=${bitmap != null}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VP", "mpv thumbnail fallback failed for $path", e)
            null
        }
    }
}

/**
 * Thumbnail generator with max 2 parallel extractions and cancellation support.
 */
class ThumbnailGenerator(
    private val primaryExtractor: FrameExtractor = MediaMetadataRetrieverFrameExtractor(),
    private val fallbackExtractor: FrameExtractor = MpvFallbackFrameExtractor(),
    maxParallel: Int = 2
) {
    private val semaphore = Semaphore(maxParallel)

    suspend fun generateThumbnail(
        path: String,
        timeUs: Long = 1_000_000L,
        targetWidth: Int = 320,
        targetHeight: Int = 180
    ): Bitmap? {
        return try {
            semaphore.withPermit {
                // Try primary retriever first
                val bitmap = primaryExtractor.extractFrame(path, timeUs, targetWidth, targetHeight)
                if (bitmap != null) {
                    Log.d("VP", "Thumbnail extracted successfully via MediaMetadataRetriever for: $path")
                    bitmap
                } else {
                    Log.w("VP", "Primary extractor failed for $path, falling back to mpv extractor")
                    val fallbackBitmap = fallbackExtractor.extractFrame(path, timeUs, targetWidth, targetHeight)
                    if (fallbackBitmap != null) {
                        Log.d("VP", "Thumbnail extracted via fallback extractor for: $path")
                        fallbackBitmap
                    } else {
                        Log.w("VP", "No thumbnail could be extracted for: $path")
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d("VP", "Thumbnail extraction cancelled for: $path")
            throw e
        } catch (e: Exception) {
            Log.e("VP", "Thumbnail generation error for $path", e)
            null
        }
    }
}
