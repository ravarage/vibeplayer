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

    fun filter(index: Index, query: String): List<VideoItem> {
        val w = words(query)
        if (w.isEmpty()) return index.videos
        return index.videos.filterIndexed { i, _ -> w.all { index.names[i].contains(it) } }
    }
}
