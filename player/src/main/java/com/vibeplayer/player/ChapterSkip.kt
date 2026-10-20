package com.vibeplayer.player

/** What a chapter is, from its title, for the skip button and auto-skip. */
enum class ChapterKind(val skipLabel: String?) {
    OPENING("Skip opening"),
    ENDING("Skip ending"),
    OTHER(null)
}

object ChapterSkip {

    private val OPENING_WORDS = setOf("op", "opening", "intro", "introduction")
    private val ENDING_WORDS = setOf("ed", "ending", "outro", "credits")
    private val NUMBERED_OP = Regex("op\\d{1,2}")
    private val NUMBERED_ED = Regex("ed\\d{1,2}")

    /**
     * Classifies by whole words of the title, so "Opening", "OP", "OP2", "Intro" or "Ending Credits"
     * match and "Operation" or "Edward" do not. Opening wins when a title has both.
     */
    fun kindOf(title: String): ChapterKind {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.any { it in OPENING_WORDS || NUMBERED_OP.matches(it) }) return ChapterKind.OPENING
        if (words.any { it in ENDING_WORDS || NUMBERED_ED.matches(it) }) return ChapterKind.ENDING
        return ChapterKind.OTHER
    }

    /**
     * Where skipping [chapter] lands: the start of the first chapter that begins after it, or null
     * when it is the last one (skip to the end of the file).
     */
    fun skipTargetMs(chapters: List<Chapter>, chapter: Chapter): Long? =
        chapters.filter { it.startMs > chapter.startMs }.minOfOrNull { it.startMs }

    /** The current chapter when it can be skipped, else null. */
    fun skippableCurrent(chapters: List<Chapter>, currentIndex: Int): Chapter? {
        val c = chapters.firstOrNull { it.index == currentIndex } ?: return null
        return if (kindOf(c.title) == ChapterKind.OTHER) null else c
    }
}
