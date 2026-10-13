package com.vibeplayer.player

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Software-only, isolated mpv extractor. Calls are serialized across the process. */
class ThumbnailEngine {
    suspend fun extract(path: String, width: Int = 320, height: Int = 180, startPercent: Int = 10): Bitmap? =
        withContext(Dispatchers.IO) {
            extractionMutex.withLock {
                val frame = MpvNative.extractThumbnail(path, width, height, TIMEOUT_MS, startPercent.coerceIn(1, 90)) ?: return@withLock null
                if (frame.width <= 0 || frame.height <= 0 || frame.stride < frame.width * 4 ||
                    frame.pixels.size < frame.stride * frame.height
                ) return@withLock null

                val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                val row = IntArray(frame.width)
                for (y in 0 until frame.height) {
                    val base = y * frame.stride
                    for (x in 0 until frame.width) {
                        val i = base + x * 4
                        val b = frame.pixels[i].toInt() and 0xff
                        val g = frame.pixels[i + 1].toInt() and 0xff
                        val r = frame.pixels[i + 2].toInt() and 0xff
                        row[x] = Color.rgb(r, g, b)
                    }
                    bitmap.setPixels(row, 0, frame.width, 0, y, frame.width, 1)
                }
                bitmap
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000
        val extractionMutex = Mutex()
    }
}
