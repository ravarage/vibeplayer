package com.vibeplayer.data.db

import com.vibeplayer.ui.normalizeMediaPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HistoryKeyNormalizationTest {

    @Test
    fun testNormalizeMediaPath() {
        assertEquals(
            "/storage/emulated/0/Movies/test.mp4",
            normalizeMediaPath("/sdcard/Movies/test.mp4")
        )
        assertEquals(
            "/storage/emulated/0/Movies/test.mp4",
            normalizeMediaPath("/storage/emulated/0/Movies/test.mp4")
        )
        assertEquals(
            "/storage/emulated/0/Movies/test.mp4",
            normalizeMediaPath("/storage/self/primary/Movies/test.mp4")
        )
    }

    @Test
    fun testHistoryDeduplicationLogic() {
        val rowIntent = FileSettings(
            key = "/sdcard/Movies/test.mp4:5000",
            positionMs = 1000L,
            durationMs = 10000L,
            lastPlayedAt = 100L
        )
        val rowBrowser = FileSettings(
            key = "/storage/emulated/0/Movies/test.mp4:5000",
            positionMs = 5000L,
            durationMs = 10000L,
            lastPlayedAt = 200L
        )
        // Ordered by lastPlayedAt DESC: rowBrowser is newer
        val historyList = listOf(rowBrowser, rowIntent)

        val deduplicated = historyList.asSequence()
            .filter { it.positionMs > 0L && it.durationMs > 0L }
            .distinctBy { normalizeMediaPath(it.key.substringBeforeLast(":")) }
            .toList()

        assertEquals(1, deduplicated.size)
        assertEquals(rowBrowser.key, deduplicated[0].key)
        assertEquals(5000L, deduplicated[0].positionMs)
    }

    @Test
    fun testHistoryMapLookupBothPaths() {
        val rowBrowser = FileSettings(
            key = "/storage/emulated/0/Movies/test.mp4:5000",
            positionMs = 5000L,
            durationMs = 10000L,
            lastPlayedAt = 200L
        )
        val historyList = listOf(rowBrowser)

        val map = mutableMapOf<String, FileSettings>()
        for (item in historyList) {
            map.putIfAbsent(item.key, item)
            val p = item.key.substringBeforeLast(":")
            map.putIfAbsent(p, item)
            val normP = normalizeMediaPath(p)
            map.putIfAbsent(normP, item)
            val size = item.key.substringAfterLast(":")
            if (size != item.key) {
                map.putIfAbsent("$normP:$size", item)
            }
        }

        // Exact match
        assertEquals(rowBrowser, map["/storage/emulated/0/Movies/test.mp4:5000"])
        // Without size
        assertEquals(rowBrowser, map["/storage/emulated/0/Movies/test.mp4"])
        // Via /sdcard/ normalized
        assertEquals(rowBrowser, map[normalizeMediaPath("/sdcard/Movies/test.mp4")])
    }
}
