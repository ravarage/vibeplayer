package com.vibeplayer.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProcessExitRecord(
    val reasonCode: Int,
    val reasonName: String,
    val description: String?,
    val pssBytes: Long,
    val rssBytes: Long,
    val timestampMs: Long
)

object ExitDiagnostics {
    const val TAG = "VibeExit"
    const val PREFS_NAME = "exit_diagnostics"
    const val KEY_LAST_SEEN_TIMESTAMP = "last_seen_timestamp"

    private val _recentExits = MutableStateFlow<List<ProcessExitRecord>>(emptyList())
    val recentExits: StateFlow<List<ProcessExitRecord>> = _recentExits.asStateFlow()

    fun reasonToString(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN ($reason)"
    }

    fun formatLogMessage(record: ProcessExitRecord): String {
        val pssMb = record.pssBytes / (1024 * 1024)
        val rssMb = record.rssBytes / (1024 * 1024)
        val descStr = record.description ?: "none"
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.timestampMs))
        return "reason=${record.reasonName} (${record.reasonCode}) desc=$descStr pss=${record.pssBytes} (${pssMb}MB) rss=${record.rssBytes} (${rssMb}MB) timestamp=${record.timestampMs} ($timeStr)"
    }

    fun filterNewExits(records: List<ProcessExitRecord>, lastSeenTimestamp: Long): List<ProcessExitRecord> {
        return records.filter { it.timestampMs > lastSeenTimestamp }
    }

    fun checkAndLog(context: Context) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeenTimestamp = prefs.getLong(KEY_LAST_SEEN_TIMESTAMP, 0L)

        val exitInfoList: List<ApplicationExitInfo> = try {
            am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read historical process exit reasons: ${e.message}")
            emptyList()
        }

        val records = exitInfoList.map { info ->
            ProcessExitRecord(
                reasonCode = info.reason,
                reasonName = reasonToString(info.reason),
                description = info.description,
                pssBytes = info.pss,
                rssBytes = info.rss,
                timestampMs = info.timestamp
            )
        }
        _recentExits.value = records

        val newExits = filterNewExits(records, lastSeenTimestamp)
        for (record in newExits) {
            Log.i(TAG, formatLogMessage(record))
        }

        val maxTimestamp = records.maxOfOrNull { it.timestampMs } ?: lastSeenTimestamp
        if (maxTimestamp > lastSeenTimestamp) {
            prefs.edit().putLong(KEY_LAST_SEEN_TIMESTAMP, maxTimestamp).apply()
        }
    }
}
