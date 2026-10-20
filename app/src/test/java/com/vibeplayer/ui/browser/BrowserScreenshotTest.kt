package com.vibeplayer.ui.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.media.VideoItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Browser rows: watched mark, progress bar, folder name in search results (see PanelScreenshotTest for how to record). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w393dp-h400dp-port-xxhdpi")
class BrowserScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun video(id: Long, name: String, folder: String) = VideoItem(
        id = id, uri = Uri.parse("file:///sdcard/Movies/$folder/$name"), path = "/sdcard/Movies/$folder/$name",
        displayName = name, bucketId = folder, bucketName = folder, durationMs = 1_420_000L,
        sizeBytes = 512L * 1024 * 1024, dateModified = 0L, width = 1920, height = 1080
    )

    @Test
    fun searchResultRows() {
        compose.setContent {
            com.vibeplayer.ui.theme.VibeTheme {
                Column(Modifier.background(Color(0xFF141218))) {
                    VideoItemRow(
                        video(1, "[grp] Bocchi the Rock! - 01.mkv", "Anime"), onClick = {}, folderName = "Anime",
                        fileSettings = FileSettings(key = "a", positionMs = 1_410_000L, durationMs = 1_420_000L)
                    )
                    VideoItemRow(
                        video(2, "[grp] Bocchi the Rock! - 02.mkv", "Anime"), onClick = {}, folderName = "Anime",
                        fileSettings = FileSettings(key = "b", positionMs = 600_000L, durationMs = 1_420_000L)
                    )
                    VideoItemRow(video(3, "[grp] Bocchi the Rock! - 03.mkv", "Downloads"), onClick = {}, folderName = "Downloads")
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/browser_search_rows.png")
    }
}
