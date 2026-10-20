package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFiltersTest {

    @Test
    fun nightModeChain() {
        assertEquals("", AudioFilters.chain(nightMode = false))
        val on = AudioFilters.chain(nightMode = true)
        assertTrue(on.startsWith("@night:lavfi=[dynaudnorm="))
        assertTrue(on.endsWith("]"))
    }
}
