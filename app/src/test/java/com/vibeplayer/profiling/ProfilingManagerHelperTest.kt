package com.vibeplayer.profiling

import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilingManagerHelperTest {

    @Test
    fun triggerTypeToStringMapsExpectedTriggerTypes() {
        assertEquals("NONE", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_NONE))
        assertEquals("APP_FULLY_DRAWN", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN))
        assertEquals("ANR", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_ANR))
        assertEquals("APP_REQUEST_RUNNING_TRACE", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE))
        assertEquals("KILL_FORCE_STOP", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP))
        assertEquals("KILL_RECENTS", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS))
        assertEquals("KILL_TASK_MANAGER", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_KILL_TASK_MANAGER))
        assertEquals("OOM", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_OOM))
        assertEquals("ANOMALY", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_ANOMALY))
        assertEquals("KILL_EXCESSIVE_CPU_USAGE", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE))
        assertEquals("COLD_START", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_COLD_START))
        assertEquals("APP_COMPAT", ProfilingManagerHelper.triggerTypeToString(ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT))
        assertEquals("UNKNOWN(9999)", ProfilingManagerHelper.triggerTypeToString(9999))
    }

    @Test
    fun errorCodeToStringMapsExpectedErrorCodes() {
        assertEquals("ERROR_NONE", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_NONE))
        assertEquals("ERROR_FAILED_RATE_LIMIT_SYSTEM", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM))
        assertEquals("ERROR_FAILED_RATE_LIMIT_PROCESS", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS))
        assertEquals("ERROR_FAILED_PROFILING_IN_PROGRESS", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS))
        assertEquals("ERROR_FAILED_EXECUTING", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_EXECUTING))
        assertEquals("ERROR_FAILED_POST_PROCESSING", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_POST_PROCESSING))
        assertEquals("ERROR_FAILED_NO_DISK_SPACE", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_NO_DISK_SPACE))
        assertEquals("ERROR_FAILED_INVALID_REQUEST", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_FAILED_INVALID_REQUEST))
        assertEquals("ERROR_UNKNOWN", ProfilingManagerHelper.errorCodeToString(ProfilingResult.ERROR_UNKNOWN))
        assertEquals("UNKNOWN(42)", ProfilingManagerHelper.errorCodeToString(42))
    }

    @Test
    fun profilingTypeToStringMapsExpectedProfilingTypes() {
        assertEquals("JAVA_HEAP_DUMP", ProfilingManagerHelper.profilingTypeToString(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP))
        assertEquals("HEAP_PROFILE", ProfilingManagerHelper.profilingTypeToString(ProfilingManager.PROFILING_TYPE_HEAP_PROFILE))
        assertEquals("STACK_SAMPLING", ProfilingManagerHelper.profilingTypeToString(ProfilingManager.PROFILING_TYPE_STACK_SAMPLING))
        assertEquals("SYSTEM_TRACE", ProfilingManagerHelper.profilingTypeToString(ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE))
        assertEquals("UNKNOWN(100)", ProfilingManagerHelper.profilingTypeToString(100))
    }

    @Test
    fun defaultTriggerTypesContainsExpectedTriggers() {
        val triggers = ProfilingManagerHelper.DEFAULT_TRIGGER_TYPES
        assertTrue(triggers.contains(ProfilingTrigger.TRIGGER_TYPE_ANOMALY))
        assertTrue(triggers.contains(ProfilingTrigger.TRIGGER_TYPE_ANR))
        assertTrue(triggers.contains(ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN))
        assertEquals(3, triggers.size)
    }

    @Test
    fun decideTriggerTypesReturnsDefaultWhenEnabledAndEmptyWhenDisabled() {
        val enabledTriggers = ProfilingManagerHelper.decideTriggerTypes(true)
        assertEquals(ProfilingManagerHelper.DEFAULT_TRIGGER_TYPES, enabledTriggers)

        val disabledTriggers = ProfilingManagerHelper.decideTriggerTypes(false)
        assertTrue(disabledTriggers.isEmpty())
    }

    @Test
    fun formatResultLogIncludesPathAndErrorCode() {
        val log = ProfilingManagerHelper.formatResultLog(
            filePath = "/data/user/0/com.vibeplayer/files/profile.perfetto-trace",
            errorCode = ProfilingResult.ERROR_NONE,
            errorMessage = null,
            tag = "stall_investigation",
            triggerType = ProfilingTrigger.TRIGGER_TYPE_ANR
        )
        assertTrue(log.contains("path=/data/user/0/com.vibeplayer/files/profile.perfetto-trace"))
        assertTrue(log.contains("errorCode=ERROR_NONE (0)"))
        assertTrue(log.contains("tag=stall_investigation"))
        assertTrue(log.contains("trigger=ANR (2)"))
    }

    @Test
    fun formatResultLogHandlesNullFieldsAndError() {
        val log = ProfilingManagerHelper.formatResultLog(
            filePath = null,
            errorCode = ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM,
            errorMessage = "Rate limited by system",
            tag = null,
            triggerType = ProfilingTrigger.TRIGGER_TYPE_NONE
        )
        assertTrue(log.contains("path=none"))
        assertTrue(log.contains("errorCode=ERROR_FAILED_RATE_LIMIT_SYSTEM (1)"))
        assertTrue(log.contains("errorMsg=Rate limited by system"))
        assertTrue(log.contains("tag=none"))
        assertTrue(log.contains("trigger=NONE (0)"))
    }

    @Test
    fun constantsMatchSpecification() {
        assertEquals("VP-Prof", ProfilingManagerHelper.TAG)
        assertEquals("KEY_DURATION_MS", ProfilingManagerHelper.KEY_DURATION_MS)
        assertEquals(10_000, ProfilingManagerHelper.DEFAULT_DURATION_MS)
        assertEquals("stall_investigation", ProfilingManagerHelper.DEFAULT_TAG)
    }
}
