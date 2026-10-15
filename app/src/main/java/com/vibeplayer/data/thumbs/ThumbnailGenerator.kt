package com.vibeplayer.data.thumbs

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
            val sync = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val exact = MediaMetadataRetriever.OPTION_CLOSEST
            // Keyframes first (cheap). A clip whose few keyframes all fall on dark frames (long GOP,
            // fade from black) gives only black that way, so then decode the exact frame at 50% and
            // 25%: slower, but it runs only after every keyframe candidate was rejected.
            val candidates = durationUs?.takeIf { it > 0L }
                ?.let { d ->
                    listOf(10L, 25L, 50L, 75L).map { d * it / 100L }.distinct().map { it to sync } +
                        listOf(50L, 25L).map { (d * it / 100L) to exact }
                }
                ?: listOf(timeUs to sync)
            var best: Bitmap? = null
            var bestDetail = 0
            for ((candidateUs, option) in candidates) {
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(candidateUs, option, targetWidth, targetHeight)
                        ?: retriever.getFrameAtTime(candidateUs, option)
                } else {
                    retriever.getFrameAtTime(candidateUs, option)
                } ?: continue
                val scaled = if (rawBitmap.width > targetWidth * 2 || rawBitmap.height > targetHeight * 2) {
                    val scale = minOf(targetWidth.toFloat() / rawBitmap.width, targetHeight.toFloat() / rawBitmap.height)
                    Bitmap.createScaledBitmap(rawBitmap, (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                        (rawBitmap.height * scale).toInt().coerceAtLeast(1), true).also {
                        if (it !== rawBitmap) rawBitmap.recycle()
                    }
                } else rawBitmap
                val detail = thumbnailDetail(scaled)
                if (detail >= USABLE_DETAIL) {
                    best?.recycle()
                    return@withContext scaled
                }
                Log.d("VP", "Thumbnail candidate rejected (near-black, detail=$detail) at ${candidateUs / 1000} ms${if (option == exact) " (exact)" else ""} via MediaMetadataRetriever: $path")
                if (detail > bestDetail) {
                    best?.recycle()
                    best = scaled
                    bestDetail = detail
                } else scaled.recycle()
            }
            // Dark throughout: the brightest candidate (may be null); the generator decides.
            best
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
            var bestDetail = 0
            for (percent in listOf(10, 25, 50, 75)) {
                // Each attempt opens the file again under a process-wide lock: bound the total.
                if (android.os.SystemClock.elapsedRealtime() - started > BUDGET_MS) break
                val frame = engine.extract(path, targetWidth, targetHeight, percent) ?: continue
                val detail = thumbnailDetail(frame)
                if (detail >= USABLE_DETAIL) {
                    bitmap?.recycle()
                    bitmap = frame
                    break
                }
                Log.d("VP", "Thumbnail candidate rejected (near-black, detail=$detail) at $percent% via mpv: $path")
                if (detail > bestDetail) {
                    bitmap?.recycle()
                    bitmap = frame
                    bestDetail = detail
                } else frame.recycle()
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

    private companion object {
        const val BUDGET_MS = 12_000L
    }
}

/** Samples out of 144 (16 x 9 grid) that must show picture for a frame to be accepted outright (5%). */
internal const val USABLE_DETAIL = 7

/**
 * Number of grid samples (0..144) brighter than near-black. Extractors use it to reject near-black
 * frames and retry later in the clip; 0 means the frame is black everywhere.
 */
internal fun thumbnailDetail(bitmap: Bitmap): Int {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0
    val columns = 16
    val rows = 9
    var visible = 0
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
    return visible
}

internal fun isUsableThumbnail(bitmap: Bitmap): Boolean = thumbnailDetail(bitmap) >= USABLE_DETAIL

/**
 * P7-02: while the player is opening a file, thumbnail extraction waits so it does not compete with
 * mpv start-up for CPU, IO and the media codecs. Extractions already running are not interrupted.
 */
object ThumbnailPause {
    private val paused = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun setPaused(value: Boolean) { paused.value = value }
    suspend fun awaitResumed() { if (paused.value) paused.first { !it } }
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
            ThumbnailPause.awaitResumed()
            semaphore.withPermit {
                // Platform retriever first. It returns black for some files it cannot really
                // decode (10-bit), so a near-black result still goes through the mpv extractor.
                val primary = primaryExtractor.extractFrame(path, timeUs, targetWidth, targetHeight)
                if (primary != null && isUsableThumbnail(primary)) {
                    Log.d("VP", "Thumbnail extracted successfully via MediaMetadataRetriever for: $path")
                    return@withPermit primary
                }
                Log.w("VP", "Primary extractor gave ${if (primary == null) "nothing" else "only near-black frames"} for $path, falling back to mpv extractor")
                val fallback = fallbackExtractor.extractFrame(path, timeUs, targetWidth, targetHeight)
                if (fallback != null && isUsableThumbnail(fallback)) {
                    Log.d("VP", "Thumbnail extracted via fallback extractor for: $path")
                    primary?.recycle()
                    return@withPermit fallback
                }
                // The clip is dark at every tried position: show its brightest frame rather than
                // a placeholder. Extractors return null instead of a fully black frame.
                val primaryDetail = primary?.let { thumbnailDetail(it) } ?: 0
                val fallbackDetail = fallback?.let { thumbnailDetail(it) } ?: 0
                val best = if (fallbackDetail > primaryDetail) fallback else primary
                if (best !== primary) primary?.recycle()
                if (best !== fallback) fallback?.recycle()
                if (best != null) {
                    Log.d("VP", "Thumbnail is a dark frame (detail=${maxOf(primaryDetail, fallbackDetail)}/144, clip dark at all tried positions) for: $path")
                } else {
                    Log.w("VP", "No thumbnail could be extracted for: $path")
                }
                best
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
