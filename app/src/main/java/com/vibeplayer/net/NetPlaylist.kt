package com.vibeplayer.net

/**
 * P13: what to play before and after a network file (the folder or channel list it was opened from), for
 * next / previous, the notification and Up next. Held in memory while the app runs; local files keep
 * using their folder on disk.
 */
object NetPlaylist {
    /**
     * One playable item: its identity (provider content:// URI or the stream URL) and how to open it, with the
     * subtitle files next to it in the same folder (identity and name).
     */
    data class Item(
        val identity: String,
        val title: String,
        val entry: RemoteEntry,
        val sourceId: Long,
        val subtitles: List<Pair<String, String>> = emptyList()
    )

    fun item(identity: String?): Item? = items.getOrNull(indexOf(identity))

    @Volatile private var items: List<Item> = emptyList()

    fun set(list: List<Item>) {
        items = list
    }

    fun indexOf(identity: String?): Int = if (identity == null) -1 else items.indexOfFirst { it.identity == identity }

    fun neighbour(identity: String?, forward: Boolean): Item? {
        val i = indexOf(identity)
        if (i < 0) return null
        return items.getOrNull(if (forward) i + 1 else i - 1)
    }

    fun contains(identity: String?): Boolean = indexOf(identity) >= 0
}
