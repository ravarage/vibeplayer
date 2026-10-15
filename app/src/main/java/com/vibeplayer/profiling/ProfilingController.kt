package com.vibeplayer.profiling

import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.util.Log
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Controller for registering ProfilingManager triggers and handling profiling results (P10-07).
 *
 * Runs callbacks on a dedicated background executor and logs with tag VP-Prof.
 */
object ProfilingController {
    private const val TAG = ProfilingManagerHelper.TAG

    private val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VP-Prof").apply { isDaemon = true }
    }

    private val resultConsumer = Consumer<ProfilingResult> { result ->
        val logMsg = ProfilingManagerHelper.formatResultLog(
            filePath = result.resultFilePath,
            errorCode = result.errorCode,
            errorMessage = result.errorMessage,
            tag = result.tag,
            triggerType = result.triggerType
        )
        Log.i(TAG, logMsg)
    }

    @Volatile
    private var isRegistered = false

    fun setCollectSystemProfilesEnabled(context: Context, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }
        val profilingManager = context.getSystemService(ProfilingManager::class.java)
        if (profilingManager == null) {
            Log.w(TAG, "ProfilingManager service not available")
            return
        }

        synchronized(this) {
            try {
                if (enabled) {
                    if (!isRegistered) {
                        profilingManager.registerForAllProfilingResults(backgroundExecutor, resultConsumer)
                        isRegistered = true
                        Log.i(TAG, "ProfilingManager: registered for all profiling results callback")
                    }
                    val triggers = ProfilingManagerHelper.buildTriggers()
                    profilingManager.addProfilingTriggers(triggers)
                    val triggerNames = triggers.map { ProfilingManagerHelper.triggerTypeToString(it.triggerType) }
                    Log.i(TAG, "ProfilingManager: registered triggers $triggerNames")
                } else {
                    profilingManager.clearProfilingTriggers()
                    if (isRegistered) {
                        profilingManager.unregisterForAllProfilingResults(resultConsumer)
                        isRegistered = false
                        Log.i(TAG, "ProfilingManager: unregistered profiling results callback")
                    }
                    Log.i(TAG, "ProfilingManager: cleared profiling triggers")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating ProfilingManager triggers (enabled=$enabled): ${e.message}")
            }
        }
    }

    fun requestSystemTrace(
        context: Context,
        durationMs: Int = ProfilingManagerHelper.DEFAULT_DURATION_MS,
        tag: String = ProfilingManagerHelper.DEFAULT_TAG,
        onResult: ((ProfilingResult) -> Unit)? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "requestProfiling not supported on SDK < 35")
            return false
        }
        val profilingManager = context.getSystemService(ProfilingManager::class.java)
        if (profilingManager == null) {
            Log.w(TAG, "ProfilingManager service not available")
            return false
        }

        return try {
            val params = ProfilingManagerHelper.createTraceParams(durationMs)
            profilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                params,
                tag,
                null,
                backgroundExecutor,
                { result ->
                    val logMsg = "Direct request result: " + ProfilingManagerHelper.formatResultLog(
                        filePath = result.resultFilePath,
                        errorCode = result.errorCode,
                        errorMessage = result.errorMessage,
                        tag = result.tag,
                        triggerType = result.triggerType
                    )
                    Log.i(TAG, logMsg)
                    onResult?.invoke(result)
                }
            )
            Log.i(TAG, "Requested ${durationMs / 1000} s system trace (tag='$tag', duration=${durationMs}ms)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "requestProfiling failed for system trace: ${e.message}")
            false
        }
    }
}
