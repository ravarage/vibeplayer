package com.vibeplayer.player

/** P4-15: one entry of mpv's `chapter-list`; [index] is mpv's own index (the value of the `chapter` property). */
data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long
)

object Chapters {

    /**
     * Builds the chapter list from mpv's `chapter-list/N/title` and `chapter-list/N/time` readers.
     * Entries stay in mpv order (unsorted and zero-length ones included) so [Chapter.index] always
     * matches mpv; an entry without a time is skipped, a missing or blank title becomes "".
     */
    fun build(count: Long, titleAt: (Int) -> String?, timeSecondsAt: (Int) -> Double?): List<Chapter> {
        if (count <= 0L) return emptyList()
        val out = ArrayList<Chapter>(count.toInt())
        for (i in 0 until count.toInt()) {
            val sec = timeSecondsAt(i)?.takeIf { !it.isNaN() } ?: continue
            out.add(Chapter(index = i, title = titleAt(i)?.trim().orEmpty(), startMs = (sec * 1000.0).toLong().coerceAtLeast(0L)))
        }
        return out
    }

    /** Title to show: the chapter's own, or "Chapter N" (1-based) when the file has none. */
    fun displayTitle(chapter: Chapter): String = chapter.title.ifEmpty { "Chapter ${chapter.index + 1}" }

    /** The chapter playing at [positionMs]: the one with the latest start at or before it, or null before the first. */
    fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? {
        var best: Chapter? = null
        for (c in chapters) {
            if (c.startMs <= positionMs && (best == null || c.startMs >= best.startMs)) best = c
        }
        return best
    }

    /** Seek-bar tick positions (0..1): every chapter start except one at 0, needs a known duration. */
    fun tickFractions(chapters: List<Chapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0L) return emptyList()
        return chapters
            .filter { it.startMs in 1 until durationMs }
            .map { it.startMs.toFloat() / durationMs.toFloat() }
            .distinct()
    }
}
