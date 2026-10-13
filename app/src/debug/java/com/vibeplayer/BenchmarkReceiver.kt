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
        val seconds = intent.getIntExtra("seconds", 15).coerceAtLeast(3)
        val mode = intent.getStringExtra("mode") ?: "quality"
        val renderer = intent.getStringExtra("renderer") ?: "gles"
        val shader = intent.getStringExtra("shader") ?: intent.getStringExtra("shader_preset") ?: "none"

        Log.i("VP", "BenchmarkReceiver: Starting benchmark path=$path seconds=$seconds mode=$mode renderer=$renderer shader=$shader")
        BenchmarkRunner.runBenchmark(
            context = context.applicationContext,
            path = path,
            seconds = seconds,
            mode = mode,
            renderer = renderer,
            shader = shader
        )
    }
}
