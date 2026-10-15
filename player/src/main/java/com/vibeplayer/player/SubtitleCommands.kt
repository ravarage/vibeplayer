package com.vibeplayer.player

// P4-14: Helpers for loading external subtitle files via Storage Access Framework (SAF).
object SubtitleCommands {

    // Preferred MIME types for ACTION_OPEN_DOCUMENT.
    // Text and subtitle types are preferred, with wildcard "*/*" so any subtitle file format
    // can be opened even if the system does not recognize its extension.
    val OPEN_DOCUMENT_MIME_TYPES = arrayOf(
        "text/*",
        "application/x-subrip",
        "text/vtt",
        "text/x-ssa",
        "text/x-ass",
        "application/octet-stream",
        "*/*"
    )

    /**
     * Builds the argument list for the mpv `sub-add` command.
     * mpv syntax: `sub-add <url> [<flags> [<title> [<lang>]]]`
     *
     * @param fdPath The descriptor URL, e.g. `fdclose://<fd>`.
     * @param title The track title to display in mpv (e.g. file display name).
     * @param select If true, selects the track immediately ("select"); otherwise adds it without selecting
     *   ("auto"). Not "cached": mpv's `cached` flag also selects the track.
     */
    fun buildSubAddArgs(fdPath: String, title: String? = null, select: Boolean = true): Array<String> {
        val flag = if (select) "select" else "auto"
        return if (!title.isNullOrEmpty()) {
            arrayOf("sub-add", fdPath, flag, title)
        } else {
            arrayOf("sub-add", fdPath, flag)
        }
    }

    /**
     * Whether an external subtitle re-added after a reload on a new mpv handle is selected. When its
     * track was on the old handle, it is selected only if it was the selected subtitle then
     * ([selectedSubTitle], the title of the selected sub track, null when none); when it was still
     * pending (never added), its requested selection stands.
     */
    fun selectOnRestore(sourceTitle: String, requestedSelect: Boolean, trackWasPresent: Boolean, selectedSubTitle: String?): Boolean =
        if (trackWasPresent) selectedSubTitle == sourceTitle else requestedSelect
}
