package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectorTest {

    // Test 1: English audio + signs track present
    // Audio: English (id=1) + Japanese (id=2)
    // Sub: Signs & Songs (id=1, "eng", forced/signs), Full Subtitles (id=2, "eng", full)
    // Defaults: pref audio = ["en"], pref sub = ["en"], subMode = FORCED_ONLY
    // Expected: Audio = 1 (English), Sub = 1 (Signs & Songs)
    @Test
    fun testEnglishAudioPlusSignsTrackPresent() {
        val tracks = listOf(
            Track(id = 1, type = "audio", title = "English Opus", lang = "eng"),
            Track(id = 2, type = "audio", title = "Japanese Opus", lang = "jpn"),
            Track(id = 1, type = "sub", title = "English Signs & Songs", lang = "eng"),
            Track(id = 2, type = "sub", title = "English Full Subtitles", lang = "eng")
        )
        val preferences = LanguagePreferences(
            preferredAudioLanguages = listOf("en"),
            preferredSubtitleLanguages = listOf("en"),
            subtitleMode = SubtitleMode.FORCED_ONLY
        )

        val result = TrackSelector.selectTracks(
            tracks = tracks,
            preferences = preferences
        )

        assertEquals(1, result.selectedAudioTrackId)
        assertTrue(result.audioEnabled)
        assertEquals(1, result.selectedSubTrackId)
        assertTrue(result.subEnabled)
    }

    // Test 2: Japanese-only audio
    // Audio: Japanese (id=1)
    // Sub: Signs (id=1, "eng"), Full Dialogue (id=2, "eng")
    // Defaults: pref audio = ["en"], pref sub = ["en"], subMode = FORCED_ONLY
    // Because audio is Japanese (NOT preferred), full subtitles in preferred subtitle language should be chosen.
    // Expected: Audio = 1 (Japanese), Sub = 2 (Full dialogue)
    @Test
    fun testJapaneseOnlyAudio() {
        val tracks = listOf(
            Track(id = 1, type = "audio", title = "Japanese Audio", lang = "ja"),
            Track(id = 1, type = "sub", title = "Signs & Songs", lang = "en"),
            Track(id = 2, type = "sub", title = "Full Dialogue Subtitles", lang = "en")
        )
        val preferences = LanguagePreferences(
            preferredAudioLanguages = listOf("en"),
            preferredSubtitleLanguages = listOf("en"),
            subtitleMode = SubtitleMode.FORCED_ONLY
        )

        val result = TrackSelector.selectTracks(
            tracks = tracks,
            preferences = preferences
        )

        assertEquals(1, result.selectedAudioTrackId)
        assertTrue(result.audioEnabled)
        assertEquals(2, result.selectedSubTrackId)
        assertTrue(result.subEnabled)
    }

    // Test 3: Folder choice overriding the defaults
    // Defaults say: pref audio = ["en"], but folder choice has audioLang = "ja", subLang = "en", subKind = "FULL"
    // Audio: English (id=1), Japanese (id=2)
    // Sub: Signs (id=1, "en"), Full Subtitles (id=2, "en")
    // Expected: Audio = 2 (Japanese), Sub = 2 (Full)
    @Test
    fun testFolderChoiceOverridingDefaults() {
        val tracks = listOf(
            Track(id = 1, type = "audio", title = "English Opus", lang = "en"),
            Track(id = 2, type = "audio", title = "Japanese Opus", lang = "ja"),
            Track(id = 1, type = "sub", title = "Signs", lang = "en"),
            Track(id = 2, type = "sub", title = "Full Subtitles", lang = "en")
        )
        val folderChoice = FolderTrackChoice(
            audioLang = "ja",
            audioTitle = "Japanese Opus",
            subLang = "en",
            subTitle = "Full Subtitles",
            subKind = SubtitleKind.FULL.name,
            subEnabled = true
        )
        val preferences = LanguagePreferences(
            preferredAudioLanguages = listOf("en"),
            preferredSubtitleLanguages = listOf("en")
        )

        val result = TrackSelector.selectTracks(
            tracks = tracks,
            folderChoice = folderChoice,
            preferences = preferences
        )

        assertEquals(2, result.selectedAudioTrackId)
        assertEquals(2, result.selectedSubTrackId)
        assertTrue(result.subEnabled)
    }

    // Test 4: File choice overriding the folder
    // Folder choice: audio = Japanese, sub = English full
    // File choice: audioTrackId = 1 (English), subEnabled = false
    // Expected: Audio = 1, Sub = null, subEnabled = false
    @Test
    fun testFileChoiceOverridingFolder() {
        val tracks = listOf(
            Track(id = 1, type = "audio", title = "English Audio", lang = "en"),
            Track(id = 2, type = "audio", title = "Japanese Audio", lang = "ja"),
            Track(id = 1, type = "sub", title = "English Full Subtitles", lang = "en")
        )
        val folderChoice = FolderTrackChoice(
            audioLang = "ja",
            audioTitle = "Japanese Audio",
            subLang = "en",
            subTitle = "English Full Subtitles",
            subKind = SubtitleKind.FULL.name,
            subEnabled = true
        )
        val fileChoice = FileTrackChoice(
            audioTrackId = 1,
            audioEnabled = true,
            subTrackId = null,
            subEnabled = false
        )

        val result = TrackSelector.selectTracks(
            tracks = tracks,
            fileChoice = fileChoice,
            folderChoice = folderChoice
        )

        assertEquals(1, result.selectedAudioTrackId)
        assertTrue(result.audioEnabled)
        assertEquals(null, result.selectedSubTrackId)
        assertFalse(result.subEnabled)
    }

    // Test 5: Track missing in the next file
    // Folder choice had targeted audio "ja" (title "Japanese (FLAC)"), sub "en" (title "Dialogue (Styled)")
    // Next file does NOT have the same titles or IDs, but has audio "jpn" and sub "eng" full subtitles!
    // Expected: Fallback matches audio "jpn" and sub "eng" by language and kind!
    @Test
    fun testTrackMissingInNextFileFallback() {
        val tracks = listOf(
            Track(id = 10, type = "audio", title = "English (AAC)", lang = "eng"),
            Track(id = 20, type = "audio", title = "Japanese (AAC)", lang = "jpn"),
            Track(id = 30, type = "sub", title = "Signs", lang = "eng"),
            Track(id = 40, type = "sub", title = "Main Dialogue", lang = "eng")
        )
        val folderChoice = FolderTrackChoice(
            audioLang = "ja",
            audioTitle = "Japanese (FLAC)", // doesn't match title, but lang matches "jpn"
            subLang = "en",
            subTitle = "Dialogue (Styled)", // doesn't match title, but matches subKind FULL + lang
            subKind = SubtitleKind.FULL.name,
            subEnabled = true
        )

        val result = TrackSelector.selectTracks(
            tracks = tracks,
            folderChoice = folderChoice
        )

        assertEquals(20, result.selectedAudioTrackId)
        assertEquals(40, result.selectedSubTrackId)
        assertTrue(result.subEnabled)
    }

    @Test
    fun testExternalSubtitleMatchingByTitle() {
        val tracks = listOf(
            Track(id = 1, type = "sub", title = "Internal English", lang = "eng", external = false),
            Track(id = 2, type = "sub", title = "my_custom_subs.srt", lang = null, external = true)
        )
        val fileChoice = FileTrackChoice(
            audioTrackId = null,
            audioTrackTitle = null,
            audioTrackLang = null,
            audioEnabled = true,
            subTrackId = 999, // old ID
            subTrackTitle = "my_custom_subs.srt",
            subTrackLang = null,
            subEnabled = true
        )
        val result = TrackSelector.selectTracks(
            tracks = tracks,
            fileChoice = fileChoice
        )
        assertEquals(2, result.selectedSubTrackId)
        assertTrue(result.subEnabled)
    }
}
