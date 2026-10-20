package com.vibeplayer.data.media

import java.text.Normalizer

/** File-name search for the browser: every word of the query must appear, ignoring case, accents and punctuation. */
object VideoSearch {

    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(SEPARATORS, " ").trim()

    fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    fun matches(name: String, queryWords: List<String>): Boolean {
        if (queryWords.isEmpty()) return true
        val n = normalize(name)
        return queryWords.all { n.contains(it) }
    }

    fun filter(videos: List<VideoItem>, query: String): List<VideoItem> {
        val w = words(query)
        return if (w.isEmpty()) videos else videos.filter { matches(it.displayName, w) }
    }
}
