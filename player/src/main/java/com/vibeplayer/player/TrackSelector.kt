package com.vibeplayer.player

import java.util.Locale

enum class SubtitleKind {
    SIGNS_OR_FORCED,
    FULL
}

enum class SubtitleMode {
    FORCED_ONLY, // "Forced / Signs & Songs only"
    FULL,        // "Full subtitles"
    OFF          // "Off"
}

data class FileTrackChoice(
    val audioTrackId: Int? = null,
    val audioTrackTitle: String? = null,
    val audioTrackLang: String? = null,
    val audioEnabled: Boolean = true,
    val subTrackId: Int? = null,
    val subTrackTitle: String? = null,
    val subTrackLang: String? = null,
    val subEnabled: Boolean = true
)

data class FolderTrackChoice(
    val audioLang: String? = null,
    val audioTitle: String? = null,
    val subLang: String? = null,
    val subTitle: String? = null,
    val subKind: String? = null,
    val subEnabled: Boolean = true
)

data class LanguagePreferences(
    val preferredAudioLanguages: List<String> = listOf("en"),
    val preferredSubtitleLanguages: List<String> = listOf("en"),
    val subtitleMode: SubtitleMode = SubtitleMode.FORCED_ONLY
)

data class TrackSelectionResult(
    val selectedAudioTrackId: Int?,
    val audioEnabled: Boolean,
    val selectedSubTrackId: Int?,
    val subEnabled: Boolean
)

object TrackSelector {

    fun normalizeLang(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lower = raw.trim().lowercase()
        return when (lower) {
            "jp" -> "ja"
            else -> {
                try {
                    val loc = Locale.forLanguageTag(lower)
                    if (loc.language.isNotEmpty()) loc.language else lower
                } catch (_: Exception) {
                    lower
                }
            }
        }
    }

    fun langMatches(lang1: String?, lang2: String?): Boolean {
        if (lang1.isNullOrBlank() || lang2.isNullOrBlank()) return false
        val l1 = lang1.trim().lowercase()
        val l2 = lang2.trim().lowercase()
        if (l1 == l2) return true
        val norm1 = normalizeLang(l1)
        val norm2 = normalizeLang(l2)
        if (norm1 == norm2 && norm1.isNotEmpty()) return true
        val iso3_1 = try { Locale.forLanguageTag(norm1).isO3Language.lowercase() } catch (_: Exception) { "" }
        val iso3_2 = try { Locale.forLanguageTag(norm2).isO3Language.lowercase() } catch (_: Exception) { "" }
        if (iso3_1.isNotEmpty() && (iso3_1 == l2 || iso3_1 == iso3_2)) return true
        if (iso3_2.isNotEmpty() && (iso3_2 == l1 || iso3_1 == iso3_2)) return true
        return false
    }

    fun titleMatches(t1: String?, t2: String?): Boolean {
        if (t1.isNullOrBlank() && t2.isNullOrBlank()) return true
        if (t1.isNullOrBlank() || t2.isNullOrBlank()) return false
        return t1.trim().equals(t2.trim(), ignoreCase = true)
    }

    fun getSubtitleKind(track: Track): SubtitleKind {
        if (track.forced) return SubtitleKind.SIGNS_OR_FORCED
        val title = track.title?.lowercase() ?: ""
        if (title.contains("sign") || title.contains("song") || title.contains("forced") || title.contains("s&s")) {
            return SubtitleKind.SIGNS_OR_FORCED
        }
        return SubtitleKind.FULL
    }

