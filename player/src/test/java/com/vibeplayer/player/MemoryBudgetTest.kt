package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {
    private val mib = 1024L * 1024L

    @Test
    fun localBudgetIsInsideTaskRange() {
        assertTrue(MemoryBudget.DEMUXER_MAX_BYTES in 32 * mib..48 * mib)
        assertTrue(MemoryBudget.DEMUXER_MAX_BACK_BYTES in 8 * mib..16 * mib)
    }

    @Test
    fun optionTableUsesNewValuesByDefault() {
        MemoryBudget.useLegacyCache = false
        val t = PlaybackConfig().getOptionTable()
        assertEquals((32 * mib).toString(), t["demuxer-max-bytes"])
        assertEquals((16 * mib).toString(), t["demuxer-max-back-bytes"])
    }

    @Test
    fun legacySwitchRestoresMpvDefaults() {
        MemoryBudget.useLegacyCache = true
        try {
            val t = PlaybackConfig().getOptionTable()
            assertEquals((150 * mib).toString(), t["demuxer-max-bytes"])
            assertEquals((50 * mib).toString(), t["demuxer-max-back-bytes"])
        } finally {
            MemoryBudget.useLegacyCache = false
        }
    }
}
