package com.vibeplayer

import java.util.Locale

/**
 * Single per-second sample captured during a benchmark run.
 */
data class BenchmarkSample(
    val runId: String,
    val elapsedSeconds: Int,
    val timePos: Double,
    val frameDropCount: Long,
    val decoderFrameDropCount: Long,
    val mistimedFrameCount: Long,
    val voDelayedFrameCount: Long,
    val estimatedVfFps: Double,
    val containerFps: Double,
    val displayFps: Double,
    val cpuPercent: Double,
    val perCoreCpu: String,
    val batteryCurrent: Long,
    val batteryTemp: Double
) {
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%s,%d,%.3f,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%s,%d,%.1f\n",
            runId,
            elapsedSeconds,
            timePos,
            frameDropCount,
            decoderFrameDropCount,
            mistimedFrameCount,
            voDelayedFrameCount,
            estimatedVfFps,
            containerFps,
            displayFps,
            cpuPercent,
            perCoreCpu,
            batteryCurrent,
            batteryTemp
        )
    }

    companion object {
        const val CSV_HEADER: String =
            "run_id,elapsed_seconds,time_pos,frame_drop_count,decoder_frame_drop_count,mistimed_frame_count,vo_delayed_frame_count,estimated_vf_fps,container_fps,display_fps,cpu_percent,per_core_cpu,battery_current,battery_temp\n"
    }
}

/**
 * Summary computed from per-second samples for the summary benchmark CSV.
 */
data class BenchmarkSummary(
    val timestamp: String,
    val runId: String,
    val file: String,
    val mode: String,
    val vo: String,
    val renderer: String,
    val profile: String,
    val decoder: String,
    val shader: String,
    val shaderCache: String,
    val requestedSeconds: Int,
    val actualSeconds: Int,
    val loops: Int,
    val startupDrops: Long,
    val steadyDrops: Long,
    val totalDrops: Long,
    val startupDecoderDrops: Long,
    val steadyDecoderDrops: Long,
    val totalDecoderDrops: Long,
    val mistimedFrames: Long,
    val voDelayedFrames: Long,
    val estimatedVfFps: Double,
    val containerFps: Double,
    val displayFps: Double,
    val cpuPercentAvg: Double,
    val batteryCurrentAvg: Double,
    val batteryTempStart: Double,
    val batteryTempEnd: Double,
    val charging: Boolean,
    val vulkanApiVersion: String,
    val vulkanDeviceName: String,
    val driverHash: String,
    val driverMtime: String
) {
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f,%b,%s,%s,%s,%s\n",
            timestamp,
            runId,
            file,
            mode,
            vo,
            renderer,
            profile,
            decoder,
            shader,
            shaderCache,
            requestedSeconds,
            actualSeconds,
            loops,
            startupDrops,
            steadyDrops,
            totalDrops,
            startupDecoderDrops,
            steadyDecoderDrops,
            totalDecoderDrops,
            mistimedFrames,
            voDelayedFrames,
            estimatedVfFps,
            containerFps,
            displayFps,
            cpuPercentAvg,
            batteryCurrentAvg,
            batteryTempStart,
            batteryTempEnd,
            charging,
            vulkanApiVersion,
            vulkanDeviceName,
            driverHash,
            driverMtime
        )
    }

    companion object {
        const val CSV_HEADER: String =
            "timestamp,run_id,file,mode,vo,renderer,profile,decoder,shader,shader_cache,requested_seconds,actual_seconds,loops,startup_drops,steady_drops,total_drops,startup_decoder_drops,steady_decoder_drops,total_decoder_drops,mistimed_frames,vo_delayed_frames,estimated_vf_fps,container_fps,display_fps,cpu_percent_avg,battery_current_avg,battery_temp_start,battery_temp_end,charging,vulkan_api_version,vulkan_device_name,driver_hash,driver_mtime\n"
    }
}

/**
 * Pure helper for computing benchmark summary from samples.
 */
object BenchmarkSummaryComputer {

