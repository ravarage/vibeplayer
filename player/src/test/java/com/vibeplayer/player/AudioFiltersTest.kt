package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun flatOrOffEqualizerAddsNothing() {
        assertNull(Equalizer(enabled = true).lavfi())
        assertNull(Equalizer(enabled = false, gains = listOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0)).lavfi())
        assertEquals("", AudioFilters.chain(false, Equalizer(enabled = false, gains = listOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0))))
    }

    @Test
    fun equalizerGraphHasPreGainAndOnlyNonZeroBands() {
        val eq = Equalizer(enabled = true, gains = listOf(6, 0, 0, 0, 0, 0, 0, 0, 0, -3))
        assertEquals("volume=-6dB,equalizer=f=31:t=o:w=1:g=6,equalizer=f=16000:t=o:w=1:g=-3", eq.lavfi())
        // cuts only: no pre-gain
        assertEquals("equalizer=f=1000:t=o:w=1:g=-4", Equalizer(true, listOf(0, 0, 0, 0, 0, -4, 0, 0, 0, 0)).lavfi())
    }

    @Test
    fun equalizerThenNightMode() {
        val eq = Equalizer(enabled = true, gains = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 2))
        assertEquals("@eq:lavfi=[volume=-2dB,equalizer=f=16000:t=o:w=1:g=2]," + AudioFilters.NIGHT_MODE, AudioFilters.chain(true, eq))
    }

    @Test
    fun gainsAreClampedAndParsed() {
        assertEquals(listOf(12, -12, 0, 0, 0, 0, 0, 0, 0, 0), Equalizer(true, listOf(20, -30)).normalized().gains)
        assertEquals(Equalizer.FLAT, Equalizer.parseGains(null))
        assertEquals(Equalizer.FLAT, Equalizer.parseGains("1,2,3"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 12), Equalizer.parseGains("1,2,3,4,5,6,7,8,9,99"))
        assertEquals("1,2", Equalizer.encodeGains(listOf(1, 2)))
    }

    @Test
    fun presetNames() {
        assertEquals("Bass boost", Equalizer(true, listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)).presetName())
        assertNull(Equalizer(true, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0)).presetName())
        assertEquals("16k", Equalizer.bandLabel(16000))
        assertEquals("125", Equalizer.bandLabel(125))
    }
}
