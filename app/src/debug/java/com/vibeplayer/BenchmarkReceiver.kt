package com.vibeplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only broadcast receiver to trigger automated performance benchmarks via adb.
 */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: "/sdcard/Movies/vibeplayer-test/ichiban.mp4"
        val seconds = intent.getIntExtra("seconds", 15).coerceAtLeast(1)
        val mode = intent.getStringExtra("mode") ?: "quality"
        val renderer = intent.getStringExtra("renderer") ?: "auto"
        val vo = intent.getStringExtra("vo")
        val shader = intent.getStringExtra("shader") ?: intent.getStringExtra("shader_preset") ?: "none"
        val cold = intent.getBooleanExtra("cold", false) ||
            intent.getStringExtra("cache")?.equals("cold", ignoreCase = true) == true ||
            intent.getStringExtra("shader_cache")?.equals("cold", ignoreCase = true) == true
        val profile = intent.getStringExtra("profile") ?: if (intent.getBooleanExtra("fast_profile", false)) "fast" else "default"
        val decoder = intent.getStringExtra("decoder") ?: "auto"
        val driverHash = intent.getStringExtra("driver_hash")
        val driverMtime = intent.getStringExtra("driver_mtime")
        val runId = intent.getStringExtra("run_id")

        Log.i("VP", "BenchmarkReceiver: Starting benchmark path=$path seconds=$seconds mode=$mode renderer=$renderer vo=$vo shader=$shader cold=$cold profile=$profile decoder=$decoder")
        BenchmarkRunner.runBenchmark(
            context = context.applicationContext,
            path = path,
            seconds = seconds,
            mode = mode,
            renderer = renderer,
            vo = vo,
            shader = shader,
            cold = cold,
            profile = profile,
            decoder = decoder,
            driverHash = driverHash,
            driverMtime = driverMtime,
            runId = runId
        )
    }
}
