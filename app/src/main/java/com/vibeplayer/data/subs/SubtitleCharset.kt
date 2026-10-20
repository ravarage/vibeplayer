package com.vibeplayer.data.subs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * External text subtitles for mpv, which is built without iconv and so reads them as UTF-8 only:
 * UTF-8 stays as it is, a UTF-16 file (with a BOM) or a file in a legacy code page is converted.
 */
object SubtitleCharset {

    /** The code page used when a file is not UTF-8: Windows code pages cover most old SRT files. */
    val CHOICES = listOf(
        "windows-1252" to "Western (Windows-1252)",
        "windows-1256" to "Arabic (Windows-1256)",
        "windows-1251" to "Cyrillic (Windows-1251)",
        "windows-1250" to "Central European (Windows-1250)",
        "windows-1253" to "Greek (Windows-1253)",
        "windows-1254" to "Turkish (Windows-1254)",
        "windows-1255" to "Hebrew (Windows-1255)",
        "GB18030" to "Chinese Simplified (GB18030)",
        "Big5" to "Chinese Traditional (Big5)",
        "Shift_JIS" to "Japanese (Shift_JIS)",
        "EUC-KR" to "Korean (EUC-KR)"
    )
    const val DEFAULT = "windows-1252"

    fun normalize(name: String?): String = CHOICES.firstOrNull { it.first == name }?.first ?: DEFAULT

    fun label(name: String): String = CHOICES.firstOrNull { it.first == name }?.second ?: name

    /** UTF-8 bytes for mpv, or null when [bytes] already are UTF-8 (with or without a BOM). */
    fun toUtf8OrNull(bytes: ByteArray, fallback: String): ByteArray? {
        val text = when {
            bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            isUtf8(bytes) -> return null
            else -> String(bytes, Charset.forName(normalize(fallback)))
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun isUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun ByteArray.startsWith(b0: Int, b1: Int) =
        size >= 2 && this[0] == b0.toByte() && this[1] == b1.toByte()
}
