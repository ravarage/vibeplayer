package com.vibeplayer.data.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatcherTest {

    @Test
    fun matchesSameBaseNameAndTaggedNames() {
        assertTrue(SubtitleMatcher.matches("Ep01.mkv", "Ep01.srt"))
        assertTrue(SubtitleMatcher.matches("Ep01.mkv", "ep01.ASS"))
        assertTrue(SubtitleMatcher.matches("Ep01.mkv", "Ep01.en.srt"))
        assertTrue(SubtitleMatcher.matches("[Grp] Show - 01 [1080p].mkv", "[Grp] Show - 01 [1080p].eng.forced.ass"))
        assertTrue(SubtitleMatcher.matches("Movie.2020.mp4", "Movie.2020.vtt"))
    }

    @Test
    fun rejectsOtherFilesAndOtherEpisodes() {
        assertFalse(SubtitleMatcher.matches("Ep01.mkv", "Ep01.txt"))
        assertFalse(SubtitleMatcher.matches("Ep01.mkv", "Ep010.srt"))
        assertFalse(SubtitleMatcher.matches("Ep01.mkv", "Ep02.srt"))
        assertFalse(SubtitleMatcher.matches("Ep01.mkv", "Ep01 extra.srt"))
        assertFalse(SubtitleMatcher.matches("Ep01.mkv", "Ep01..srt"))
        assertFalse(SubtitleMatcher.matches(".mkv", ".srt"))
    }

    @Test
    fun selectPutsTheExactNameFirst() {
        val names = listOf("Ep01.ja.srt", "Ep02.srt", "Ep01.en.srt", "Ep01.srt", "cover.jpg")
        assertEquals(listOf("Ep01.srt", "Ep01.en.srt", "Ep01.ja.srt"), SubtitleMatcher.select("Ep01.mkv", names))
    }

    @Test
    fun folderDocumentIdInsideAndOutsideTheTree() {
        val video = "/storage/emulated/0/Movies/Anime/Ep01.mkv"
        assertEquals("primary:Movies/Anime", SubtitleMatcher.folderDocumentId("primary:Movies", video))
        assertEquals("primary:Movies/Anime", SubtitleMatcher.folderDocumentId("primary:Movies/Anime", video))
        assertEquals("primary:Movies/Anime", SubtitleMatcher.folderDocumentId("primary:", video))
        assertNull(SubtitleMatcher.folderDocumentId("primary:Download", video))
        assertNull(SubtitleMatcher.folderDocumentId("primary:Movies/Ani", video))
        assertEquals("1A2B-3C4D:Videos", SubtitleMatcher.folderDocumentId("1A2B-3C4D:", "/storage/1A2B-3C4D/Videos/a.mp4"))
        assertNull(SubtitleMatcher.folderDocumentId("1A2B-3C4D:", video))
        assertNull(SubtitleMatcher.folderDocumentId("nocolon", video))
    }
}
