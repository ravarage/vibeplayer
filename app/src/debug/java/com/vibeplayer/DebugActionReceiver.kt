package com.vibeplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vibeplayer.playback.PlaybackService

/**
 * Debug-only broadcast receiver for automating playback actions via adb:
 * pause, play, next, prev, seek, aspect, speed, double_tap and clear_vulkan_cache.
 */
class DebugActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action")
            ?: intent.action?.removePrefix("com.vibeplayer.")?.removePrefix("DEBUG_")?.lowercase()
            ?: return

        Log.i("VP", "DebugActionReceiver onReceive: action='$action'")
        val service = PlaybackService.currentInstance
        val engine = service?.playerEngine ?: PlaybackService.getOrCreateEngine()

        when {
            action == "pause" -> {
                engine.pause()
                Log.i("VP", "DebugActionReceiver: paused")
            }
            action == "play" -> {
                engine.play()
                Log.i("VP", "DebugActionReceiver: played")
            }
            action == "toggle_pause" -> {
                engine.togglePause()
                Log.i("VP", "DebugActionReceiver: toggled pause")
            }
            action == "next" -> {
                service?.skipToNeighbour(true)
                Log.i("VP", "DebugActionReceiver: next")
            }
            action == "prev" -> {
                service?.skipToNeighbour(false)
                Log.i("VP", "DebugActionReceiver: prev")
            }
            action.startsWith("seek:") || action == "seek" -> {
                val targetMs = if (action.startsWith("seek:")) {
                    action.substringAfter("seek:").toLongOrNull()
                } else {
                    intent.getLongExtra("ms", -1L).takeIf { it >= 0 }
                        ?: intent.getLongExtra("position_ms", -1L).takeIf { it >= 0 }
                        ?: intent.getIntExtra("ms", -1).toLong().takeIf { it >= 0 }
                        ?: intent.getStringExtra("ms")?.toLongOrNull()
                }
                if (targetMs != null) {
                    engine.seekTo(targetMs, exact = true)
                    Log.i("VP", "DebugActionReceiver: seekTo $targetMs ms")
                } else {
                    val deltaSec = intent.getDoubleExtra("delta_s", 0.0)
                    if (deltaSec != 0.0) {
                        engine.seekRelative(deltaSec, exact = true)
                        Log.i("VP", "DebugActionReceiver: seekRelative $deltaSec s")
                    }
                }
            }
            action.startsWith("aspect:") || action == "aspect" -> {
                val mode = if (action.startsWith("aspect:")) {
                    action.substringAfter("aspect:")
                } else {
                    intent.getStringExtra("mode") ?: intent.getStringExtra("aspect")
                }
                if (!mode.isNullOrEmpty()) {
                    service?.setAspect(mode) ?: engine.setAspect(mode)
                    Log.i("VP", "DebugActionReceiver: setAspect $mode")
                } else {
                    val modes = listOf("fit", "crop", "stretch", "16:9", "4:3")
                    val current = engine.state.value.aspect ?: "fit"
                    val idx = modes.indexOf(current).let { if (it >= 0) it else 0 }
                    val nextMode = modes[(idx + 1) % modes.size]
                    service?.setAspect(nextMode) ?: engine.setAspect(nextMode)
                    Log.i("VP", "DebugActionReceiver: cycled aspect to $nextMode")
                }
            }
            action.startsWith("speed:") || action == "speed" -> {
                val sp = if (action.startsWith("speed:")) {
                    action.substringAfter("speed:").toDoubleOrNull()
                } else {
                    intent.getDoubleExtra("speed", -1.0).takeIf { it > 0 }
                        ?: intent.getStringExtra("speed")?.toDoubleOrNull()
                }
                if (sp != null && sp > 0) {
                    service?.setSpeed(sp) ?: engine.setSpeed(sp)
                    service?.persistSpeed(sp)
                    Log.i("VP", "DebugActionReceiver: setSpeed $sp")
                }
            }
            action == "double_tap" || intent.action == "com.vibeplayer.DEBUG_DOUBLE_TAP" -> {
                val side = intent.getStringExtra("side")?.lowercase() ?: "right"
                val sec = if (side == "left") -10.0 else 10.0
                engine.seekRelative(sec, exact = true)
                Log.i("VP", "DebugActionReceiver: double_tap side=$side, seekRelative $sec s")
            }
            action == "clear_vulkan_cache" -> {
                // P6-07 tests: a forced Vulkan failure is remembered per format on this driver
                // build; this undoes it so the table row applies again.
                com.vibeplayer.playback.VulkanDriverCache.clearCache(context)
                Log.i("VP", "DebugActionReceiver: cleared Vulkan driver cache (remembered failures)")
            }
            else -> {
                Log.w("VP", "DebugActionReceiver: unknown action '$action'")
            }
        }
    }
}
