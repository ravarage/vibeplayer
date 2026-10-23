package com.vibeplayer.data.subs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * External text subtitles for mpv, which is built without iconv and so reads them as UTF-8 only:
 * UTF-8 stays as it is, UTF-16/32 (with a BOM, or UTF-16 without one) or a file in a legacy code page is
 * converted. Unicode comes first (user, 2026-10-23: Kurdish and Japanese subtitles must not break): the code
 * page setting is only used for a file that is clearly not Unicode.
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
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32LE"))
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32BE"))
            bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // Without a BOM, UTF-16 text is full of zero bytes, which are valid UTF-8 and would pass the check below.
            else -> when (utf16WithoutBom(bytes)) {
                Charsets.UTF_16LE -> String(bytes, Charsets.UTF_16LE)
                Charsets.UTF_16BE -> String(bytes, Charsets.UTF_16BE)
                else -> when {
                    isUtf8(bytes) -> return null
                    // A few broken bytes in an otherwise UTF-8 file: keep it UTF-8 (the bad bytes become U+FFFD)
                    // instead of reading the whole file in a code page.
                    isMostlyUtf8(bytes) -> String(bytes, Charsets.UTF_8)
                    else -> String(bytes, Charset.forName(normalize(fallback)))
                }
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** UTF-16LE/BE when zero bytes sit mostly at odd/even positions (ASCII-range text), else null. */
    internal fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096) and 1.inv()
        if (n < 4) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) if (bytes[i] == 0.toByte()) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
        val pairs = n / 2
        return when {
            oddZeros > pairs / 4 && evenZeros < oddZeros / 8 -> Charsets.UTF_16LE
            evenZeros > pairs / 4 && oddZeros < evenZeros / 8 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /**
     * Valid multi-byte UTF-8 characters outnumber malformed sequences ten to one. Legacy code page text almost
     * never forms valid multi-byte sequences, so it stays below this.
     */
    internal fun isMostlyUtf8(bytes: ByteArray): Boolean {
        var good = 0
        var bad = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (len == 1) { i++; continue }
            val ok = len > 0 && i + len <= bytes.size &&
                (1 until len).all { (bytes[i + it].toInt() and 0xC0) == 0x80 }
            if (ok) { good++; i += len } else { bad++; i++ }
        }
        return good > 0 && good >= bad * 10
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

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
