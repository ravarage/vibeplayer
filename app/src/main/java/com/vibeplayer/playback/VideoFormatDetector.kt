package com.vibeplayer.playback

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.vibeplayer.player.VideoFormat
import java.io.File

/**
 * Fast video format detection via Android MediaExtractor (MIME, profile, bit depth).
 *
 * Runs off the main thread in parallel with engine init / settings read, costing well under 50 ms.
 */
object VideoFormatDetector {

    /**
     * Pure function to determine [VideoFormat] from container/track metadata.
     * Accessible for host unit testing without Android runtime.
     */
    fun parseFormat(mime: String?, profile: Int? = null, bitDepth: Int? = null): VideoFormat {
        if (mime == null) return VideoFormat.OTHER
        val lowerMime = mime.lowercase().trim()

        if (lowerMime.contains("av01") || lowerMime.contains("av1")) {
            return VideoFormat.AV1
        }
        if (lowerMime.contains("hevc") || lowerMime.contains("h265")) {
            return VideoFormat.HEVC
        }
        if (lowerMime.contains("vp9")) {
            return VideoFormat.VP9
        }
        if (lowerMime.contains("avc") || lowerMime.contains("h264")) {
            // Check if 10-bit (Hi10P)
            // AVCProfileHigh10 = 16, AVCProfileHigh422 = 32, AVCProfileHigh444 = 64
            val isHi10Profile = profile != null && (profile == 16 || profile == 32 || profile == 64)
            val is10BitDepth = bitDepth != null && bitDepth >= 10
            return if (isHi10Profile || is10BitDepth) {
                VideoFormat.H264_10BIT
            } else {
                VideoFormat.H264_8BIT
            }
        }

        return VideoFormat.OTHER
    }

    /**
     * Detects [VideoFormat] for the given file or content URI using [MediaExtractor].
     * Must be called off the main thread.
     */
    fun detect(context: Context, pathOrUri: String, identityKey: String? = null): VideoFormat {
        val startMs = SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        try {
            val targetUriString = when {
                identityKey?.startsWith("content://") == true -> identityKey
                pathOrUri.startsWith("content://") -> pathOrUri
                else -> null
            }

            if (targetUriString != null) {
                extractor.setDataSource(context, Uri.parse(targetUriString), null)
            } else {
                val cleanPath = if (pathOrUri.startsWith("file://")) {
                    pathOrUri.removePrefix("file://")
                } else {
                    pathOrUri
                }
                extractor.setDataSource(cleanPath)
            }

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                        try { format.getInteger(MediaFormat.KEY_PROFILE) } catch (_: Throwable) { null }
                    } else null

                    val bitDepth = when {
                        format.containsKey("bit-depth") -> try { format.getInteger("bit-depth") } catch (_: Throwable) { null }
                        format.containsKey("bits-per-sample") -> try { format.getInteger("bits-per-sample") } catch (_: Throwable) { null }
                        else -> null
                    }

                    val detected = parseFormat(mime, profile, bitDepth)
                    val costMs = SystemClock.elapsedRealtime() - startMs
                    Log.i("VP-Perf", "Format detection for $pathOrUri took ${costMs} ms: mime=$mime profile=$profile bitDepth=$bitDepth -> $detected")
                    return detected
                }
            }
        } catch (e: Throwable) {
            Log.w("VP", "Format detection failed for $pathOrUri (${e.message}), falling back to OTHER")
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
        val costMs = SystemClock.elapsedRealtime() - startMs
        Log.i("VP-Perf", "Format detection for $pathOrUri took ${costMs} ms -> OTHER (fallback)")
        return VideoFormat.OTHER
    }
}
