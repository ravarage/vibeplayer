package com.vibeplayer.ui.browser

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingFileActionTest {
    private val scope = SaverScope { true }

    private fun roundTrip(p: PendingFileAction?): PendingFileAction? = with(PendingFileAction.Saver) {
        restore(scope.save(p)!!)
    }

    @Test
    fun renameAndDeleteSurviveRecreation() {
        val rename = PendingFileAction("content://media/external/video/media/7", "/sdcard/a b.mkv", 100L, "a b.mkv", "Episode 1.mkv")
        assertEquals(rename, roundTrip(rename))
        val delete = rename.copy(newName = null)
        assertEquals(delete, roundTrip(delete))
        assertNull(roundTrip(null))
    }
}
