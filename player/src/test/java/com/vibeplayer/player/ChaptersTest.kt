package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    private fun build(titles: List<String?>, times: List<Double?>) =
        Chapters.build(titles.size.toLong(), { titles[it] }, { times[it] })

    @Test
    fun countZeroGivesEmptyList() {
        assertTrue(Chapters.build(0L, { "x" }, { 1.0 }).isEmpty())
        assertTrue(Chapters.build(-1L, { "x" }, { 1.0 }).isEmpty())
    }

    @Test
    fun readsTitlesAndTimesInMpvOrder() {
        val list = build(listOf("Opening", "Part A", "Ending"), listOf(0.0, 90.5, 1300.0))
        assertEquals(
            listOf(Chapter(0, "Opening", 0L), Chapter(1, "Part A", 90_500L), Chapter(2, "Ending", 1_300_000L)),
            list
        )
    }

    @Test
    fun emptyTitlesShowChapterNumber() {
        val list = build(listOf(null, "  ", "Named"), listOf(0.0, 10.0, 20.0))
        assertEquals(listOf("", "", "Named"), list.map { it.title })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Named"), list.map { Chapters.displayTitle(it) })
    }

    @Test
    fun unsortedAndZeroLengthEntriesKeptInMpvOrder() {
        val list = build(listOf("B", "A", "A2"), listOf(60.0, 0.0, 0.0))
        assertEquals(listOf(0, 1, 2), list.map { it.index })
        assertEquals(listOf(60_000L, 0L, 0L), list.map { it.startMs })
    }

    @Test
    fun entryWithoutTimeIsSkippedButIndexesStayMpvs() {
        val list = build(listOf("A", "broken", "C"), listOf(0.0, null, 30.0))
        assertEquals(listOf(0, 2), list.map { it.index })
        assertEquals(listOf(0, 2), build(listOf("A", "nan", "C"), listOf(0.0, Double.NaN, 30.0)).map { it.index })
    }

    @Test
    fun chapterAtPicksLatestStartAtOrBefore() {
        val list = build(listOf("A", "B", "C"), listOf(5.0, 60.0, 120.0))
        assertNull(Chapters.chapterAt(list, 4_999L))
        assertEquals("A", Chapters.chapterAt(list, 5_000L)?.title)
        assertEquals("B", Chapters.chapterAt(list, 119_999L)?.title)
        assertEquals("C", Chapters.chapterAt(list, 999_999L)?.title)
        assertEquals("B", Chapters.chapterAt(build(listOf("B", "A"), listOf(60.0, 0.0)), 70_000L)?.title)
        assertNull(Chapters.chapterAt(emptyList(), 0L))
    }

    @Test
    fun ticksSkipZeroAndOutOfRangeAndNeedDuration() {
        val list = build(listOf("A", "B", "C", "D"), listOf(0.0, 25.0, 25.0, 200.0))
        assertEquals(listOf(0.25f), Chapters.tickFractions(list, 100_000L))
        assertTrue(Chapters.tickFractions(list, 0L).isEmpty())
    }
}
