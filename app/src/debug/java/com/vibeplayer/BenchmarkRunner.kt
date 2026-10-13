package com.vibeplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.vibeplayer.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BenchmarkRunner {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    fun runBenchmark(
        context: Context,
        path: String,
        seconds: Int,
        mode: String,
        renderer: String,
        shader: String
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val app = context.applicationContext as Application

            // 1. Initial Battery & System State
            val initialBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempStart = (initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val status = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentSamples = mutableListOf<Long>()

            val initialCpuTicks = readProcessCpuTicks()
            val startTimeMs = SystemClock.elapsedRealtime()

            // 2. Track PlayerActivity instance
            var playerActivityRef: WeakReference<PlayerActivity>? = null
            val lifecycleListener = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is PlayerActivity) {
                        playerActivityRef = WeakReference(activity)
                    }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is PlayerActivity) {
                        playerActivityRef = WeakReference(activity)
                    }
                }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === playerActivityRef?.get()) {
                        playerActivityRef = null
                    }
                }
            }
            app.registerActivityLifecycleCallbacks(lifecycleListener)

            // 3. Launch PlayerActivity with benchmark flag
            val launchIntent = Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                action = Intent.ACTION_VIEW
                data = Uri.fromFile(File(path))
                putExtra("path", path)
                putExtra("benchmark", true)
                putExtra("mode", mode)
                putExtra("renderer", renderer)
                putExtra("shader", shader)
            }
            context.startActivity(launchIntent)

            // 4. Sample battery current each second
            for (i in 1..seconds) {
                delay(1000L)
                val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                currentSamples.add(currentNow)
            }

            // 5. Final Battery & System State
            val finalBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempEnd = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val avgCurrent = if (currentSamples.isNotEmpty()) currentSamples.average() else 0.0

            val finalCpuTicks = readProcessCpuTicks()
            val endTimeMs = SystemClock.elapsedRealtime()
            val elapsedSec = (endTimeMs - startTimeMs) / 1000.0

            val clkTck = try {
                android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
            } catch (_: Throwable) {
                100L
            }.coerceAtLeast(1L)
            val deltaTicks = (finalCpuTicks - initialCpuTicks).coerceAtLeast(0L)
            val cpuTimeSec = deltaTicks.toDouble() / clkTck
            val cpuPercent = if (elapsedSec > 0.0) (cpuTimeSec / elapsedSec) * 100.0 else 0.0

            // 6. Player stats
            val activity = playerActivityRef?.get()
            val stats = activity?.playerEngine?.stats?.value

            val vo = stats?.vo ?: ""
            val gpuApi = stats?.gpuApi ?: ""
            val hwdecCurrent = stats?.hwdecCurrent ?: ""
            val drops = stats?.frameDropCount ?: 0L
            val decDrops = stats?.decoderFrameDropCount ?: 0L
            val fps = stats?.fps ?: 0.0

            val vulkanDriver = readVulkanDriverVersion()
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
            val fileName = File(path).name

            // 7. Write CSV row
            val externalDir = context.getExternalFilesDir(null)
            val csvFile = File(externalDir, "benchmark.csv")
            val isNew = !csvFile.exists() || csvFile.length() == 0L
            if (isNew) {
                csvFile.parentFile?.mkdirs()
                csvFile.writeText("timestamp,file,mode,vo,gpu-api,hwdec-current,seconds,frame-drop-count,decoder-frame-drop-count,estimated-vf-fps,cpu-percent,battery-current-avg,battery-temp-start,battery-temp-end,charging,vulkan-driver-version\n")
            }

            val row = String.format(
                Locale.US,
                "%s,%s,%s,%s,%s,%s,%d,%d,%d,%.2f,%.1f,%.1f,%.1f,%.1f,%b,%s\n",
                timestamp,
                fileName,
                mode,
                vo,
                gpuApi,
                hwdecCurrent,
                seconds,
                drops,
                decDrops,
                fps,
                cpuPercent,
                avgCurrent,
                tempStart,
                tempEnd,
                isCharging,
                vulkanDriver
            )
            csvFile.appendText(row)
            Log.i("VP", "BENCHMARK-CSV: $row")
            Log.i("VP", "Benchmark completed successfully. Row appended to ${csvFile.absolutePath}")

            // 8. Cleanup and finish activity
            app.unregisterActivityLifecycleCallbacks(lifecycleListener)
            activity?.finish()
        }
    }

    private fun readProcessCpuTicks(): Long {
        try {
            val stat = File("/proc/self/stat").readText()
            val parts = stat.split("\\s+".toRegex())
            if (parts.size > 14) {
                val utime = parts[13].toLongOrNull() ?: 0L
                val stime = parts[14].toLongOrNull() ?: 0L
                return utime + stime
            }
        } catch (e: Throwable) {
            Log.w("VP", "readProcessCpuTicks failed: ${e.message}")
        }
        return 0L
    }

    private fun readVulkanDriverVersion(): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "-s", "mpv"))
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.contains("driver", ignoreCase = true) || line.contains("vulkan", ignoreCase = true)) {
                        val match = Regex("""(?:driver(?:Version)?|version)[:=\s]+([0-9a-zA-Z._-]+)""", RegexOption.IGNORE_CASE).find(line)
                        if (match != null) {
                            return match.groupValues[1]
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("VP", "readVulkanDriverVersion failed: ${e.message}")
        }
        return "unknown"
    }
}
