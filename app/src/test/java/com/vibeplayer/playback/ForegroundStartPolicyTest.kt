package com.vibeplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartPolicyTest {

    @Test
    fun `startForegroundService intent always enters foreground whatever the benchmark flag says`() {
        // The ANR: started while the flag was false, flag true by the time onStartCommand ran.
        assertEquals(ForegroundNotice.SILENT, ForegroundStartPolicy.onStartCommand(null, benchmarkRun = true, isForeground = false))
        assertEquals(ForegroundNotice.MEDIA, ForegroundStartPolicy.onStartCommand(null, benchmarkRun = false, isForeground = false))
    }

    @Test
    fun `already foreground needs nothing`() {
        for (action in listOf(null, PlaybackService.ACTION_PLAY_PAUSE, PlaybackService.ACTION_STOP)) {
            for (bench in listOf(true, false)) {
                assertEquals(ForegroundNotice.NONE, ForegroundStartPolicy.onStartCommand(action, bench, isForeground = true))
            }
        }
    }

    @Test
    fun `notification buttons are plain starts`() {
        assertEquals(ForegroundNotice.NONE, ForegroundStartPolicy.onStartCommand(PlaybackService.ACTION_STOP, false, false))
        assertEquals(ForegroundNotice.NONE, ForegroundStartPolicy.onStartCommand(PlaybackService.ACTION_DISMISS, false, false))
        assertEquals(ForegroundNotice.MEDIA, ForegroundStartPolicy.onStartCommand(PlaybackService.ACTION_PLAY_PAUSE, false, false))
        assertEquals(ForegroundNotice.NONE, ForegroundStartPolicy.onStartCommand(PlaybackService.ACTION_PLAY_PAUSE, true, false))
    }

    @Test
    fun `benchmark never asks for foreground on its own`() {
        assertEquals(ForegroundNotice.NONE, ForegroundStartPolicy.opportunistic(benchmarkRun = true))
        assertEquals(ForegroundNotice.MEDIA, ForegroundStartPolicy.opportunistic(benchmarkRun = false))
        assertFalse(ForegroundStartPolicy.shouldStartForegroundService(benchmarkRun = true))
        assertTrue(ForegroundStartPolicy.shouldStartForegroundService(benchmarkRun = false))
    }
}
