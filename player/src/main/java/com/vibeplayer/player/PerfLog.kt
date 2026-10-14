package com.vibeplayer.player

import android.util.Log

/**
 * P7-02: one release-safe `VP-Perf` line per start-up milestone, stamped with the device epoch clock
 * so tools/start_time.py (logcat -v epoch) and a human can line them up with the system's own
 * ActivityTaskManager "START" line. Cheap: one string and one Log call, no I/O.
 */
object PerfLog {
    fun mark(event: String) {
        Log.i("VP-Perf", "$event at ${System.currentTimeMillis()}")
    }
}
