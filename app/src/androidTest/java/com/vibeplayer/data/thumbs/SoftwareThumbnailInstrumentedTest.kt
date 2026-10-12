package com.vibeplayer.data.thumbs

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.player.ThumbnailEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SoftwareThumbnailInstrumentedTest {
    @Test
    fun extractsRealFramesFromPreviouslyUnsupportedVideos() = runBlocking {
        val root = File("/sdcard/Movies/vibeplayer-test")
        val clips = listOf(
            "hevc10_4k.mp4",
            "av1_4k.mp4",
            "[denisplay] Bocchi the Rock! - S01E01.mkv",
            "[Ironclad] Clevatess - S02E01 [WEB.1080p.AV1].mkv",
            "Bad Girl - S01E01.mkv"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ThumbnailEngine()
        for ((index, name) in clips.withIndex()) {
            val file = File(root, name)
            assertTrue("Missing test clip: $name", file.isFile)
            val start = SystemClock.elapsedRealtime()
            val bitmap = engine.extract(file.absolutePath, 320, 180)
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i("VP-Perf", "software thumbnail path=$name elapsedMs=$elapsed success=${bitmap != null}")
            assertNotNull("No extracted frame for $name", bitmap)
            assertPicture(bitmap!!, name)
            assertTrue("Unexpected dimensions for $name: ${bitmap.width}x${bitmap.height}",
                bitmap.width in 1..320 && bitmap.height in 1..180)
            if (index < 3) {
                File(context.cacheDir, "thumb-p2-10-$index.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            bitmap.recycle()
        }
    }

    private fun assertPicture(bitmap: Bitmap, name: String) {
        var colorful = 0
        val sample = IntArray(1)
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                bitmap.getPixels(sample, 0, 1, x, y, 1, 1)
                val color = sample[0]
                if (((color shr 16) and 0xff) + ((color shr 8) and 0xff) + (color and 0xff) > 36) colorful++
            }
        }
        assertTrue("Extracted frame is black for $name", colorful > 8)
    }
}
