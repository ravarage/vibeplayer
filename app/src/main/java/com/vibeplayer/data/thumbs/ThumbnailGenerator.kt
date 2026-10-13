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
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1_000L)
            val times = durationUs?.takeIf { it > 0L }
                ?.let { listOf(10L, 25L, 50L, 75L).map { percent -> it * percent / 100L }.distinct() }
                ?: listOf(timeUs)
            for (candidateUs in times) {
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        candidateUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    ) ?: retriever.getFrameAtTime(candidateUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    retriever.getFrameAtTime(candidateUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: continue
                val scaled = if (rawBitmap.width > targetWidth * 2 || rawBitmap.height > targetHeight * 2) {
                    val scale = minOf(targetWidth.toFloat() / rawBitmap.width, targetHeight.toFloat() / rawBitmap.height)
                    Bitmap.createScaledBitmap(rawBitmap, (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                        (rawBitmap.height * scale).toInt().coerceAtLeast(1), true).also {
                        if (it !== rawBitmap) rawBitmap.recycle()
                    }
                } else rawBitmap
                if (isUsableThumbnail(scaled)) return@withContext scaled
                scaled.recycle()
            }
            null
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
            var bitmap: Bitmap? = null
            for (percent in listOf(10, 25, 50, 75)) {
                val frame = engine.extract(path, targetWidth, targetHeight, percent) ?: continue
                if (isUsableThumbnail(frame)) {
                    bitmap = frame
                    break
                }
                frame.recycle()
            }
            bitmap.also { bitmap ->
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

/** Reject fully/near-black extraction results so the cache can retry a later point in the clip. */
internal fun isUsableThumbnail(bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return false
    val columns = 16
    val rows = 9
    var visible = 0
    val total = columns * rows
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val color = bitmap.getPixel(
                ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1),
                ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            )
            val luminance = ((android.graphics.Color.red(color) * 299 +
                android.graphics.Color.green(color) * 587 + android.graphics.Color.blue(color) * 114) / 1000)
            if (luminance > 18) visible++
        }
    }
    return visible >= total / 20 // at least 5% of samples contain picture detail
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
