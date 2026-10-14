package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugMpvOptionsTest {

    @Test
    fun `parses semicolon list and keeps commas inside values`() {
        val p = DebugMpvOptions.parse(" vd-queue-enable=yes; hwdec-extra-frames=12 ;vd-lavc-o=operating_rate=120,ndk_codec=1;")
        assertEquals(
            mapOf("vd-queue-enable" to "yes", "hwdec-extra-frames" to "12", "vd-lavc-o" to "operating_rate=120,ndk_codec=1"),
            p.options
        )
        assertEquals(emptyList<String>(), p.rejected)
    }

    @Test
    fun `unknown names and empty values are rejected`() {
        val p = DebugMpvOptions.parse("vd-queue-enabel=yes;framedrop=;=x;interpolation")
        assertEquals(emptyMap<String, String>(), p.options)
        assertEquals(listOf("vd-queue-enabel=yes", "framedrop=", "=x", "interpolation"), p.rejected)
        assertEquals(emptyMap<String, String>(), DebugMpvOptions.parse(null).options)
    }

    @Test
    fun `applied last and no default changes without them`() {
        val base = PlaybackConfig(mode = PlaybackMode.EFFICIENT, renderer = Renderer.GLES)
        val plain = base.getOptionTable()
        assertFalse(plain.containsKey("vd-queue-enable"))
        val withOpts = base.copy(debugMpvOptions = mapOf("hwdec-extra-frames" to "12", "profile" to "default")).getOptionTable()
        assertEquals("12", withOpts["hwdec-extra-frames"])
        assertEquals("default", withOpts["profile"])
        assertEquals(plain - "profile", withOpts - "profile" - "hwdec-extra-frames")
    }

    @Test
    fun `csv label has no commas`() {
        assertEquals("vd-lavc-o=a=1|b=2;framedrop=no", DebugMpvOptions.label(linkedMapOf("vd-lavc-o" to "a=1,b=2", "framedrop" to "no")))
    }
}
