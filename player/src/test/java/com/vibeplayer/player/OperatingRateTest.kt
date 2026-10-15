package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P6-08 fix: MediaCodec operating-rate hint. */
class OperatingRateTest {

    private val auto = OperatingRatePolicy.AUTO
    private fun hevc(fps: Double?, w: Int? = 3840, h: Int? = 2160, tenBit: Boolean = true) =
        ClipInfo(VideoFormat.HEVC, fps, w, h, tenBit)
    private fun h264(fps: Double?, w: Int? = 1920, h: Int? = 1080) = ClipInfo(VideoFormat.H264_8BIT, fps, w, h)

    @Test
    fun `known fps gives twice the rate clamped to 60-240`() {
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(60.0)))
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(59.94)))
        assertEquals(60, OperatingRate.hint(auto, "mediacodec-copy", h264(29.97)))
        assertEquals(60, OperatingRate.hint(auto, "mediacodec-copy", h264(30.0)))
        assertEquals(60, OperatingRate.hint(auto, "mediacodec-copy", h264(23.976)))
        assertEquals(60, OperatingRate.hint(auto, "mediacodec", h264(10.0)))
        assertEquals(100, OperatingRate.hint(auto, "mediacodec", h264(50.0)))
        assertEquals(240, OperatingRate.hint(auto, "mediacodec", h264(120.0)))
        assertEquals(240, OperatingRate.hint(auto, "mediacodec", h264(144.0)))
    }

    @Test
    fun `unknown fps gives 120 only for 4K or 10-bit HEVC`() {
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(null)))
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(null, 1920, 1080, tenBit = true)))
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(null, 2160, 3840, tenBit = false))) // portrait 4K
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", ClipInfo(VideoFormat.VP9, null, 3840, 2160)))
        assertNull(OperatingRate.hint(auto, "mediacodec", hevc(null, 1920, 1080, tenBit = false)))
        assertNull(OperatingRate.hint(auto, "mediacodec", h264(null)))
        assertNull(OperatingRate.hint(auto, "mediacodec", ClipInfo(VideoFormat.VP9, null, 1920, 1080, tenBit = true)))
        assertNull(OperatingRate.hint(auto, "mediacodec", ClipInfo(VideoFormat.H264_8BIT)))
    }

    @Test
    fun `implausible container rates count as unknown`() {
        assertNull(OperatingRate.sanitizeFps(0.0))
        assertNull(OperatingRate.sanitizeFps(-1.0))
        assertNull(OperatingRate.sanitizeFps(1000.0))
        assertNull(OperatingRate.sanitizeFps(90000.0))
        assertNull(OperatingRate.sanitizeFps(Double.NaN))
        assertNull(OperatingRate.sanitizeFps(null))
        assertEquals(25.0, OperatingRate.sanitizeFps(25.0)!!, 0.0)
        assertNull(OperatingRate.hint(auto, "mediacodec", h264(1000.0)))
        assertEquals(120, OperatingRate.hint(auto, "mediacodec", hevc(90000.0)))
    }

    @Test
    fun `no hint for software decoding, policy off or no clip`() {
        assertNull(OperatingRate.hint(auto, "no", hevc(60.0)))
        assertNull(OperatingRate.hint(auto, null, hevc(60.0)))
        assertNull(OperatingRate.hint(auto, "auto", hevc(60.0)))
        assertNull(OperatingRate.hint(OperatingRatePolicy.OFF, "mediacodec", hevc(60.0)))
        assertNull(OperatingRate.hint(auto, "mediacodec", null))
    }

    @Test
    fun `load option and debug override`() {
        assertEquals("vd-lavc-o=operating_rate=120", OperatingRate.loadOption(120))
        assertNull(OperatingRate.loadOption(null))
        assertNull(OperatingRate.loadOption(0))
        assertNull(OperatingRate.loadOption(120, mapOf("vd-lavc-o" to "operating_rate=0")))
        assertEquals("vd-lavc-o=operating_rate=60", OperatingRate.loadOption(60, mapOf("vd-queue-enable" to "yes")))
    }

    @Test
    fun `policy tokens`() {
        assertEquals(OperatingRatePolicy.AUTO, OperatingRatePolicy.fromToken("auto"))
        assertEquals(OperatingRatePolicy.OFF, OperatingRatePolicy.fromToken(" OFF "))
        assertEquals(OperatingRatePolicy.AUTO, OperatingRatePolicy.fromToken(null))
        assertEquals(OperatingRatePolicy.AUTO, OperatingRatePolicy.fromToken("bogus"))
    }
}
