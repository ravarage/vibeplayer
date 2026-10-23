package com.vibeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkDropsTest {
    @Test
    fun counterWithoutRestartIsUnchanged() {
        assertEquals(listOf(0L, 3L, 3L, 9L), accumulateAcrossResets(listOf(0L, 3L, 3L, 9L)))
    }

    @Test
    fun restartsAreSummed() {
        // 1198 drops in the first pass, restart, 202 then 685 in the second
        assertEquals(listOf(459L, 1198L, 1400L, 1883L), accumulateAcrossResets(listOf(459L, 1198L, 202L, 685L)))
    }

    @Test
    fun restartToZero() {
        assertEquals(listOf(5L, 5L, 7L), accumulateAcrossResets(listOf(5L, 0L, 2L)))
    }
}