    /**
     * Compute summary statistics from the list of samples.
     * Startup drops are the drops that occur within the first 2 seconds of playback.
     * Steady drops are all subsequent drops.
     */
    fun computeSummary(
        samples: List<BenchmarkSample>,
        timestamp: String,
        runId: String,
        file: String,
        mode: String,
        vo: String,
        renderer: String,
        profile: String,
        decoder: String,
        shader: String,
        shaderCache: String,
        requestedSeconds: Int,
        loops: Int,
        batteryTempStart: Double,
        batteryTempEnd: Double,
        charging: Boolean,
        vulkanApiVersion: String,
        vulkanDeviceName: String,
        driverHash: String,
        driverMtime: String
    ): BenchmarkSummary {
        val actualSeconds = samples.size

        if (samples.isEmpty()) {
            return BenchmarkSummary(
                timestamp = timestamp,
                runId = runId,
                file = file,
                mode = mode,
                vo = vo,
                renderer = renderer,
                profile = profile,
                decoder = decoder,
                shader = shader,
                shaderCache = shaderCache,
                requestedSeconds = requestedSeconds,
                actualSeconds = 0,
                loops = loops,
                startupDrops = 0L,
                steadyDrops = 0L,
                totalDrops = 0L,
                startupDecoderDrops = 0L,
                steadyDecoderDrops = 0L,
                totalDecoderDrops = 0L,
                mistimedFrames = 0L,
                voDelayedFrames = 0L,
                estimatedVfFps = 0.0,
                containerFps = 0.0,
                displayFps = 0.0,
                cpuPercentAvg = 0.0,
                batteryCurrentAvg = 0.0,
                batteryTempStart = batteryTempStart,
                batteryTempEnd = batteryTempEnd,
                charging = charging,
                vulkanApiVersion = vulkanApiVersion,
                vulkanDeviceName = vulkanDeviceName,
                driverHash = driverHash,
                driverMtime = driverMtime
            )
        }

        val last = samples.last()
        val totalDrops = last.frameDropCount
        val totalDecoderDrops = last.decoderFrameDropCount

        // Startup drops: frame drop count at elapsedSeconds <= 2 (after playback starts)
        val startupSample = samples.lastOrNull { it.elapsedSeconds <= 2 }
        val startupDrops = startupSample?.frameDropCount?.coerceAtMost(totalDrops) ?: totalDrops
        val steadyDrops = (totalDrops - startupDrops).coerceAtLeast(0L)

        val startupDecoderDrops = startupSample?.decoderFrameDropCount?.coerceAtMost(totalDecoderDrops) ?: totalDecoderDrops
        val steadyDecoderDrops = (totalDecoderDrops - startupDecoderDrops).coerceAtLeast(0L)

        val avgCpu = samples.map { it.cpuPercent }.average()
        val avgCurrent = samples.map { it.batteryCurrent }.average()
        val avgVfFps = samples.map { it.estimatedVfFps }.filter { it > 0.0 }.let {
            if (it.isNotEmpty()) it.average() else last.estimatedVfFps
        }

        return BenchmarkSummary(
            timestamp = timestamp,
            runId = runId,
            file = file,
            mode = mode,
            vo = vo,
            renderer = renderer,
            profile = profile,
            decoder = decoder,
            shader = shader,
            shaderCache = shaderCache,
            requestedSeconds = requestedSeconds,
            actualSeconds = actualSeconds,
            loops = loops,
            startupDrops = startupDrops,
            steadyDrops = steadyDrops,
            totalDrops = totalDrops,
            startupDecoderDrops = startupDecoderDrops,
            steadyDecoderDrops = steadyDecoderDrops,
            totalDecoderDrops = totalDecoderDrops,
            mistimedFrames = last.mistimedFrameCount,
            voDelayedFrames = last.voDelayedFrameCount,
            estimatedVfFps = avgVfFps,
            containerFps = last.containerFps,
            displayFps = last.displayFps,
            cpuPercentAvg = avgCpu,
            batteryCurrentAvg = avgCurrent,
            batteryTempStart = batteryTempStart,
            batteryTempEnd = batteryTempEnd,
            charging = charging,
            vulkanApiVersion = vulkanApiVersion,
            vulkanDeviceName = vulkanDeviceName,
            driverHash = driverHash,
            driverMtime = driverMtime
        )
    }
}
