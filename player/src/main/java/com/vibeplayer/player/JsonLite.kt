package com.vibeplayer.player

/**
 * Minimal JSON reader for small bundled data files (format_table.json). Pure Kotlin so the table code
 * runs in JVM unit tests, where Android's org.json is only a stub.
 *
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Double, plus String, Boolean and null.
 */
object JsonLite {

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (p.pos != text.length) throw ParseException("trailing data at ${p.pos}")
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (pos >= s.length) throw ParseException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("expected key at $pos")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw ParseException("expected , or } at ${pos - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw ParseException("expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw ParseException("bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw ParseException("bad number at $start")
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else throw ParseException("unexpected end")
        private fun next(): Char = peek().also { pos++ }
        private fun expect(c: Char) {
            if (next() != c) throw ParseException("expected '$c' at ${pos - 1}")
        }
    }
}
