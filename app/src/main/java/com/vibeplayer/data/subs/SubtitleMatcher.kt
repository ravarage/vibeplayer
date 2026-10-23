package com.vibeplayer.data.subs

/**
 * Finds subtitle files that belong to a video by name ("Ep01.mkv" -> "Ep01.srt", "Ep01.en.ass",
 * "Ep01.eng.forced.srt") and maps a video path to the folder's document id inside a granted tree.
 */
object SubtitleMatcher {

    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    private const val PRIMARY_ROOT = "/storage/emulated/0"
    private val PRIMARY_ALIASES = listOf("/sdcard", "/storage/self/primary")

    /** Whether [candidate] is a subtitle for [videoName]: same base name, optionally followed by ".tags". */
    fun matches(videoName: String, candidate: String): Boolean {
        val ext = candidate.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) return false
        val videoBase = videoName.substringBeforeLast('.')
        if (videoBase.isEmpty()) return false
        val candBase = candidate.substring(0, candidate.length - ext.length - 1)
        return candBase.equals(videoBase, ignoreCase = true) ||
            (candBase.length > videoBase.length + 1 &&
                candBase.startsWith(videoBase, ignoreCase = true) &&
                candBase[videoBase.length] == '.')
    }

    /** Matches in a stable order: the exact name first, then by name. */
    fun select(videoName: String, names: List<String>): List<String> {
        val base = videoName.substringBeforeLast('.')
        return names.filter { matches(videoName, it) }
            .sortedWith(compareBy<String>({ it.substringBeforeLast('.').length != base.length }, { it.lowercase() }))
    }

    /**
     * The document id of the folder holding [videoPath] inside the tree whose root id is [treeDocId]
     * ("primary:Movies"), or null when the video is not under that tree. Only the external storage
     * provider's id scheme ("<volume>:<relative path>") is understood.
     */
    fun folderDocumentId(treeDocId: String, videoPath: String): String? {
        val volume = treeDocId.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val treeRel = treeDocId.substringAfter(':').trim('/')
        val root = if (volume == "primary") PRIMARY_ROOT else "/storage/$volume"
        // /sdcard and /storage/self/primary are the same primary storage (a file opened by path from adb or a
        // file manager arrives as /sdcard/..., 2026-10-23).
        val path = PRIMARY_ALIASES.firstOrNull { videoPath.startsWith("$it/") }
            ?.let { PRIMARY_ROOT + videoPath.removePrefix(it) } ?: videoPath
        if (!path.startsWith("$root/")) return null
        val folderRel = path.removePrefix("$root/").substringBeforeLast('/', "")
        val inside = treeRel.isEmpty() || folderRel == treeRel || folderRel.startsWith("$treeRel/")
        return if (inside) "$volume:$folderRel" else null
    }
}
