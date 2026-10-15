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
        if (action == "clear_vulkan_cache") {
            // P6-07 tests: a forced Vulkan failure is remembered per format on this driver
            // build; this undoes it so the table row applies again. Needs no playback session.
            com.vibeplayer.playback.VulkanDriverCache.clearCache(context)
            Log.i("VP", "DebugActionReceiver: cleared Vulkan driver cache (remembered failures)")
            return
        }
        if (action == "system_trace" || action == "trace" || action == "request_profiling" || intent.action == "com.vibeplayer.REQUEST_PROFILING") {
            // P10-07: adb broadcast action to request a 10 s system trace for stall investigations
            val durationMs = intent.getIntExtra("duration_ms", com.vibeplayer.profiling.ProfilingManagerHelper.DEFAULT_DURATION_MS)
            val tag = intent.getStringExtra("tag") ?: com.vibeplayer.profiling.ProfilingManagerHelper.DEFAULT_TAG
            val ok = com.vibeplayer.profiling.ProfilingController.requestSystemTrace(context, durationMs = durationMs, tag = tag)
            Log.i("VP-Prof", "DebugActionReceiver: requestProfiling system trace (ok=$ok, durationMs=$durationMs, tag=$tag)")
            return
        }
        // Only act on a running session: creating an engine here would leave one behind that no
        // service owns and nothing ever releases.
        val service = PlaybackService.currentInstance
        if (service == null) {
            Log.w("VP", "DebugActionReceiver: no active playback session, ignoring '$action'")
            return
        }
        val engine = service.playerEngine

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
                service.skipToNeighbour(true)
                Log.i("VP", "DebugActionReceiver: next")
            }
            action == "prev" -> {
                service.skipToNeighbour(false)
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
                    service.setAspect(mode)
                    Log.i("VP", "DebugActionReceiver: setAspect $mode")
                } else {
                    val modes = listOf("fit", "crop", "stretch", "16:9", "4:3")
                    val current = engine.state.value.aspect ?: "fit"
                    val idx = modes.indexOf(current).let { if (it >= 0) it else 0 }
                    val nextMode = modes[(idx + 1) % modes.size]
                    service.setAspect(nextMode)
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
                    service.setSpeed(sp)
                    service.persistSpeed(sp)
                    Log.i("VP", "DebugActionReceiver: setSpeed $sp")
                }
            }
            action == "double_tap" || intent.action == "com.vibeplayer.DEBUG_DOUBLE_TAP" -> {
                val side = intent.getStringExtra("side")?.lowercase() ?: "right"
                val sec = if (side == "left") -10.0 else 10.0
                engine.seekRelative(sec, exact = true)
                Log.i("VP", "DebugActionReceiver: double_tap side=$side, seekRelative $sec s")
            }
            else -> {
                Log.w("VP", "DebugActionReceiver: unknown action '$action'")
            }
        }
    }
}