    fun selectTracks(
        tracks: List<Track>,
        fileChoice: FileTrackChoice? = null,
        folderChoice: FolderTrackChoice? = null,
        preferences: LanguagePreferences = LanguagePreferences()
    ): TrackSelectionResult {
        val audioTracks = tracks.filter { it.type == "audio" }
        val subTracks = tracks.filter { it.type == "sub" }

        // --- 1. AUDIO SELECTION ---
        var selectedAudioId: Int? = null
        var audioEnabled = true
        var audioResolved = false

        // Rule 1: Per-file choice
        if (fileChoice != null) {
            if (!fileChoice.audioEnabled) {
                selectedAudioId = null
                audioEnabled = false
                audioResolved = true
            } else if (fileChoice.audioTrackId != null && audioTracks.any { it.id == fileChoice.audioTrackId }) {
                selectedAudioId = fileChoice.audioTrackId
                audioEnabled = true
                audioResolved = true
            } else if (!fileChoice.audioTrackLang.isNullOrBlank() || !fileChoice.audioTrackTitle.isNullOrBlank()) {
                val match = audioTracks.firstOrNull {
                    langMatches(it.lang, fileChoice.audioTrackLang) && titleMatches(it.title, fileChoice.audioTrackTitle)
                } ?: audioTracks.firstOrNull {
                    langMatches(it.lang, fileChoice.audioTrackLang)
                } ?: audioTracks.firstOrNull {
                    titleMatches(it.title, fileChoice.audioTrackTitle)
                }
                if (match != null) {
                    selectedAudioId = match.id
                    audioEnabled = true
                    audioResolved = true
                }
            }
        }

        // Rule 2: Folder choice
        if (!audioResolved && folderChoice != null) {
            if (!folderChoice.audioLang.isNullOrBlank() || !folderChoice.audioTitle.isNullOrBlank()) {
                val match = audioTracks.firstOrNull {
                    langMatches(it.lang, folderChoice.audioLang) && titleMatches(it.title, folderChoice.audioTitle)
                } ?: audioTracks.firstOrNull {
                    langMatches(it.lang, folderChoice.audioLang)
                }
                if (match != null) {
                    selectedAudioId = match.id
                    audioEnabled = true
                    audioResolved = true
                }
            }
        }

        // Rule 3: Language defaults from settings
        if (!audioResolved) {
            for (prefLang in preferences.preferredAudioLanguages) {
                val match = audioTracks.firstOrNull { langMatches(it.lang, prefLang) }
                if (match != null) {
                    selectedAudioId = match.id
                    audioEnabled = true
                    audioResolved = true
                    break
                }
            }
        }

        // Rule 4: mpv default
        if (!audioResolved && audioTracks.isNotEmpty()) {
            val defaultTrack = audioTracks.firstOrNull { it.selected } ?: audioTracks.first()
            selectedAudioId = defaultTrack.id
            audioEnabled = true
        }

        // --- 2. SUBTITLE SELECTION ---
        var selectedSubId: Int? = null
        var subEnabled = true
        var subResolved = false

        // Rule 1: Per-file choice
        if (fileChoice != null) {
            if (!fileChoice.subEnabled) {
                selectedSubId = null
                subEnabled = false
                subResolved = true
            } else if (fileChoice.subTrackId != null && subTracks.any { it.id == fileChoice.subTrackId }) {
                selectedSubId = fileChoice.subTrackId
                subEnabled = true
                subResolved = true
            } else if (!fileChoice.subTrackLang.isNullOrBlank() || !fileChoice.subTrackTitle.isNullOrBlank()) {
                val match = subTracks.firstOrNull {
                    langMatches(it.lang, fileChoice.subTrackLang) && titleMatches(it.title, fileChoice.subTrackTitle)
                } ?: subTracks.firstOrNull {
                    langMatches(it.lang, fileChoice.subTrackLang)
                } ?: subTracks.firstOrNull {
                    titleMatches(it.title, fileChoice.subTrackTitle)
                }
                if (match != null) {
                    selectedSubId = match.id
                    subEnabled = true
                    subResolved = true
                }
            }
        }

        // Rule 2: Folder choice
        if (!subResolved && folderChoice != null) {
            if (!folderChoice.subEnabled) {
                selectedSubId = null
                subEnabled = false
                subResolved = true
            } else if (!folderChoice.subLang.isNullOrBlank()) {
                val match = subTracks.firstOrNull {
                    langMatches(it.lang, folderChoice.subLang) && titleMatches(it.title, folderChoice.subTitle)
                } ?: subTracks.firstOrNull {
                    langMatches(it.lang, folderChoice.subLang) && getSubtitleKind(it).name == folderChoice.subKind
                } ?: subTracks.firstOrNull {
                    langMatches(it.lang, folderChoice.subLang)
                }
                if (match != null) {
                    selectedSubId = match.id
                    subEnabled = true
                    subResolved = true
                }
            }
        }

        // Rule 3: Language defaults from settings
        if (!subResolved && subTracks.isNotEmpty()) {
            val activeAudio = audioTracks.firstOrNull { it.id == selectedAudioId }
            val audioIsPreferred = activeAudio != null && preferences.preferredAudioLanguages.any {
                langMatches(activeAudio.lang, it)
            }

            if (audioIsPreferred) {
                when (preferences.subtitleMode) {
                    SubtitleMode.OFF -> {
                        selectedSubId = null
                        subEnabled = false
                        subResolved = true
                    }
                    SubtitleMode.FORCED_ONLY -> {
                        val match = subTracks.firstOrNull {
                            getSubtitleKind(it) == SubtitleKind.SIGNS_OR_FORCED &&
                            preferences.preferredSubtitleLanguages.any { pref -> langMatches(it.lang, pref) }
                        } ?: subTracks.firstOrNull {
                            getSubtitleKind(it) == SubtitleKind.SIGNS_OR_FORCED &&
                            langMatches(it.lang, activeAudio.lang)
                        }
                        if (match != null) {
                            selectedSubId = match.id
                            subEnabled = true
                        } else {
                            selectedSubId = null
                            subEnabled = false
                        }
                        subResolved = true
                    }
                    SubtitleMode.FULL -> {
                        val match = subTracks.firstOrNull {
                            getSubtitleKind(it) == SubtitleKind.FULL &&
                            preferences.preferredSubtitleLanguages.any { pref -> langMatches(it.lang, pref) }
                        } ?: subTracks.firstOrNull {
                            preferences.preferredSubtitleLanguages.any { pref -> langMatches(it.lang, pref) }
                        }
                        if (match != null) {
                            selectedSubId = match.id
                            subEnabled = true
                        } else {
                            selectedSubId = null
                            subEnabled = false
                        }
                        subResolved = true
                    }
                }
            } else {
                // Audio is NOT in a preferred language: full subtitles in preferred subtitle language
                val match = subTracks.firstOrNull {
                    getSubtitleKind(it) == SubtitleKind.FULL &&
                    preferences.preferredSubtitleLanguages.any { pref -> langMatches(it.lang, pref) }
                } ?: subTracks.firstOrNull {
                    preferences.preferredSubtitleLanguages.any { pref -> langMatches(it.lang, pref) }
                }
                if (match != null) {
                    selectedSubId = match.id
                    subEnabled = true
                } else {
                    selectedSubId = null
                    subEnabled = false
                }
                subResolved = true
            }
        }

        // Rule 4: mpv default
        if (!subResolved && subTracks.isNotEmpty()) {
            val defaultTrack = subTracks.firstOrNull { it.selected }
            if (defaultTrack != null) {
                selectedSubId = defaultTrack.id
                subEnabled = true
            } else {
                selectedSubId = null
                subEnabled = false
            }
        }

        return TrackSelectionResult(
            selectedAudioTrackId = selectedAudioId,
            audioEnabled = audioEnabled,
            selectedSubTrackId = selectedSubId,
            subEnabled = subEnabled
        )
    }
}
