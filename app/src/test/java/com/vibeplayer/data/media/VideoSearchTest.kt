package com.vibeplayer.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSearchTest {

    private fun m(name: String, q: String) = VideoSearch.matches(name, VideoSearch.words(q))

    @Test
    fun allWordsInAnyOrderIgnoringCaseAndPunctuation() {
        val name = "[denisplay] Bocchi the Rock! - S01E01.mkv"
        assertTrue(m(name, "bocchi"))
        assertTrue(m(name, "rock bocchi"))
        assertTrue(m(name, "BOCCHI s01e01"))
        assertTrue(m(name, "bocchi 01"))
        assertTrue(m(name, "the-rock"))
        assertFalse(m(name, "bocchi s02"))
    }

    @Test
    fun accentsAndSeparatorsAreIgnored() {
        assertTrue(m("Amélie_2001.mp4", "amelie 2001"))
        assertTrue(m("Case.Closed.001.mkv", "case closed 001"))
    }

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(m("anything.mp4", ""))
        assertTrue(m("anything.mp4", "  - "))
        assertEquals(emptyList<String>(), VideoSearch.words(" .. "))
    }
}
