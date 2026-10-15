package com.vibeplayer.playback

import android.content.Context
import android.os.PerformanceHintManager
import android.util.Log
import com.vibeplayer.player.AdpfHintPolicy
import com.vibeplayer.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADPF performance hint session for playback (P10-06), behind the "Performance hints (experimental)"
 * setting.
 *
 * While the setting is on and a file plays (not paused, not at its end), one [PerformanceHintManager]
 * session holds the mpv playback threads (names and matching rules: [AdpfHintPolicy]). The session is
 * closed on pause, stop, file end, setting off and service destroy (cancellation of the loop).
 * Everything runs on the IO dispatcher: the /proc scan, the session calls and the close.
 *
 * Once per second: re-scan /proc when due (file loaded, surface attached or detached, handle replaced
 * after a stall, schedule), update the
 * target when the container fps changes, update power efficiency when the decode path changes, and
 * report one work duration derived from the drop counters ([AdpfHintPolicy.reportedDurationNanos]).
 * When mpv does not answer the drop read (decoder stall, B-08) that second is not reported.
 */
class PerformanceHintController(
    context: Context,
    private val engine: PlayerEngine,
    private val enabledFlow: Flow<Boolean>,
) {
    private val hintManager: PerformanceHintManager? =
        try {
            context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
        } catch (e: Exception) {
            null
        }


    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val playing = engine.state.map { !it.paused && !it.eof }.distinctUntilChanged()
            combine(enabledFlow.distinctUntilChanged(), playing) { on, isPlaying -> on to isPlaying }
                .distinctUntilChanged()
                .collectLatest { (on, isPlaying) ->
                    when {
                        !on -> engine.setAdpfStatus(AdpfHintPolicy.overlayText(false, true, null))
                        unsupported -> engine.setAdpfStatus(AdpfHintPolicy.overlayText(true, false, null))
                        !isPlaying -> engine.setAdpfStatus(AdpfHintPolicy.overlayText(true, true, null))
                        else -> runSession()
                    }
                }
        }
    }

    private fun markUnsupported(reason: String) {
        unsupported = true
        engine.setAdpfStatus(AdpfHintPolicy.overlayText(true, false, null))
        if (!loggedUnsupported) {
            loggedUnsupported = true
            Log.w(TAG, "ADPF unsupported: $reason")
        }
    }

    private suspend fun runSession() {
        val manager = hintManager ?: return markUnsupported("no PerformanceHintManager service")
        var session: PerformanceHintManager.Session? = null
        var tids = IntArray(0)
        // Each failing call kind is logged once per session, not every second.
        val warned = HashSet<String>()
        fun warnOnce(kind: String, e: Exception) {
            if (warned.add(kind)) Log.w(TAG, "$kind failed: ${e.message}")
        }
        var createFailures = 0
        try {
            var lastLoadSerial = -1
            var lastSurfaceEpoch = -1
            var secondsSinceLoad = 0
            var previousDrops: Long? = null
            var target = 0L
            var powerEfficient: Boolean? = null
            while (true) {
                val loadSerial = engine.threadChangeSerial.value
                val surfaceEpoch = engine.surfaceEpoch.value
                if (loadSerial != lastLoadSerial || surfaceEpoch != lastSurfaceEpoch) {
                    // New file, a reload on a new handle, a surface attach/detach: threads were replaced.
                    // The drop counters restart with a new file, so the next sample is a baseline.
                    lastLoadSerial = loadSerial
                    lastSurfaceEpoch = surfaceEpoch
                    secondsSinceLoad = 0
                    previousDrops = null
                }
                val state = engine.state.value
                val wantedTarget = AdpfHintPolicy.targetDurationNanos(state.containerFps)

                if (session == null || AdpfHintPolicy.shouldRescan(secondsSinceLoad)) {
                    val found = readPlaybackTids()
                    if (found.isNotEmpty() && !found.contentEquals(tids)) {
                        val current = session
                        if (current == null) {
                            val created = try {
                                manager.createHintSession(found, wantedTarget)
                            } catch (e: Exception) {
                                // A thread can exit between the scan and the call; retry next second,
                                // but a call that keeps throwing means no ADPF on this device.
                                warnOnce("createHintSession", e)
                                if (++createFailures >= MAX_CREATE_FAILURES) {
                                    return markUnsupported("createHintSession threw $createFailures times: ${e.message}")
                                }
                                delay(TICK_MS)
                                secondsSinceLoad++
                                continue
                            }
                            if (created == null) return markUnsupported("createHintSession returned null")
                            session = created
                            tids = found
                            target = wantedTarget
                            Log.i(TAG, "session created: tids=${found.size} target=${wantedTarget}ns")
                        } else {
                            try {
                                current.setThreads(found)
                                Log.i(TAG, "session threads: ${tids.size} -> ${found.size}")
                                tids = found
                            } catch (e: Exception) {
                                warnOnce("setThreads (keeping the previous threads)", e)
                            }
                        }
                    }
                }

                val active = session
                if (active != null) {
                    if (wantedTarget != target) {
                        try {
                            active.updateTargetWorkDuration(wantedTarget)
                            target = wantedTarget
                        } catch (e: Exception) {
                            warnOnce("updateTargetWorkDuration", e)
                        }
                    }
                    val wantPower = AdpfHintPolicy.preferPowerEfficiency(state.hwdecCurrent)
                    if (wantPower != powerEfficient) {
                        try {
                            active.setPreferPowerEfficiency(wantPower)
                            powerEfficient = wantPower
                            Log.i(TAG, "preferPowerEfficiency=$wantPower (hwdec=${state.hwdecCurrent})")
                        } catch (e: Exception) {
                            warnOnce("setPreferPowerEfficiency", e)
                        }
                    }
                    val drops = engine.readTotalDropCount()
                    if (drops != null) {
                        val reported = AdpfHintPolicy.reportedDurationNanos(target, previousDrops, drops)
                        try {
                            active.reportActualWorkDuration(reported)
                        } catch (e: Exception) {
                            warnOnce("reportActualWorkDuration", e)
                        }
                        previousDrops = drops
                    }
                    engine.setAdpfStatus(AdpfHintPolicy.overlayText(true, true, tids.size))
                }
                delay(TICK_MS)
                secondsSinceLoad++
            }
        } finally {
            val toClose = session
            if (toClose != null) {
                withContext(NonCancellable) {
                    try {
                        toClose.close()
                        Log.i(TAG, "session closed")
                    } catch (e: Exception) {
                        Log.w(TAG, "session close failed: ${e.message}")
                    }
                }
                if (!unsupported) engine.setAdpfStatus(AdpfHintPolicy.overlayText(true, true, null))
            }
        }
    }

    /** Tids of the playback threads of this process, from /proc/self/task/<tid>/comm. Blocking (IO). */
    private fun readPlaybackTids(): IntArray {
        val dirs = File("/proc/self/task").listFiles() ?: return IntArray(0)
        val threads = ArrayList<Pair<Int, String>>(dirs.size)
        for (dir in dirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            try {
                threads.add(tid to File(dir, "comm").readText().trim())
            } catch (_: Exception) {
                // The thread exited during the scan.
            }
        }
        return AdpfHintPolicy.selectTids(threads)
    }

    companion object {
        private const val TAG = "VP-ADPF"
        private const val TICK_MS = 1_000L
        private const val MAX_CREATE_FAILURES = 3

        /** Set once a session cannot be created at all (no service, null session, repeated throws); per process. */
        @Volatile private var unsupported = false
        @Volatile private var loggedUnsupported = false
    }
}
