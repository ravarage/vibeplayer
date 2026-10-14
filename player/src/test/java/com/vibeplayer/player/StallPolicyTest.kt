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
        elapsed: Long = StallPolicy.FIRST_CHECK_MS,
        vid: String? = null
    ) = StallPolicy.Facts(released, sameLoad, restart, surface, recovered, elapsed, vid)

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
    fun doesNothingWhenVideoDisabled() {
        assertEquals(StallPolicy.Action.NONE, StallPolicy.onTimer(facts(vid = "no")))
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

    private val uri = "content://media/external/video/media/42"

    @Test
    fun softwareFlagSurvivesReloadsOfTheSameFile() {
        // reconfigure / Vulkan fallback reload the same identity (a content file even with a new fd path)
        assertEquals(uri, StallPolicy.recoveryAfterLoad(uri, uri))
        assertTrue(StallPolicy.softwareAfterStall(StallPolicy.recoveryAfterLoad(uri, uri), uri))
    }

    @Test
    fun anotherFileGetsHardwareAndAFreshBudget() {
        val after = StallPolicy.recoveryAfterLoad(uri, "/sdcard/Movies/b.mkv")
        assertEquals(null, after)
        assertFalse(StallPolicy.softwareAfterStall(after, "/sdcard/Movies/b.mkv"))
        // coming back to the stalled file later tries hardware again (budget reset)
        assertFalse(StallPolicy.softwareAfterStall(StallPolicy.recoveryAfterLoad(after, uri), uri))
    }

    @Test
    fun noRecoveryMeansHardware() {
        assertEquals(null, StallPolicy.recoveryAfterLoad(null, uri))
        assertFalse(StallPolicy.softwareAfterStall(null, uri))
        assertFalse(StallPolicy.softwareAfterStall(uri, null))
    }
}
