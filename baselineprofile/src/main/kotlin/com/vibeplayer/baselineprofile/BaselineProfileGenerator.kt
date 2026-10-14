package com.vibeplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Generates the baseline profile on a connected device. Journey: cold start to the folder list,
 * scroll folders, open a folder, scroll its video list, open a video and let it play, show the
 * controls, open the audio and subtitle sheets, leave the player.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun grant() = grantStorage()

    @Test
    fun generate() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForBrowser()

        // Folder list
        scrollList()
        val folder = device.findObject(By.pkg(targetPackage).textContains("vibeplayer-test"))
            ?: listRows().firstOrNull()
        folder?.click()

        // Video list
        device.wait(Until.hasObject(By.pkg(targetPackage).scrollable(true)), TIMEOUT_MS)
        device.waitForIdle()
        scrollList()
        val video = device.findObject(By.pkg(targetPackage).textContains("ichiban"))
            ?: listRows().firstOrNull()
        video?.click()

        // Player: wait for playback, show controls, sheets
        device.wait(Until.hasObject(By.pkg(targetPackage).text("←")), TIMEOUT_MS)
        Thread.sleep(3_000)
        device.click(device.displayWidth / 2, device.displayHeight / 2) // toggles the controls
        for ((button, title) in listOf("🎵" to "Audio Tracks", "💬" to "Subtitles")) {
            if (!device.hasObject(By.text(button))) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
            }
            device.wait(Until.findObject(By.text(button)), TIMEOUT_MS)?.click()
            device.wait(Until.hasObject(By.text(title)), TIMEOUT_MS)
            device.pressBack() // closes the sheet
            device.waitForIdle()
        }

        // Leave the player (back to the video list), then home
        device.pressBack()
        device.waitForIdle()
        device.wait(Until.hasObject(By.pkg(targetPackage).scrollable(true)), TIMEOUT_MS)
    }
}
