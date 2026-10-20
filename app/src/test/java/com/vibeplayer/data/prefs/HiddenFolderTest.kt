package com.vibeplayer.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenFolderTest {
    @Test
    fun roundTripsNamesWithSpacesAndTabs() {
        val f = HiddenFolder("-1739773001", "WhatsApp Video\told")
        assertEquals(f, HiddenFolder.decode(f.encode()))
    }

    @Test
    fun rejectsValuesWithoutABucket() {
        assertNull(HiddenFolder.decode("no separator"))
        assertNull(HiddenFolder.decode("\tname"))
    }
}
