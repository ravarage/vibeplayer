package com.vibeplayer.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationModeTest {

    @Test
    fun cyclesThroughAllModes() {
        assertEquals(RotationMode.LANDSCAPE, RotationMode.AUTO.next())
        assertEquals(RotationMode.PORTRAIT, RotationMode.LANDSCAPE.next())
        assertEquals(RotationMode.AUTO, RotationMode.PORTRAIT.next())
    }

    @Test
    fun autoFollowsTheVideoAndFixedModesIgnoreIt() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, RotationMode.AUTO.orientationFor(1920, 1080))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, RotationMode.AUTO.orientationFor(1080, 1920))
        assertNull(RotationMode.AUTO.orientationFor(0, 0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, RotationMode.PORTRAIT.orientationFor(1920, 1080))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, RotationMode.LANDSCAPE.orientationFor(0, 0))
    }
}
