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

    fun filter(videos: List<VideoItem>, query: String): List<VideoItem> = filter(index(videos), query)

    /** Videos with their normalized names, built once per list so typing does not re-normalize every name. */
    class Index internal constructor(val videos: List<VideoItem>, internal val names: List<String>)

    fun index(videos: List<VideoItem>): Index = Index(videos, videos.map { normalize(it.displayName) })

    /**
     * Matches in the index's order, best first: names that start with the query, then names that hold it
     * as one phrase, then the rest (every word somewhere). The order within each group is kept.
     */
    fun filter(index: Index, query: String): List<VideoItem> {
        if (words(query).isEmpty()) return index.videos
        return matchOrder(index.names, query).map { index.videos[it] }
    }

    /** Positions in [normalizedNames] that match [query], in the ranked order of [filter]. */
    fun matchOrder(normalizedNames: List<String>, query: String): List<Int> {
        val w = words(query)
        val phrase = w.joinToString(" ")
        return normalizedNames.indices
            .filter { i -> w.all { normalizedNames[i].contains(it) } }
            .sortedBy { i ->
                val n = normalizedNames[i]
                when {
                    n.startsWith(phrase) -> 0
                    n.contains(phrase) -> 1
                    else -> 2
                }
            }
    }
}
