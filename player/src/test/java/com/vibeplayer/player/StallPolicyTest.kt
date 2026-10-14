package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallPolicyTest {
    private fun facts(
        released: Boolean = false,
        sameLoad: Boolean = true,
        restart: Boolean = false,
        surface: Boolean = true,
        recovered: Boolean = false,
        elapsed: Long = StallPolicy.FIRST_CHECK_MS
    ) = StallPolicy.Facts(released, sameLoad, restart, surface, recovered, elapsed)

    @Test
    fun probesWhenPlaybackHasNotStartedInTime() {
        assertEquals(StallPolicy.Action.PROBE, StallPolicy.onTimer(facts()))
    }

    @Test
    fun doesNothingBeforeTheDeadline() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(elapsed = StallPolicy.FIRST_CHECK_MS - 1)))
    }

    @Test
    fun doesNothingOncePlaybackStarted() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(restart = true)))
    }

    @Test
    fun doesNothingForAnotherLoadOrAReleasedEngine() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(sameLoad = false)))
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(released = true)))
    }

    @Test
    fun doesNothingWithoutASurface() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(surface = false)))
    }

    @Test
    fun aFileIsRecoveredOnlyOnce() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(recovered = true)))
    }

    @Test
    fun givesUpOnAVerySlowButLiveLoad() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(elapsed = StallPolicy.GIVE_UP_MS + 1)))
    }

    @Test
    fun recoversOnlyWhenTheProbeGotNoAnswer() {
        assertTrue(StallPolicy.shouldRecover(probeAnswered = false))
        assertFalse(StallPolicy.shouldRecover(probeAnswered = true))
    }
}
