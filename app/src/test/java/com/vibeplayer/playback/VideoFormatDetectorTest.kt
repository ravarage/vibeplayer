package com.vibeplayer.playback

import com.vibeplayer.player.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFormatDetectorTest {

    @Test
    fun `parseFormat detects h264 8-bit correctly`() {
        assertEquals(VideoFormat.H264_8BIT, VideoFormatDetector.parseFormat("video/avc"))
        assertEquals(VideoFormat.H264_8BIT, VideoFormatDetector.parseFormat("video/avc", profile = 1, bitDepth = 8))
        assertEquals(VideoFormat.H264_8BIT, VideoFormatDetector.parseFormat("video/avc", profile = 2, bitDepth = 8))
        assertEquals(VideoFormat.H264_8BIT, VideoFormatDetector.parseFormat("video/avc", profile = 8, bitDepth = 8)) // High profile 8-bit
        assertEquals(VideoFormat.H264_8BIT, VideoFormatDetector.parseFormat("video/h264"))
    }

    @Test
    fun `parseFormat detects h264 10-bit Hi10P correctly`() {
        // High 10 profile = 16, High 4:2:2 = 32, High 4:4:4 = 64
        assertEquals(VideoFormat.H264_10BIT, VideoFormatDetector.parseFormat("video/avc", profile = 16))
        assertEquals(VideoFormat.H264_10BIT, VideoFormatDetector.parseFormat("video/avc", profile = 32))
        assertEquals(VideoFormat.H264_10BIT, VideoFormatDetector.parseFormat("video/avc", profile = 64))
        assertEquals(VideoFormat.H264_10BIT, VideoFormatDetector.parseFormat("video/avc", bitDepth = 10))
        assertEquals(VideoFormat.H264_10BIT, VideoFormatDetector.parseFormat("video/h264", bitDepth = 10))
    }

    @Test
    fun `parseFormat detects hevc correctly`() {
        assertEquals(VideoFormat.HEVC, VideoFormatDetector.parseFormat("video/hevc"))
        assertEquals(VideoFormat.HEVC, VideoFormatDetector.parseFormat("video/hevc", profile = 1, bitDepth = 8))
        assertEquals(VideoFormat.HEVC, VideoFormatDetector.parseFormat("video/hevc", profile = 2, bitDepth = 10))
        assertEquals(VideoFormat.HEVC, VideoFormatDetector.parseFormat("video/h265"))
    }

    @Test
    fun `parseFormat detects vp9 correctly`() {
        assertEquals(VideoFormat.VP9, VideoFormatDetector.parseFormat("video/x-vnd.on2.vp9"))
        assertEquals(VideoFormat.VP9, VideoFormatDetector.parseFormat("video/vp9"))
    }

    @Test
    fun `parseFormat detects av1 correctly`() {
        assertEquals(VideoFormat.AV1, VideoFormatDetector.parseFormat("video/av01"))
        assertEquals(VideoFormat.AV1, VideoFormatDetector.parseFormat("video/av1"))
        assertEquals(VideoFormat.AV1, VideoFormatDetector.parseFormat("video/av01", bitDepth = 10))
    }

    @Test
    fun `parseFormat falls back to other for unknown formats and audio`() {
        assertEquals(VideoFormat.OTHER, VideoFormatDetector.parseFormat("video/mp4v-es"))
        assertEquals(VideoFormat.OTHER, VideoFormatDetector.parseFormat("video/mpeg2"))
        assertEquals(VideoFormat.OTHER, VideoFormatDetector.parseFormat("audio/mp4a-latm"))
        assertEquals(VideoFormat.OTHER, VideoFormatDetector.parseFormat(null))
        assertEquals(VideoFormat.OTHER, VideoFormatDetector.parseFormat(""))
    }
}
