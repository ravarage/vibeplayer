package com.vibeplayer.ui.player

import com.vibeplayer.ui.player.HoldGesture.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldGestureTest {
    private fun c(x: Float = 900f, t: Long = 500, moved: Float = 0f, allowed: Boolean = true) =
        HoldGesture.classify(x, 1000f, t, moved, 20f, 450, allowed)

    @Test fun rightHalfLongPressHolds() = assertEquals(Outcome.HOLD, c())
    @Test fun beforeTimeoutPending() = assertEquals(Outcome.PENDING, c(t = 200))
    @Test fun leftHalfNeverHolds() = assertEquals(Outcome.PENDING, c(x = 100f))
    @Test fun movedBeforeTimeoutIsDrag() = assertEquals(Outcome.DRAG, c(t = 200, moved = 30f))
    @Test fun movedAtTimeoutIsDragNotHold() = assertEquals(Outcome.DRAG, c(moved = 21f))
    @Test fun withinSlopStillHolds() = assertEquals(Outcome.HOLD, c(moved = 20f))
    @Test fun notAllowedNeverHolds() = assertEquals(Outcome.PENDING, c(allowed = false))

    @Test fun releaseRestores() {
        val calls = mutableListOf<Double?>()
        val h = HoldSpeedController { calls += it }
        h.start(2.0)
        h.start(3.0) // ignored while active
        h.end()
        h.end() // idempotent
        assertEquals(listOf(2.0, null), calls)
        assertNull(h.active)
    }

    @Test fun normalizeAndLabel() {
        assertEquals(2.0, HoldGesture.normalize(2.5), 0.0)
        assertEquals(3.0, HoldGesture.normalize(3.0), 0.0)
        assertEquals("2x", HoldGesture.label(2.0))
        assertEquals("1.5x", HoldGesture.label(1.5))
    }
}
