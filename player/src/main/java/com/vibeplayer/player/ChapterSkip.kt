package com.vibeplayer.player

/** What a chapter is, from its title, for the skip button and auto-skip. */
enum class ChapterKind(val skipLabel: String?) {
    OPENING("Skip opening"),
    ENDING("Skip ending"),
    OTHER(null)
}

object ChapterSkip {

    private val OPENING_WORDS = setOf("opening", "intro", "introduction")
    private val ENDING_WORDS = setOf("ending", "outro", "credits")
    private val NUMBERED_OP = Regex("op\\d{1,2}", RegexOption.IGNORE_CASE)
    private val NUMBERED_ED = Regex("ed\\d{1,2}", RegexOption.IGNORE_CASE)

    /**
     * Classifies by whole words of the title, so "Opening", "OP", "OP2", "Intro" or "Ending Credits"
     * match and "Operation" or "Edward" do not. The bare short forms count only in capitals ("OP",
     * "ED"), so a name such as "Ed's Story" is not an ending; numbered ones in any case ("op2").
     * Opening wins when a title has both.
     */
    fun kindOf(title: String): ChapterKind {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        fun isWord(w: String, long: Set<String>, short: String, numbered: Regex) =
            w.lowercase() in long || w == short || numbered.matches(w)
        if (words.any { isWord(it, OPENING_WORDS, "OP", NUMBERED_OP) }) return ChapterKind.OPENING
        if (words.any { isWord(it, ENDING_WORDS, "ED", NUMBERED_ED) }) return ChapterKind.ENDING
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
