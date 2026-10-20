package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeBoostTest {

    @Test
    fun noBoostIsThePlainMediaScale() {
        assertEquals(0, VolumeBoost.boostSteps(15, 100))
        assertEquals(15, VolumeBoost.totalLevels(15, 100))
        assertEquals(VolumeBoost.Split(15, 100.0), VolumeBoost.split(20, 15, 100))
        assertEquals(15, VolumeBoost.level(15, 180.0, 15, 100))
        assertEquals(100, VolumeBoost.percent(15, 15, 100))
    }

    @Test
    fun boostAddsLevelsAboveTheMediaMaximum() {
        assertEquals(15, VolumeBoost.boostSteps(15, 200))
        assertEquals(8, VolumeBoost.boostSteps(15, 150))
        assertEquals(30, VolumeBoost.totalLevels(15, 200))
        assertEquals(VolumeBoost.Split(7, 100.0), VolumeBoost.split(7, 15, 200))
        assertEquals(VolumeBoost.Split(15, 100.0), VolumeBoost.split(15, 15, 200))
        assertEquals(VolumeBoost.Split(15, 200.0), VolumeBoost.split(30, 15, 200))
        assertEquals(VolumeBoost.Split(15, 200.0), VolumeBoost.split(99, 15, 200))
        assertEquals(150.0, VolumeBoost.split(22, 15, 200).mpvVolume, 4.0)
    }

    @Test
    fun levelIsTheInverseOfSplit() {
        for (max in VolumeBoost.CHOICES) {
            for (l in 0..VolumeBoost.totalLevels(15, max)) {
                val s = VolumeBoost.split(l, 15, max)
                assertEquals("max=$max level=$l", l, VolumeBoost.level(s.systemVolume, s.mpvVolume, 15, max))
            }
        }
    }

    @Test
    fun percentShowsMediaThenBoost() {
        assertEquals(0, VolumeBoost.percent(0, 15, 200))
        assertEquals(53, VolumeBoost.percent(8, 15, 200))
        assertEquals(100, VolumeBoost.percent(15, 15, 200))
        assertEquals(200, VolumeBoost.percent(30, 15, 200))
        assertEquals(0, VolumeBoost.percent(5, 0, 200))
    }

    @Test
    fun normalizeMax() {
        assertEquals(150, VolumeBoost.normalizeMax(150))
        assertEquals(100, VolumeBoost.normalizeMax(175))
        assertEquals(100, VolumeBoost.normalizeMax(0))
    }
}
