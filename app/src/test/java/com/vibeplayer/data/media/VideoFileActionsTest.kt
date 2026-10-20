package com.vibeplayer.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFileActionsTest {
    @Test
    fun keepsTheExtensionWhenLeftOut() {
        assertEquals("Episode 1.mkv", VideoFileActions.renamedName("ep01.mkv", "Episode 1"))
        assertEquals("Episode 1.mp4", VideoFileActions.renamedName("ep01.mkv", " Episode 1.mp4 "))
        assertEquals("notes", VideoFileActions.renamedName("clip", "notes"))
    }

    @Test
    fun rejectsNamesThatChangeNothingOrAreNotNames() {
        assertNull(VideoFileActions.renamedName("ep01.mkv", ""))
        assertNull(VideoFileActions.renamedName("ep01.mkv", "   "))
        assertNull(VideoFileActions.renamedName("ep01.mkv", "ep01.mkv"))
        assertNull(VideoFileActions.renamedName("ep01.mkv", "ep01"))
        assertNull(VideoFileActions.renamedName("ep01.mkv", "a/b"))
        assertNull(VideoFileActions.renamedName("ep01.mkv", ".."))
    }

    @Test
    fun historyKeysCoverEveryStoredForm() {
        assertEquals(
            listOf(
                "/storage/emulated/0/Movies/a.mkv:100",
                "/sdcard/Movies/a.mkv:100",
                "/storage/emulated/0/Movies/a.mkv",
                "/sdcard/Movies/a.mkv"
            ),
            VideoFileActions.historyKeys("/sdcard/Movies/a.mkv", 100L)
        )
        assertEquals(listOf("/storage/emulated/0/a.mkv"), VideoFileActions.historyKeys("/storage/emulated/0/a.mkv", 0L))
    }
}
