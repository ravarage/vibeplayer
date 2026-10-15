package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrOutputPolicyTest {
    @Test fun mediaFormatMapping() {
        assertEquals(HdrTransfer.PQ, HdrOutputPolicy.transferFromMediaFormat(6))
        assertEquals(HdrTransfer.HLG, HdrOutputPolicy.transferFromMediaFormat(7))
        assertNull(HdrOutputPolicy.transferFromMediaFormat(3)) // SDR video
        assertNull(HdrOutputPolicy.transferFromMediaFormat(1)) // linear
        assertNull(HdrOutputPolicy.transferFromMediaFormat(null))
    }

    @Test fun mpvGammaMapping() {
        assertEquals(HdrTransfer.PQ, HdrOutputPolicy.transferFromMpvGamma("pq"))
        assertEquals(HdrTransfer.PQ, HdrOutputPolicy.transferFromMpvGamma(" PQ "))
        assertEquals(HdrTransfer.HLG, HdrOutputPolicy.transferFromMpvGamma("hlg"))
        assertNull(HdrOutputPolicy.transferFromMpvGamma("bt.1886"))
        assertNull(HdrOutputPolicy.transferFromMpvGamma("srgb"))
        assertNull(HdrOutputPolicy.transferFromMpvGamma(""))
        assertNull(HdrOutputPolicy.transferFromMpvGamma(null))
    }

    @Test fun conditionMatrix() {
        for (setting in listOf(false, true))
            for (t in listOf(null, HdrTransfer.PQ, HdrTransfer.HLG))
                for (r in Renderer.values())
                    for (vo in listOf("gpu", "gpu-next", "mediacodec_embed")) {
                        val expected = setting && t != null && r == Renderer.VULKAN && vo == "gpu-next"
                        val d = HdrOutputPolicy.decide(setting, t, r, vo)
                        assertEquals("$setting $t $r $vo", expected, d.active)
                        if (d.active) assertEquals(t, d.transfer)
                    }
    }

    @Test fun reasons() {
        assertEquals("off (setting off)", HdrOutputPolicy.decide(false, HdrTransfer.PQ, Renderer.VULKAN, "gpu-next").overlayText)
        assertEquals("off (SDR content)", HdrOutputPolicy.decide(true, null, Renderer.VULKAN, "gpu-next").overlayText)
        assertEquals("off (renderer GLES)", HdrOutputPolicy.decide(true, HdrTransfer.PQ, Renderer.GLES, "gpu-next").overlayText)
        assertEquals("off (vo gpu)", HdrOutputPolicy.decide(true, HdrTransfer.HLG, Renderer.VULKAN, "gpu").overlayText)
        assertEquals("on (PQ)", HdrOutputPolicy.decide(true, HdrTransfer.PQ, Renderer.VULKAN, "gpu-next").overlayText)
        assertEquals("on (HLG)", HdrOutputPolicy.decide(true, HdrTransfer.HLG, Renderer.VULKAN, "gpu-next").overlayText)
    }

    @Test fun capableConfig() {
        assertTrue(HdrOutputPolicy.isCapableConfig(Renderer.VULKAN, "gpu-next"))
        assertFalse(HdrOutputPolicy.isCapableConfig(Renderer.GLES, "gpu-next"))
        assertFalse(HdrOutputPolicy.isCapableConfig(Renderer.VULKAN, "gpu"))
    }
}
