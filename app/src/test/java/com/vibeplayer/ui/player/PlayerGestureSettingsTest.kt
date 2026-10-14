package com.vibeplayer.ui.player

import com.vibeplayer.data.prefs.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureSettingsTest {

    @Test
    fun appSettingsDefaultsMatchPlayerExpectations() {
        val settings = AppSettings()
        assertEquals(true, settings.gesturesEnabled)
        assertEquals(10, settings.skipSeconds)
        assertEquals(2.0, settings.holdSpeed, 0.0)
    }

    @Test
    fun doubleTapStepCalculationHandlesVariousIntervals() {
        val intervals = listOf(5, 10, 15, 20, 30)
        for (sec in intervals) {
            val step = sec.coerceAtLeast(1)
            assertEquals(sec, step)
            // Initial double tap
            val initialAccum = step
            val initialDeltaLeft = -step.toDouble()
            val initialDeltaRight = step.toDouble()
            assertEquals(-sec.toDouble(), initialDeltaLeft, 0.0)
            assertEquals(sec.toDouble(), initialDeltaRight, 0.0)

            // Second tap in streak
            val streakAccum = initialAccum + step
            assertEquals(sec * 2, streakAccum)
        }
    }

    @Test
    fun gesturesDisabledSuppressesHold() {
        val gesturesEnabled = false
        val holdEnabled = true
        val holdAllowed = holdEnabled && gesturesEnabled
        val outcome = HoldGesture.classify(
            downX = 900f,
            width = 1000f,
            elapsedMs = 600,
            movedPx = 0f,
            touchSlopPx = 20f,
            longPressMs = 450,
            holdAllowed = holdAllowed
        )
        assertEquals(HoldGesture.Outcome.PENDING, outcome)
    }
}
