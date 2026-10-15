package com.vibeplayer.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCommandsTest {

    @Test
    fun openDocumentMimeTypesContainWildcardAndPreferredTypes() {
        val types = SubtitleCommands.OPEN_DOCUMENT_MIME_TYPES
        assertTrue(types.contains("*/*"))
        assertTrue(types.contains("text/*"))
        assertTrue(types.contains("application/x-subrip"))
        assertTrue(types.contains("text/x-ass"))
        assertTrue(types.contains("text/x-ssa"))
        assertTrue(types.contains("text/vtt"))
        assertTrue(types.contains("application/octet-stream"))
    }

    @Test
    fun buildSubAddArgsWithTitleAndSelect() {
        val args = SubtitleCommands.buildSubAddArgs("fdclose://42", "English.srt", select = true)
        assertArrayEquals(arrayOf("sub-add", "fdclose://42", "select", "English.srt"), args)
    }

    @Test
    fun buildSubAddArgsWithTitleNotSelectedUsesAuto() {
        val args = SubtitleCommands.buildSubAddArgs("fdclose://42", "English.srt", select = false)
        assertArrayEquals(arrayOf("sub-add", "fdclose://42", "auto", "English.srt"), args)
    }

    @Test
    fun notSelectedNeverUsesCachedFlagWhichSelects() {
        // mpv input.rst: "cached" selects the subtitle; only "auto" adds it without selecting.
        val args = SubtitleCommands.buildSubAddArgs("fdclose://7", "x.srt", select = false)
        assertTrue("cached" !in args && "select" !in args)
    }

    @Test
    fun buildSubAddArgsWithoutTitle() {
        val args = SubtitleCommands.buildSubAddArgs("fdclose://42", null, select = true)
        assertArrayEquals(arrayOf("sub-add", "fdclose://42", "select"), args)

        val argsEmpty = SubtitleCommands.buildSubAddArgs("fdclose://42", "", select = false)
        assertArrayEquals(arrayOf("sub-add", "fdclose://42", "auto"), argsEmpty)
    }
}
