package com.vibeplayer.data.subs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class SubtitleCharsetTest {

    private val line = "1\n00:00:01,000 --> 00:00:02,000\n"

    @Test
    fun utf8IsLeftAlone() {
        assertNull(SubtitleCharset.toUtf8OrNull("${line}Café ñ 日本 سلام".toByteArray(Charsets.UTF_8), "windows-1252"))
        assertNull(SubtitleCharset.toUtf8OrNull(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a".toByteArray(), "windows-1252"))
        assertNull(SubtitleCharset.toUtf8OrNull(ByteArray(0), "windows-1252"))
    }

    @Test
    fun legacyCodePagesAreConverted() {
        val western = "${line}Café déjà vu"
        assertArrayEquals(western.toByteArray(Charsets.UTF_8),
            SubtitleCharset.toUtf8OrNull(western.toByteArray(Charset.forName("windows-1252")), "windows-1252"))
        val arabic = "${line}مرحبا بالعالم"
        assertArrayEquals(arabic.toByteArray(Charsets.UTF_8),
            SubtitleCharset.toUtf8OrNull(arabic.toByteArray(Charset.forName("windows-1256")), "windows-1256"))
    }

    @Test
    fun utf16WithBomIsConverted() {
        val text = "${line}Hello ü"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(le, "windows-1252"))
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(be, "windows-1252"))
    }

    @Test
    fun unknownChoiceFallsBackToTheDefault() {
        assertEquals("windows-1252", SubtitleCharset.normalize("nope"))
        assertEquals("windows-1256", SubtitleCharset.normalize("windows-1256"))
        assertEquals("windows-1252", SubtitleCharset.normalize(null))
    }

    // User, 2026-10-23: Kurdish (Sorani) and Japanese subtitles must never be read in a code page.
    private val kurdish = "${line}ئەمە تاقیکردنەوەیەکە بۆ ژێرنووس، سڵاو لە هەمووان"
    private val japanese = "${line}これは字幕のテストです。こんにちは、世界！"

    @Test
    fun kurdishAndJapaneseUtf8AreLeftAloneWhateverTheSetting() {
        for (cp in listOf("windows-1252", "windows-1256", "Shift_JIS")) {
            assertNull(SubtitleCharset.toUtf8OrNull(kurdish.toByteArray(Charsets.UTF_8), cp))
            assertNull(SubtitleCharset.toUtf8OrNull(japanese.toByteArray(Charsets.UTF_8), cp))
        }
    }

    @Test
    fun utf16WithoutBomIsConverted() {
        for (text in listOf(kurdish, japanese, "${line}Hello")) {
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(text.toByteArray(Charsets.UTF_16LE), "windows-1256"))
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(text.toByteArray(Charsets.UTF_16BE), "windows-1256"))
        }
    }

    @Test
    fun utf32WithBomIsConverted() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + japanese.toByteArray(Charset.forName("UTF-32LE"))
        assertArrayEquals(japanese.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(le, "windows-1252"))
    }

    @Test
    fun aFewBrokenBytesKeepTheFileUtf8() {
        val bytes = kurdish.toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) + japanese.toByteArray(Charsets.UTF_8)
        val out = String(SubtitleCharset.toUtf8OrNull(bytes, "windows-1256")!!, Charsets.UTF_8)
        assertEquals(kurdish + "\uFFFD" + japanese, out)
    }

    @Test
    fun legacyCodePageTextIsNotMistakenForUtf8() {
        val arabic = "${line}مرحبا بالعالم هذا اختبار للترميز"
        assertEquals(false, SubtitleCharset.isMostlyUtf8(arabic.toByteArray(Charset.forName("windows-1256"))))
        val sjis = japanese.toByteArray(Charset.forName("Shift_JIS"))
        assertArrayEquals(japanese.toByteArray(Charsets.UTF_8), SubtitleCharset.toUtf8OrNull(sjis, "Shift_JIS"))
    }
}
