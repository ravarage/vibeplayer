package com.vibeplayer.ui.player

import android.util.Log
import com.vibeplayer.BuildConfig

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * P7-03: debug-only recomposition counter.
 *
 * Compose has no built-in per-composable recomposition counter that can be read from adb, so this
 * object counts how often each tracked composable body runs. [TrackRecomposition] is called at the
 * top of the composable it measures; every call is one composition (initial composition or
 * recomposition) of that composable.
 *
 * It is a no-op in release builds ([BuildConfig.DEBUG] is false): the counter map is never touched
 * and nothing is logged, so there is no cost outside debug builds.
 *
 * Read the counts from adb with the debug broadcast actions `recomp_reset` and `recomp_dump`
 * (see DebugActionReceiver); the dump is logged under tag `VP-Recomp`.
 */
object RecompositionTracker {

    private const val TAG = "VP-Recomp"

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Called from a composable body: one call == one composition of that composable. */
    fun count(name: String) {
        if (!BuildConfig.DEBUG) return
        counts.getOrPut(name) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Zeroes all counters (start of a measurement window). */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        counts.clear()
        Log.i(TAG, "reset")
    }

    /** Logs the current counts, one line per tracked composable, sorted by name. */
    fun dump(label: String = "") {
        if (!BuildConfig.DEBUG) return
        val snapshot = counts.entries
            .map { it.key to it.value.get() }
            .sortedBy { it.first }
        Log.i(TAG, "dump${if (label.isNotEmpty()) " [$label]" else ""}: ${snapshot.size} tracked composables")
        for ((name, value) in snapshot) {
            Log.i(TAG, "  $name = $value")
        }
    }
}

/**
 * P7-03: marks a composable for recomposition counting. Call it once at the top of the composable
 * body. No-op in release builds.
 *
 * Deliberately NOT a @Composable function: a composable call can be skipped by the Compose compiler
 * when its (stable) parameters are unchanged, which would hide exactly the recompositions this
 * counter is meant to observe. A plain inline call always runs whenever the enclosing composable
 * body executes.
 */
inline fun TrackRecomposition(name: String) {
    RecompositionTracker.count(name)
}

