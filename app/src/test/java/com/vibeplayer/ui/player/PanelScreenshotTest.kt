package com.vibeplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibeplayer.player.Chapter
import com.vibeplayer.player.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the player panels and popups to PNG on the JVM, to look at layout changes without the phone.
 * `./gradlew :app:testDebugUnitTest -Proborazzi.record=true --tests '*PanelScreenshotTest*'` writes
 * them to app/build/outputs/roborazzi/; a normal test run only renders them (catches crashes).
 * Landscape is the phone's 2400x1080 at 440 dpi (~873x393 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w873dp-h393dp-land-xxhdpi")
class PanelScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val tracks = listOf(
        Track(1, "video", codec = "av1"),
        Track(1, "audio", title = "Japanese", lang = "jpn", codec = "opus", channels = 2, selected = true),
        Track(2, "audio", title = "English 5.1", lang = "eng", codec = "eac3", channels = 6),
        Track(1, "sub", title = "Signs & Songs", lang = "eng", codec = "ass", forced = true),
        Track(2, "sub", title = "Full Subtitles", lang = "eng", codec = "ass"),
        Track(3, "sub", title = "Español", lang = "spa", codec = "subrip"),
        Track(4, "sub", title = "Ep01.en.srt", codec = "subrip", external = true)
    )

    private val chapters = listOf(
        Chapter(0, "Prologue", 0L), Chapter(1, "Opening", 90_000L), Chapter(2, "Part A", 180_000L),
        Chapter(3, "Part B", 780_000L), Chapter(4, "Ending", 1_290_000L), Chapter(5, "", 1_380_000L)
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { com.vibeplayer.ui.theme.VibeTheme {
            // A stand-in for the video: panels are translucent, so a flat black would hide that.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF2E5E8C), Color(0xFFB0643C), Color(0xFF3C8C5A))))
            ) {
                content()
            }
        } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun subtitlesLandscape() = shot("subtitles_land") {
        SubtitleTrackSheet(tracks, selectedTrackId = 2, onSelectTrack = {}, onDismiss = {}, onOpenSync = {}, onLoadSubtitle = {}, onSubSeek = {})
    }

    @Test
    @Config(qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun subtitlesPortrait() = shot("subtitles_port") {
        SubtitleTrackSheet(tracks, selectedTrackId = 2, onSelectTrack = {}, onDismiss = {}, onOpenSync = {}, onLoadSubtitle = {})
    }

    @Test
    fun audioLandscape() = shot("audio_land") {
        AudioTrackSheet(tracks, selectedTrackId = 1, onSelectTrack = {}, onDismiss = {}, nightMode = true, onToggleNightMode = {},
            equalizerOn = true, onOpenEqualizer = {})
    }

    @Test
    fun equalizerLandscape() = shot("equalizer_land") {
        EqualizerSheet(com.vibeplayer.player.Equalizer(true, listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)), onPreview = {}, onSave = {}, onDismiss = {})
    }

    @Test
    fun speedLandscape() = shot("speed_land") {
        SpeedSheet(currentSpeed = 1.5, onSpeedChanged = {}, onDismiss = {})
    }

    @Test
    fun syncLandscape() = shot("sync_land") {
        SyncSheet(subDelayMs = 250, audioDelayMs = 0, onSubDelay = {}, onAudioDelay = {}, onDismiss = {})
    }

    @Test
    fun chaptersLandscape() = shot("chapters_land") {
        ChapterSheet(chapters, currentChapter = 2, onSelectChapter = {}, onDismiss = {})
    }

    @Test
    fun moreLandscape() = shot("more_land") {
        MoreSheet(
            sleepTimer = null, onSetSleepTimer = {}, abLoopAMs = 61_000L, abLoopBMs = null, onCycleAbLoop = {},
            loopFile = false, onToggleLoopFile = {}, rotationMode = RotationMode.AUTO, onCycleRotation = {}, onDismiss = {},
            pictureAdjust = com.vibeplayer.player.PictureAdjust(brightness = 10, saturation = -25),
            bookmarkCount = 2, onOpenBookmarks = {},
            folderShow = FolderShowControls(
                show = com.vibeplayer.playback.FolderShow("/sdcard/Anime/Bocchi the Rock", speed = 1.25f, autoSkipChapters = true),
                currentSpeed = 1.25
            )
        )
    }

    @Test
    fun bookmarksLandscape() = shot("bookmarks_land") {
        BookmarkSheet(
            bookmarks = listOf(
                com.vibeplayer.data.db.Bookmark(id = 1, fileKey = "k", positionMs = 95_000L, label = "Opening"),
                com.vibeplayer.data.db.Bookmark(id = 2, fileKey = "k", positionMs = 812_000L, label = "Part B")
            ),
            onAdd = {}, onJump = {}, onDelete = {}, onDismiss = {}
        )
    }

    @Test
    fun cardsLandscape() = shot("cards_land") {
        Box(Modifier.fillMaxSize()) {
            ResumeChip(
                info = com.vibeplayer.playback.ResumeInfo("/a/ep01.mkv", 750_000L, 1L), onStartOver = {},
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 40.dp)
            )
            UpNextCard(
                upNext = com.vibeplayer.playback.UpNext("/a/ep01.mkv", "/a/ep02.mkv", "[grp] Bocchi the Rock! - 02.mkv", Long.MAX_VALUE / 2),
                onPlayNow = {}, onCancel = {},
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp)
            )
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun morePortraitWithQuickActions() = shot("more_port") {
        MoreSheet(
            sleepTimer = com.vibeplayer.playback.SleepTimer.endOfFile(), onSetSleepTimer = {}, abLoopAMs = null,
            abLoopBMs = null, onCycleAbLoop = {}, loopFile = true, onToggleLoopFile = {},
            rotationMode = RotationMode.PORTRAIT, onCycleRotation = {}, onDismiss = {},
            quickActions = listOf(
                QuickAction("Speed 1.5x", "speed", selected = true) {}, QuickAction("📑 Chapters", "chapters") {},
                QuickAction("Aspect: fit", "aspect") {}, QuickAction("Mini player", "pip") {},
                QuickAction("Efficient", "quality_mode") {}
            )
        )
    }

    @Test
    fun indicatorsLandscape() = shot("indicators_land") {
        Box(Modifier.fillMaxSize()) {
            BrightnessIndicator(visible = true, brightness = 0.6f, modifier = Modifier.align(Alignment.CenterStart).padding(start = 64.dp))
            VolumeIndicator(visible = true, volume = 22, maxVolume = 30, percent = 150, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 64.dp))
            SwipeSeekIndicator(visible = true, deltaSeconds = 20, targetPositionMs = 754_000L, durationMs = 1_420_000L, modifier = Modifier.align(Alignment.Center))
            ChapterSkipButton(chapters, currentChapter = 1, controlsVisible = true, onSkip = {},
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp))
        }
    }
}
