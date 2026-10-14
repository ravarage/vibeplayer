package com.vibeplayer.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Package under test. The plugin passes the real id of the tested APK as `targetAppId`. */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?.takeIf { it.isNotBlank() }
        ?: "com.vibeplayer.benchmark"

const val PLAYER_ACTIVITY = "com.vibeplayer.ui.player.PlayerActivity"
const val TEST_VIDEO = "file:///sdcard/Movies/vibeplayer-test/ichiban.mp4"
const val TIMEOUT_MS = 8_000L

/**
 * The benchmark build has its own applicationId (com.vibeplayer.benchmark) and so a fresh, empty
 * data dir: grant the storage permissions the browser needs once, via the shell. Never touches
 * com.vibeplayer.
 */
fun grantStorage(pkg: String = targetPackage) {
    val d = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    d.executeShellCommand("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
    d.executeShellCommand("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
}

/** ACTION_VIEW straight into the player, like "open with" from a file manager. */
fun MacrobenchmarkScope.openVideoByIntent() {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(TEST_VIDEO), "video/*")
        setClassName(targetPackage, PLAYER_ACTIVITY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivityAndWait(intent)
}

/** Browser is showing the folder list (or, with no videos, its empty message). */
fun MacrobenchmarkScope.waitForBrowser() {
    device.wait(Until.hasObject(By.pkg(targetPackage).scrollable(true)), TIMEOUT_MS)
}

/** Widest clickable rows of the current list (list rows are full-width; icon buttons are not). */
fun MacrobenchmarkScope.listRows(): List<UiObject2> {
    val minWidth = device.displayWidth * 8 / 10
    return device.findObjects(By.pkg(targetPackage).clickable(true))
        .filter { it.visibleBounds.width() >= minWidth && it.visibleBounds.height() > 0 }
}

fun MacrobenchmarkScope.scrollList() {
    val list = device.findObject(By.pkg(targetPackage).scrollable(true)) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(3) {
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
