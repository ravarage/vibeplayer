package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureAdjustTest {

    @Test
    fun defaultIsNeutral() {
        assertTrue(PictureAdjust().isDefault)
        assertFalse(PictureAdjust(contrast = 5).isDefault)
        assertEquals(
            listOf("brightness" to "0", "contrast" to "0", "saturation" to "0", "gamma" to "0"),
            PictureAdjust().mpvProperties()
        )
    }

    @Test
    fun valuesAreClampedToMpvRange() {
        val p = PictureAdjust(brightness = 150, contrast = -300, saturation = 20, gamma = -100)
        assertEquals(PictureAdjust(100, -100, 20, -100), p.normalized())
        assertEquals("100", p.mpvProperties().first { it.first == "brightness" }.second)
    }
}
