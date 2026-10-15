package com.vibeplayer.profiling

import android.os.Bundle
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger

/**
 * Pure helper and constants for Android 17 (API 37) ProfilingManager integration (P10-07).
 */
object ProfilingManagerHelper {
    const val TAG = "VP-Prof"

    /** Key for duration in milliseconds when requesting system traces via ProfilingManager. */
    const val KEY_DURATION_MS = "KEY_DURATION_MS"

    const val DEFAULT_DURATION_MS = 10_000
    const val DEFAULT_TAG = "stall_investigation"

    /**
     * Default trigger types registered for system-triggered profiling.
     * API 37 offers ANOMALY, ANR, and APP_FULLY_DRAWN.
     */
    val DEFAULT_TRIGGER_TYPES = listOf(
        ProfilingTrigger.TRIGGER_TYPE_ANOMALY,
        ProfilingTrigger.TRIGGER_TYPE_ANR,
        ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN
    )

    fun triggerTypeToString(triggerType: Int): String = when (triggerType) {
        ProfilingTrigger.TRIGGER_TYPE_NONE -> "NONE"
        ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN -> "APP_FULLY_DRAWN"
        ProfilingTrigger.TRIGGER_TYPE_ANR -> "ANR"
        ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE -> "APP_REQUEST_RUNNING_TRACE"
        ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP -> "KILL_FORCE_STOP"
        ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS -> "KILL_RECENTS"
        ProfilingTrigger.TRIGGER_TYPE_KILL_TASK_MANAGER -> "KILL_TASK_MANAGER"
        ProfilingTrigger.TRIGGER_TYPE_OOM -> "OOM"
        ProfilingTrigger.TRIGGER_TYPE_ANOMALY -> "ANOMALY"
        ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE -> "KILL_EXCESSIVE_CPU_USAGE"
        ProfilingTrigger.TRIGGER_TYPE_COLD_START -> "COLD_START"
        ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT -> "APP_COMPAT"
        else -> "UNKNOWN($triggerType)"
    }

    fun errorCodeToString(errorCode: Int): String = when (errorCode) {
        ProfilingResult.ERROR_NONE -> "ERROR_NONE"
        ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM -> "ERROR_FAILED_RATE_LIMIT_SYSTEM"
        ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS -> "ERROR_FAILED_RATE_LIMIT_PROCESS"
        ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS -> "ERROR_FAILED_PROFILING_IN_PROGRESS"
        ProfilingResult.ERROR_FAILED_EXECUTING -> "ERROR_FAILED_EXECUTING"
        ProfilingResult.ERROR_FAILED_POST_PROCESSING -> "ERROR_FAILED_POST_PROCESSING"
        ProfilingResult.ERROR_FAILED_NO_DISK_SPACE -> "ERROR_FAILED_NO_DISK_SPACE"
        ProfilingResult.ERROR_FAILED_INVALID_REQUEST -> "ERROR_FAILED_INVALID_REQUEST"
        ProfilingResult.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
        else -> "UNKNOWN($errorCode)"
    }

    fun profilingTypeToString(profilingType: Int): String = when (profilingType) {
        ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP -> "JAVA_HEAP_DUMP"
        ProfilingManager.PROFILING_TYPE_HEAP_PROFILE -> "HEAP_PROFILE"
        ProfilingManager.PROFILING_TYPE_STACK_SAMPLING -> "STACK_SAMPLING"
        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE -> "SYSTEM_TRACE"
        else -> "UNKNOWN($profilingType)"
    }

    fun formatResultLog(
        filePath: String?,
        errorCode: Int,
        errorMessage: String?,
        tag: String?,
        triggerType: Int
    ): String {
        val pathStr = filePath ?: "none"
        val errStr = errorCodeToString(errorCode)
        val msgStr = errorMessage ?: "none"
        val tagStr = tag ?: "none"
        val triggerStr = triggerTypeToString(triggerType)
        return "Profiling result: path=$pathStr errorCode=$errStr ($errorCode) errorMsg=$msgStr tag=$tagStr trigger=$triggerStr ($triggerType)"
    }

    fun decideTriggerTypes(collectEnabled: Boolean): List<Int> {
        return if (collectEnabled) DEFAULT_TRIGGER_TYPES else emptyList()
    }

    fun buildTriggers(
        triggerTypes: List<Int> = DEFAULT_TRIGGER_TYPES,
        rateLimitingPeriodHours: Int? = null
    ): List<ProfilingTrigger> {
        return triggerTypes.map { type ->
            val builder = ProfilingTrigger.Builder(type)
            if (rateLimitingPeriodHours != null) {
                builder.setRateLimitingPeriodHours(rateLimitingPeriodHours)
            }
            builder.build()
        }
    }

    fun createTraceParams(durationMs: Int = DEFAULT_DURATION_MS): Bundle {
        return Bundle().apply {
            putInt(KEY_DURATION_MS, durationMs)
        }
    }
}
