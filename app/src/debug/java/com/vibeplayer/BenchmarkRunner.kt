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
import com.vibeplayer.player.MpvNative
import com.vibeplayer.player.PlaybackConfig
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BenchmarkRunner {

    // Default, not Main: the loop calls blocking mpv getters every second; on the main thread a busy mpv
    // core (e.g. audio focus change on an incoming call) caused an ANR (2026-10-14). UI calls switch to Main.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    fun runBenchmark(
        context: Context,
        path: String,
        seconds: Int,
        mode: String,
        renderer: String,
        vo: String? = null,
        shader: String = "none",
        cold: Boolean = false,
        profile: String = "default",
        decoder: String = "auto",
        driverHash: String? = null,
        driverMtime: String? = null,
        runId: String? = null,
        /** Debug A/B: extra mpv options (see DebugMpvOptions), recorded in the CSV profile column. */
        mpvOpts: String? = null
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val app = context.applicationContext as Application

            val actualRunId = runId?.takeIf { it.isNotBlank() }
                ?: (SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + (1000..9999).random())

            // 1. Manage Shader Cache (Cold vs Warm)
            val cacheState = if (cold) "cold" else "warm"
            val shaderCacheDir = File(context.cacheDir, "gpu-shader-cache")
            if (cold) {
                try {
                    shaderCacheDir.deleteRecursively()
                    Log.i("VP", "Benchmark: Cleared shader cache dir (cold start): ${shaderCacheDir.absolutePath}")
                } catch (e: Throwable) {
                    Log.w("VP", "Failed to clear shader cache: ${e.message}")
                }
            }
            shaderCacheDir.mkdirs()
            PlaybackConfig.defaultShaderCacheDir = shaderCacheDir.absolutePath

            // 2. Driver identity for /vendor/lib64/hw/vulkan.adreno.so
            val (resolvedDriverHash, resolvedDriverMtime) = readDriverFileIdentity(driverHash, driverMtime)

            // 3. Initial Battery State
            val initialBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempStart = (initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val status = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            // 4. Track PlayerActivity instance
            var playerActivityRef: WeakReference<PlayerActivity>? = null
            val lifecycleListener = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is PlayerActivity) {
                        playerActivityRef = WeakReference(activity)
                        activity.viewModel.isBenchmarkRun = true
                    }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is PlayerActivity) {
                        playerActivityRef = WeakReference(activity)
                        activity.viewModel.isBenchmarkRun = true
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

            // 5. Launch PlayerActivity
            val launchIntent = Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                action = Intent.ACTION_VIEW
                data = Uri.fromFile(File(path))
                putExtra("path", path)
                putExtra("benchmark", true)
                putExtra("mode", mode)
                putExtra("renderer", renderer)
                if (!vo.isNullOrBlank()) {
                    putExtra("probe_vo", vo)
                    putExtra("probe_renderer", renderer)
                }
                putExtra("shader", shader)
                putExtra("fast_profile", profile.equals("fast", ignoreCase = true))
                putExtra("profile", profile)
                putExtra("decoder", decoder)
                if (!mpvOpts.isNullOrBlank()) putExtra("mpv_opts", mpvOpts)
            }
            context.startActivity(launchIntent)

            // 6. Wait for PlayerActivity to initialize and get duration
            var activity: PlayerActivity? = null
            for (w in 1..60) {
                delay(100L)
                activity = playerActivityRef?.get()
                if (activity != null) {
                    activity.viewModel.isBenchmarkRun = true
                    if (activity.playerEngine.state.value.durationMs > 0L) {
                        break
                    }
                }
            }

            val engine = activity?.playerEngine
            val durationMs = engine?.state?.value?.durationMs ?: 0L
            val durationSec = durationMs / 1000.0

            // 7. Loop policy: loop when requested seconds > clip duration
            val willLoop = durationSec > 0.0 && seconds > durationSec
            val targetRunSeconds = if (willLoop) {
                seconds
            } else if (durationSec > 0.0) {
                minOf(seconds, (durationSec - 3.0).toInt().coerceAtLeast(1))
            } else {
                seconds
            }

            if (willLoop && engine != null) {
                val h = getMpvHandle(engine)
                if (h != 0L) {
                    MpvNative.setPropertyString(h, "loop-file", "inf")
                    Log.i("VP", "Benchmark: Loop enabled (requested $seconds s > duration $durationSec s)")
                }
            }

            // 8. Prepare CSV Files
            val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
            val samplesFile = File(externalDir, "benchmark_samples.csv")
            val summaryFile = File(externalDir, "benchmark.csv")
            samplesFile.parentFile?.mkdirs()

            if (!samplesFile.exists() || samplesFile.length() == 0L) {
                samplesFile.writeText(BenchmarkSample.CSV_HEADER)
            }
            if (!summaryFile.exists() || summaryFile.length() == 0L) {
                summaryFile.writeText(BenchmarkSummary.CSV_HEADER)
            }

            // 9. Sampling loop (1 sample per second)
            val samples = mutableListOf<BenchmarkSample>()
            var loopsCount = 0
            var prevTimePos = -1.0
            var prevCpuTicks = readProcessCpuTicks()
            var prevCpuElapsedMs = SystemClock.elapsedRealtime()
            val prevCoreTicks = mutableMapOf<String, Pair<Long, Long>>()
            val clkTck = readClkTck()

            for (sec in 1..targetRunSeconds) {
                delay(1000L)

                val nowElapsedMs = SystemClock.elapsedRealtime()
                val nowCpuTicks = readProcessCpuTicks()
                val deltaTicks = (nowCpuTicks - prevCpuTicks).coerceAtLeast(0L)
                val deltaElapsedSec = (nowElapsedMs - prevCpuElapsedMs) / 1000.0
                val cpuPercent = if (deltaElapsedSec > 0.0) {
                    ((deltaTicks.toDouble() / clkTck) / deltaElapsedSec) * 100.0
                } else {
                    0.0
                }
                prevCpuTicks = nowCpuTicks
                prevCpuElapsedMs = nowElapsedMs

                val perCoreCpu = readPerCoreCpuLoad(prevCoreTicks)
                val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val batIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val currentTemp = (batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

                val currentActivity = playerActivityRef?.get()
                val currentEngine = currentActivity?.playerEngine
                val handle = if (currentEngine != null) getMpvHandle(currentEngine) else 0L

                val timePos = if (handle != 0L) MpvNative.getPropertyDouble(handle, "time-pos") ?: 0.0 else 0.0
                val drops = if (handle != 0L) MpvNative.getPropertyLong(handle, "frame-drop-count") ?: 0L else 0L
                val decDrops = if (handle != 0L) MpvNative.getPropertyLong(handle, "decoder-frame-drop-count") ?: 0L else 0L
                val mistimed = if (handle != 0L) MpvNative.getPropertyLong(handle, "mistimed-frame-count") ?: 0L else 0L
                val delayed = if (handle != 0L) MpvNative.getPropertyLong(handle, "vo-delayed-frame-count") ?: 0L else 0L
                val vfFps = if (handle != 0L) MpvNative.getPropertyDouble(handle, "estimated-vf-fps") ?: 0.0 else 0.0
                val containerFps = if (handle != 0L) MpvNative.getPropertyDouble(handle, "container-fps") ?: 0.0 else 0.0
                val displayFps = if (handle != 0L) {
                    MpvNative.getPropertyDouble(handle, "display-fps")
                        ?: MpvNative.getPropertyDouble(handle, "estimated-display-fps")
                        ?: 0.0
                } else {
                    0.0
                }

                // Loop detection via time-pos wrap
                if (prevTimePos > 0.0 && timePos < prevTimePos - 2.0) {
                    loopsCount++
                }
                prevTimePos = timePos

                val sample = BenchmarkSample(
                    runId = actualRunId,
                    elapsedSeconds = sec,
                    timePos = timePos,
                    frameDropCount = drops,
                    decoderFrameDropCount = decDrops,
                    mistimedFrameCount = mistimed,
                    voDelayedFrameCount = delayed,
                    estimatedVfFps = vfFps,
                    containerFps = containerFps,
                    displayFps = displayFps,
                    cpuPercent = cpuPercent,
                    perCoreCpu = perCoreCpu,
                    batteryCurrent = currentNow,
                    batteryTemp = currentTemp
                )
                samples.add(sample)
                samplesFile.appendText(sample.toCsvRow())
                Log.d("VP", "BENCHMARK-SAMPLE: ${sample.toCsvRow().trim()}")
            }

            // 10. Final Battery State & Driver Info from logs
            val finalBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempEnd = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val actualRenderer = if (renderer.equals("auto", ignoreCase = true)) {
                activity?.playerEngine?.playbackConfig?.renderer?.name?.lowercase() ?: "auto"
            } else renderer
            val (vulkanApiVersion, vulkanDeviceName) = readVulkanLogIdentity(actualRenderer)

            val resolvedVo = vo ?: if (mode.equals("quality", ignoreCase = true)) "gpu-next" else (activity?.playerEngine?.playbackConfig?.vo ?: "gpu")
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
            val fileName = File(path).name

            // 11. Compute Summary Row from Samples
            val summary = BenchmarkSummaryComputer.computeSummary(
                samples = samples,
                timestamp = timestamp,
                runId = actualRunId,
                file = fileName,
                mode = mode,
                vo = resolvedVo,
                renderer = actualRenderer,
                profile = com.vibeplayer.player.DebugMpvOptions.parse(mpvOpts).options
                    .takeIf { it.isNotEmpty() }
                    ?.let { "$profile+${com.vibeplayer.player.DebugMpvOptions.label(it)}" } ?: profile,
                decoder = decoder,
                shader = shader,
                shaderCache = cacheState,
                requestedSeconds = seconds,
                loops = loopsCount,
                batteryTempStart = tempStart,
                batteryTempEnd = tempEnd,
                charging = isCharging,
                vulkanApiVersion = vulkanApiVersion,
                vulkanDeviceName = vulkanDeviceName,
                driverHash = resolvedDriverHash,
                driverMtime = resolvedDriverMtime
            )

            summaryFile.appendText(summary.toCsvRow())
            Log.i("VP", "BENCHMARK-SUMMARY: ${summary.toCsvRow().trim()}")
            Log.i("VP", "Benchmark completed. Samples: ${samplesFile.absolutePath}, Summary: ${summaryFile.absolutePath}")

            // 12. Cleanup
            app.unregisterActivityLifecycleCallbacks(lifecycleListener)
            kotlinx.coroutines.withContext(Dispatchers.Main) { activity?.finish() }
            endBenchmarkMode()
        }
    }

    /**
     * The benchmark flag is process-wide: clear it when the run is over so a later normal open is not
     * treated as a benchmark (no history, no notification). Not before the service is gone: its onUnbind
     * relies on the flag to stop instead of playing on in the background.
     */
    private suspend fun endBenchmarkMode() {
        for (i in 1..50) {
            if (com.vibeplayer.playback.PlaybackService.currentInstance == null) break
            delay(100L)
        }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            com.vibeplayer.playback.PlaybackService.benchmarkRun = false
        }
        Log.i("VP", "Benchmark: run over, benchmark mode off (service gone: ${com.vibeplayer.playback.PlaybackService.currentInstance == null})")
    }

    private fun getMpvHandle(engine: PlayerEngine): Long {
        return try {
            val field = PlayerEngine::class.java.getDeclaredField("handle")
            field.isAccessible = true
            field.getLong(engine)
        } catch (_: Throwable) {
            0L
        }
    }

    private fun readClkTck(): Long {
        return try {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
        } catch (_: Throwable) {
            100L
        }.coerceAtLeast(1L)
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

    /**
     * Reads per-core CPU load from /proc/stat if accessible.
     * On Android 17, access to /proc/stat is typically restricted by SELinux for apps;
     * if unreadable, returns empty string as required.
     */
    private fun readPerCoreCpuLoad(prevCoreTicks: MutableMap<String, Pair<Long, Long>>): String {
        try {
            val file = File("/proc/stat")
            if (!file.exists() || !file.canRead()) {
                return ""
            }
            val lines = file.readLines()
            val coreLoads = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("cpu") && line.length > 3 && line[3].isDigit()) {
                    val parts = line.trim().split("\\s+".toRegex())
                    val coreName = parts[0]
                    val user = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val nice = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val system = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                    val idle = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                    val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0L

                    val total = user + nice + system + idle + iowait + irq + softirq + steal
                    val busy = total - idle - iowait

                    val prev = prevCoreTicks[coreName]
                    if (prev != null) {
                        val deltaTotal = total - prev.first
                        val deltaBusy = busy - prev.second
                        val pct = if (deltaTotal > 0L) (deltaBusy.toDouble() / deltaTotal) * 100.0 else 0.0
                        coreLoads.add(String.format(Locale.US, "%s:%.0f%%", coreName, pct))
                    }
                    prevCoreTicks[coreName] = Pair(total, busy)
                }
            }
            return coreLoads.joinToString(";")
        } catch (e: Throwable) {
            // Android SELinux restricts /proc/stat on Android 8+
            return ""
        }
    }

    /**
     * Computes the SHA-256 of /vendor/lib64/hw/vulkan.adreno.so and its last-modified date.
     * If the file is not readable by the app, falls back to the extras passed on the command line.
     */
    private fun readDriverFileIdentity(overrideHash: String?, overrideMtime: String?): Pair<String, String> {
        try {
            val file = File("/vendor/lib64/hw/vulkan.adreno.so")
            if (file.exists() && file.canRead()) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val hashHex = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
                val mtime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(file.lastModified()))
                Log.i("VP", "Benchmark: Driver file hashed directly: $hashHex mtime=$mtime")
                return Pair(hashHex, mtime)
            }
        } catch (e: Throwable) {
            Log.w("VP", "Benchmark: /vendor/lib64/hw/vulkan.adreno.so not readable: ${e.message}")
        }

        val hash = overrideHash?.takeIf { it.isNotBlank() } ?: "unreadable"
        val mtime = overrideMtime?.takeIf { it.isNotBlank() } ?: "unknown"
        Log.i("VP", "Benchmark: Using command-line driver identity: hash=$hash mtime=$mtime")
        return Pair(hash, mtime)
    }

    /**
     * Extracts Vulkan API version and device name from libplacebo / mpv logs.
     */
    private fun readVulkanLogIdentity(renderer: String): Pair<String, String> {
        if (!renderer.equals("vulkan", ignoreCase = true)) {
            return Pair("N/A (GLES)", "N/A (GLES)")
        }

        var apiVersion = "unknown"
        var deviceName = "unknown"

        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "-s", "mpv"))
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val apiMatch = Regex("""API version:\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(line)
                    if (apiMatch != null) {
                        apiVersion = apiMatch.groupValues[1]
                    }

                    val deviceMatch = Regex("""Device Name:\s*(.+)""", RegexOption.IGNORE_CASE).find(line)
                    if (deviceMatch != null) {
                        deviceName = deviceMatch.groupValues[1].trim()
                    }

                    val gpuMatch = Regex("""GPU \d+:\s*(.+?)\s+v([0-9.]+)""", RegexOption.IGNORE_CASE).find(line)
                    if (gpuMatch != null) {
                        if (deviceName == "unknown") deviceName = gpuMatch.groupValues[1].trim()
                        if (apiVersion == "unknown") apiVersion = gpuMatch.groupValues[2].trim()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("VP", "readVulkanLogIdentity failed: ${e.message}")
        }

        return Pair(apiVersion, deviceName)
    }
}
