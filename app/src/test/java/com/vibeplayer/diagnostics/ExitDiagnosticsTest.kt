package com.vibeplayer.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitDiagnosticsTest {

    @Test
    fun reasonToStringMapsStandardExitReasons() {
        assertEquals("EXIT_SELF", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals("LOW_MEMORY", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_ANR))
        assertEquals("SIGNALED", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("USER_REQUESTED", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("USER_STOPPED", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals("FREEZER", ExitDiagnostics.reasonToString(ApplicationExitInfo.REASON_FREEZER))
    }

    @Test
    fun reasonToStringHandlesUnknownCode() {
        assertEquals("UNKNOWN (9999)", ExitDiagnostics.reasonToString(9999))
    }

    @Test
    fun formatLogMessageIncludesMemoryLimiterAndMemoryStats() {
        val record = ProcessExitRecord(
            reasonCode = ApplicationExitInfo.REASON_LOW_MEMORY,
            reasonName = "LOW_MEMORY",
            description = "MemoryLimiter:AnonSwap",
            pssBytes = 398458880L, // ~380 MB
            rssBytes = 429916160L, // ~410 MB
            timestampMs = 1776260000000L
        )

        val log = ExitDiagnostics.formatLogMessage(record)
        assertTrue("Expected log to contain reason=LOW_MEMORY", log.contains("reason=LOW_MEMORY (3)"))
        assertTrue("Expected log to contain MemoryLimiter:AnonSwap description", log.contains("desc=MemoryLimiter:AnonSwap"))
        assertTrue("Expected log to contain PSS bytes and MB", log.contains("pss=398458880 (380MB)"))
        assertTrue("Expected log to contain RSS bytes and MB", log.contains("rss=429916160 (410MB)"))
        assertTrue("Expected log to contain timestamp", log.contains("timestamp=1776260000000"))
    }

    @Test
    fun filterNewExitsFiltersByLastSeenTimestamp() {
        val r1 = ProcessExitRecord(
            reasonCode = 1,
            reasonName = "EXIT_SELF",
            description = null,
            pssBytes = 100_000_000L,
            rssBytes = 120_000_000L,
            timestampMs = 1000L
        )
        val r2 = ProcessExitRecord(
            reasonCode = 3,
            reasonName = "LOW_MEMORY",
            description = "MemoryLimiter:AnonSwap",
            pssBytes = 350_000_000L,
            rssBytes = 380_000_000L,
            timestampMs = 2000L
        )
        val r3 = ProcessExitRecord(
            reasonCode = 4,
            reasonName = "CRASH",
            description = "NullPointerException",
            pssBytes = 200_000_000L,
            rssBytes = 220_000_000L,
            timestampMs = 3000L
        )

        val all = listOf(r1, r2, r3)

        // When lastSeen is 0, all exits are new
        val newFromZero = ExitDiagnostics.filterNewExits(all, 0L)
        assertEquals(3, newFromZero.size)

        // When lastSeen is 2000, only r3 is new
        val newFrom2000 = ExitDiagnostics.filterNewExits(all, 2000L)
        assertEquals(listOf(r3), newFrom2000)

        // When lastSeen is 3000, no exits are new
        val newFrom3000 = ExitDiagnostics.filterNewExits(all, 3000L)
        assertTrue(newFrom3000.isEmpty())
    }
}
