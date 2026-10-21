package com.vibeplayer.ui.net

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibeplayer.data.prefs.BrowserPreferences
import com.vibeplayer.net.NetRepository
import com.vibeplayer.net.NetSource
import com.vibeplayer.net.NetType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P13: the Network screen with saved sources and recent links (see PanelScreenshotTest for how to record). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w393dp-h873dp-port-xxhdpi")
class NetworkScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun networkScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = NetRepository(context)
        val prefs = BrowserPreferences(context)
        runBlocking {
            // no password: the Keystore is not available on the JVM
            repo.save(NetSource(type = NetType.SFTP.name, name = "Home PC", host = "192.168.1.10", path = "/home/me/Videos", username = "me"), null)
            repo.save(NetSource(type = NetType.SMB.name, name = "NAS", host = "nas.local", path = "/Media/Anime", username = "me"), null)
            repo.save(NetSource(type = NetType.M3U.name, name = "IPTV", host = "http://provider.example/get.php?type=m3u"), null)
            repo.save(NetSource(type = NetType.WEBDAV.name, name = "Nextcloud", host = "https://cloud.example/remote.php/dav/files/me/"), null)
            prefs.addRecentLink("https://example.com/stream/master.m3u8")
        }
        compose.setContent {
            com.vibeplayer.ui.theme.VibeTheme {
                NetworkScreen(repo = repo, preferences = prefs, onBack = {}, onBrowse = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/network_screen.png")
    }
}
