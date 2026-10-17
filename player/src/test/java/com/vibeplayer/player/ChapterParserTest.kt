package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {

    @Test
    fun parseCountZeroReturnsEmptyList() {
        val result = ChapterParser.parse(
            count = 0L,
            getTitle = { "Chapter" },
            getTimeSec = { 0.0 }
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseNegativeCountReturnsEmptyList() {
        val result = ChapterParser.parse(
            count = -1L,
            getTitle = { "Chapter" },
            getTimeSec = { 0.0 }
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseEmptyOrBlankTitlesDefaultToChapterN() {
        val titles = listOf(null, "", "   ", "Custom Title")
        val result = ChapterParser.parse(
            count = titles.size.toLong(),
            getTitle = { titles[it] },
            getTimeSec = { it * 10.0 }
        )
        assertEquals(4, result.size)
        assertEquals(Chapter(index = 0, title = "Chapter 1", startMs = 0L), result[0])
        assertEquals(Chapter(index = 1, title = "Chapter 2", startMs = 10000L), result[1])
        assertEquals(Chapter(index = 2, title = "Chapter 3", startMs = 20000L), result[2])
        assertEquals(Chapter(index = 3, title = "Custom Title", startMs = 30000L), result[3])
    }

    @Test
    fun parseKeepsUnsortedEntriesInMpvOrder() {
        // mpv may report chapters in file order that is not strictly sorted by time
        val times = listOf(30.0, 10.0, 50.0, 5.0)
        val result = ChapterParser.parse(
            count = times.size.toLong(),
            getTitle = { "Part ${it + 1}" },
            getTimeSec = { times[it] }
        )
        assertEquals(4, result.size)
        assertEquals(0, result[0].index)
        assertEquals(30000L, result[0].startMs)
        assertEquals(1, result[1].index)
        assertEquals(10000L, result[1].startMs)
        assertEquals(2, result[2].index)
        assertEquals(50000L, result[2].startMs)
        assertEquals(3, result[3].index)
        assertEquals(5000L, result[3].startMs)
    }

    @Test
    fun parseKeepsZeroLengthEntriesInMpvOrder() {
        // Chapters starting at the exact same timestamp (zero-length)
        val times = listOf(0.0, 0.0, 15.5)
        val result = ChapterParser.parse(
            count = times.size.toLong(),
            getTitle = { if (it == 0) "Prologue" else if (it == 1) "Intro" else "Scene 1" },
            getTimeSec = { times[it] }
        )
        assertEquals(3, result.size)
        assertEquals(Chapter(index = 0, title = "Prologue", startMs = 0L), result[0])
        assertEquals(Chapter(index = 1, title = "Intro", startMs = 0L), result[1])
        assertEquals(Chapter(index = 2, title = "Scene 1", startMs = 15500L), result[2])
    }

    @Test
    fun parseNegativeTimeCoercedToZero() {
        val result = ChapterParser.parse(
            count = 1L,
            getTitle = { null },
            getTimeSec = { -2.5 }
        )
        assertEquals(1, result.size)
        assertEquals(Chapter(index = 0, title = "Chapter 1", startMs = 0L), result[0])
    }

    @Test
    fun parseNullTimeDefaultsToZero() {
        val result = ChapterParser.parse(
            count = 1L,
            getTitle = { "Start" },
            getTimeSec = { null }
        )
        assertEquals(1, result.size)
        assertEquals(Chapter(index = 0, title = "Start", startMs = 0L), result[0])
    }
}
