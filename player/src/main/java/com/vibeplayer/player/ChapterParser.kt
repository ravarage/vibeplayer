package com.vibeplayer.player

/**
 * P4-15: Parses raw chapter list properties from mpv into [Chapter] models.
 * Preserves mpv order (even if unsorted or zero-length entries exist).
 * Empty or blank titles are mapped to "Chapter ${index + 1}".
 */
object ChapterParser {
    fun parse(
        count: Long,
        getTitle: (Int) -> String?,
        getTimeSec: (Int) -> Double?
    ): List<Chapter> {
        if (count <= 0L) return emptyList()
        val total = count.toInt()
        val list = ArrayList<Chapter>(total)
        for (i in 0 until total) {
            val rawTitle = getTitle(i)?.trim()
            val title = if (rawTitle.isNullOrEmpty()) {
                "Chapter ${i + 1}"
            } else {
                rawTitle
            }
            val timeSec = getTimeSec(i) ?: 0.0
            val startMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L)
            list.add(
                Chapter(
                    index = i,
                    title = title,
                    startMs = startMs
                )
            )
        }
        return list
    }
}
