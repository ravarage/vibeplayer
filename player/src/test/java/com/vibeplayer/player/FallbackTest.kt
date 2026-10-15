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

    @Test
    fun testSoftwareByDesignNeverShowsBadge() {
        // P6-07: H.264 10-bit is decoded in software on purpose (hwdecOverride = "no").
        val result = PlayerEngine.computeFallback(
            hwdec = "no",
            codec = "h264",
            durationMs = 30_000L,
            hasFilePath = true,
            playbackRestartMs = 1000L,
            nowMs = 5000L,
            alreadyFallback = true,
            softwareByDesign = true
        )
        assertFalse("Software decoding chosen by the format table is not a fallback", result)
    }
    // Bug "SW FALLBACK badge after seek": hwdec-current reads "no" while a swipe seek reinits the decoder.
    @Test
    fun testSeekTransientNoDoesNotShowBadge() {
        // Playback restarted long ago, a seek is in progress and hwdec-current just turned "no".
        assertFalse(
            "A 'no' while the seek is pending must not show the badge",
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "h264", durationMs = 300_000L,
                playbackRestartMs = 1_000L, nowMs = 60_000L,
                hwdecNoSinceMs = 59_990L, seekPending = true
            )
        )
        // The seek's playback-restart came, but "no" has not lasted the grace period yet.
        assertFalse(
            "A 'no' shortly after the seek's restart must not show the badge",
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "h264", durationMs = 300_000L,
                playbackRestartMs = 60_200L, nowMs = 60_500L,
                hwdecNoSinceMs = 59_990L, seekPending = false
            )
        )
        // A short "no" without a SEEK event (decoder reinit) is ignored too, even long after the restart.
        assertFalse(
            "A 'no' reported less than the grace period ago must not show the badge",
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "hevc", durationMs = 300_000L,
                playbackRestartMs = 1_000L, nowMs = 60_000L,
                hwdecNoSinceMs = 59_500L
            )
        )
    }

    @Test
    fun testRealFallbackAfterSeekShowsBadge() {
        // hwdec-current stayed "no" past the grace period after the restart: a real software fallback.
        assertTrue(
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "h264", durationMs = 300_000L,
                playbackRestartMs = 60_200L, nowMs = 61_800L,
                hwdecNoSinceMs = 59_990L, seekPending = false
            )
        )
        // Real fallback from the start of the file ("no" since before the first restart).
        assertTrue(
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "vp9", durationMs = 300_000L,
                playbackRestartMs = 2_000L, nowMs = 3_600L,
                hwdecNoSinceMs = 500L
            )
        )
    }

    @Test
    fun testSoftwareByConfigNeverShowsBadgeAfterSeek() {
        assertFalse(
            PlayerEngine.computeFallback(
                hwdec = "no", codec = "h264", durationMs = 300_000L,
                playbackRestartMs = 1_000L, nowMs = 60_000L,
                hwdecNoSinceMs = 500L, softwareByDesign = true
            )
        )
    }
}
