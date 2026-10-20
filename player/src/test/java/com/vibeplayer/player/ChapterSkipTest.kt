package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSkipTest {

    @Test
    fun openingTitles() {
        for (t in listOf("Opening", "OP", "op2", "Intro", "Opening Theme", "OP - Song name", "[OP]", "Introduction")) {
            assertEquals(t, ChapterKind.OPENING, ChapterSkip.kindOf(t))
        }
    }

    @Test
    fun endingTitles() {
        for (t in listOf("Ending", "ED", "ED1", "Outro", "Credits", "End Credits", "Ending Theme")) {
            assertEquals(t, ChapterKind.ENDING, ChapterSkip.kindOf(t))
        }
    }

    @Test
    fun otherTitlesAreNotSkipped() {
        for (t in listOf("", "Part A", "Part B", "Operation", "Edward", "Prologue", "Preview", "Episode", "Chapter 1", "Opened")) {
            assertEquals(t, ChapterKind.OTHER, ChapterSkip.kindOf(t))
        }
    }

    @Test
    fun skipTargetIsNextStartOrNullAtTheEnd() {
        val chapters = listOf(
            Chapter(0, "Prologue", 0L), Chapter(1, "Opening", 90_000L),
            Chapter(2, "Part A", 180_000L), Chapter(3, "Ending", 1_300_000L)
        )
        assertEquals(180_000L, ChapterSkip.skipTargetMs(chapters, chapters[1]))
        assertNull(ChapterSkip.skipTargetMs(chapters, chapters[3]))
        // Unsorted mpv order: the next start in time wins.
        val unsorted = listOf(Chapter(0, "B", 600_000L), Chapter(1, "OP", 0L), Chapter(2, "A", 90_000L))
        assertEquals(90_000L, ChapterSkip.skipTargetMs(unsorted, unsorted[1]))
    }

    @Test
    fun skippableCurrent() {
        val chapters = listOf(Chapter(0, "OP", 0L), Chapter(1, "Part A", 90_000L))
        assertEquals(0, ChapterSkip.skippableCurrent(chapters, 0)?.index)
        assertNull(ChapterSkip.skippableCurrent(chapters, 1))
        assertNull(ChapterSkip.skippableCurrent(chapters, -1))
        assertNull(ChapterSkip.skippableCurrent(emptyList(), 0))
    }
}
