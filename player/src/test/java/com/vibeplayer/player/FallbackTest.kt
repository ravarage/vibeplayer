package com.vibeplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackTest {

    @Test
    fun testTransientNoIgnoredDuringGracePeriodAfterRestart() {
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 1500L, // 500 ms after restart (< 1000 ms grace period)
            minTimeSinceRestartMs = 1000L,
            alreadyFallback = false
        )
        assertFalse("Transient 'no' within grace period must not trigger fallback", result)
    }

    @Test
    fun testNoPersistingPastGracePeriodTriggersFallback() {
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 2100L, // 1100 ms after restart (>= 1000 ms grace period)
            minTimeSinceRestartMs = 1000L,
            alreadyFallback = false
        )
        assertTrue("Persistent 'no' after grace period must trigger fallback", result)
    }

    @Test
    fun testPlaybackNotYetRestartedReturnsFalse() {
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 0L, // initial load, codec not yet open
            nowMs = 5000L,
            alreadyFallback = false
        )
        assertFalse("Before playback has restarted, initial 'no' must be ignored", result)
    }

    @Test
    fun testHardwareDecodingActiveReturnsFalse() {
        assertFalse(
            PlayerEngine.computeFallback(
                hwdec = "mediacodec",
                codec = "h264",
                durationMs = 30_000L,
                hasFilePath = true,
                playbackRestartMs = 1000L,
                nowMs = 5000L
            )
        )
        assertFalse(
            PlayerEngine.computeFallback(
                hwdec = "mediacodec-copy",
                codec = "hevc",
                durationMs = 30_000L,
                hasFilePath = true,
                playbackRestartMs = 1000L,
                nowMs = 5000L
            )
        )
    }

    @Test
    fun testNonHwdecCodecReturnsFalse() {
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "av1",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 5000L
        )
        assertFalse("AV1 is software by design and must never trigger fallback badge", result)
    }

    @Test
    fun testMissingFileOrDurationReturnsFalse() {
        assertFalse(PlayerEngine.computeFallback(hwdec = "no", codec = "h264", durationMs = 0L, hasFilePath = true, playbackRestartMs = 1000L, nowMs = 5000L))
        assertFalse(PlayerEngine.computeFallback(hwdec = "no", codec = "h264", durationMs = 30_000L, hasFilePath = false, playbackRestartMs = 1000L, nowMs = 5000L))
        assertFalse(PlayerEngine.computeFallback(hwdec = "no", codec = null, durationMs = 30_000L, hasFilePath = true, playbackRestartMs = 1000L, nowMs = 5000L))
    }

    @Test
    fun testAllHwdecCodecsSupported() {
        for (codec in listOf("h264", "H264", "hevc", "HEVC", "vp9", "VP9")) {
            assertTrue(
                "Codec $codec must report fallback when 'no' persists",
                PlayerEngine.computeFallback(
                    hwdec = "no",
                    codec = codec,
                    durationMs = 30_000L,
                    hasFilePath = true,
                    playbackRestartMs = 1000L,
                    nowMs = 2500L
                )
            )
        }
    }

    @Test
    fun testAlreadyFallbackPreservedAcrossSeek() {
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 1200L, // within grace period of seek
            minTimeSinceRestartMs = 1000L,
            alreadyFallback = true
        )
        assertTrue("Already confirmed fallback stays true during seek", result)
    }

    @Test
    fun testHardwareRecoveryClearsFallback() {
        val result = PlayerEngine.computeFallback(
            hwdec = "mediacodec",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 5000L,
            alreadyFallback = true
        )
        assertFalse("Hardware codec recovery must clear fallback badge", result)
    }
}
