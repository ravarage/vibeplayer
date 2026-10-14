package com.vibeplayer.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** P6-06: the table as data (assets/format_table.json), keyed by the Vulkan driver hash. */
class FormatTableDataTest {

    private val shipped: String = File("src/main/assets/format_table.json").readText()
    private val currentDriver = "c0b92d25178c8b21"

    @After
    fun reset() = FormatProfileTable.resetToBuiltIn()

    private fun resolve(format: VideoFormat, vulkan: Boolean = true, setting: RendererSetting = RendererSetting.AUTO) =
        FormatProfileTable.resolve(format, PlaybackMode.EFFICIENT, setting, vulkan)

    @Test
    fun `shipped asset parses with a default and the current driver table`() {
        val file = FormatProfileTable.parse(shipped)
        assertEquals(VideoFormat.entries.toSet(), file.default.keys)
        assertTrue(file.tables.containsKey(currentDriver))
        assertEquals(VideoFormat.entries.toSet(), file.tables.getValue(currentDriver).keys)
    }

    @Test
    fun `shipped table rows are the P6-08 winners`() {
        assertEquals(currentDriver, FormatProfileTable.install(shipped, currentDriver))
        resolve(VideoFormat.H264_8BIT).let {
            assertEquals(Triple(Renderer.VULKAN, "gpu-next", DecodeMode.MEDIACODEC_COPY), Triple(it.renderer, it.vo, it.decodeMode))
            assertEquals(currentDriver, it.tableSource)
        }
        resolve(VideoFormat.HEVC).let {
            assertEquals(Triple(Renderer.GLES, "gpu", DecodeMode.MEDIACODEC_ZERO_COPY), Triple(it.renderer, it.vo, it.decodeMode))
        }
        resolve(VideoFormat.AV1).let {
            assertEquals(Triple(Renderer.VULKAN, "gpu", DecodeMode.SOFTWARE), Triple(it.renderer, it.vo, it.decodeMode))
        }
        resolve(VideoFormat.VP9).let {
            assertEquals(Triple(Renderer.GLES, "gpu", DecodeMode.MEDIACODEC_ZERO_COPY), Triple(it.renderer, it.vo, it.decodeMode))
        }
        resolve(VideoFormat.H264_10BIT).let {
            assertEquals(Triple(Renderer.VULKAN, "gpu", DecodeMode.SOFTWARE), Triple(it.renderer, it.vo, it.decodeMode))
        }
        resolve(VideoFormat.OTHER).let {
            assertEquals(Triple(Renderer.GLES, "gpu", DecodeMode.SOFTWARE), Triple(it.renderer, it.vo, it.decodeMode))
        }
    }

    @Test
    fun `unknown or missing driver hash falls back to the default rows`() {
        assertEquals(FormatProfileTable.SOURCE_DEFAULT, FormatProfileTable.install(shipped, "ffffffffffffffff"))
        assertEquals(FormatProfileTable.SOURCE_DEFAULT, FormatProfileTable.install(shipped, null))
        assertEquals(FormatProfileTable.SOURCE_DEFAULT, FormatProfileTable.install(shipped, "unreadable"))
        assertEquals(Renderer.VULKAN, resolve(VideoFormat.H264_8BIT).renderer)
        assertEquals(FormatProfileTable.SOURCE_DEFAULT, resolve(VideoFormat.H264_8BIT).tableSource)
    }

    @Test
    fun `driver specific rows override the default and missing formats come from the default`() {
        val json = """
            {"version": 1,
             "default": {"rows": {
               "hevc": {"hwdec": "mediacodec", "renderer": "gles", "vo": "gpu", "reason": "d"},
               "vp9": {"hwdec": "mediacodec", "renderer": "gles", "vo": "gpu", "reason": "d"}}},
             "tables": [{"driverHash": "AAAAAAAAAAAAAAAA", "rows": {
               "hevc": {"hwdec": "mediacodec-copy", "renderer": "vulkan", "vo": "gpu-next", "reason": "new driver",
                        "gles": {"hwdec": "mediacodec", "vo": "gpu", "reason": "gles"}}}}]}
        """.trimIndent()
        assertEquals("aaaaaaaaaaaaaaaa", FormatProfileTable.install(json, "aaaaaaaaaaaaaaaa"))
        resolve(VideoFormat.HEVC).let {
            assertEquals(Renderer.VULKAN, it.renderer)
            assertEquals("gpu-next", it.vo)
            assertEquals("new driver", it.reason)
        }
        assertEquals("d", resolve(VideoFormat.VP9).reason)
        // Not in the file at all: built-in row.
        assertEquals(DecodeMode.SOFTWARE, resolve(VideoFormat.AV1).decodeMode)
        // Same driver row without Vulkan: its measured GLES values.
        resolve(VideoFormat.HEVC, vulkan = false).let {
            assertEquals(Renderer.GLES, it.renderer)
            assertEquals(DecodeMode.MEDIACODEC_ZERO_COPY, it.decodeMode)
            assertEquals("gpu", it.vo)
            assertEquals("gles", it.reason)
        }
    }

    @Test
    fun `vulkan row without vulkan uses its gles block and never gpu-next`() {
        FormatProfileTable.install(shipped, currentDriver)
        resolve(VideoFormat.H264_8BIT, vulkan = false).let {
            assertEquals(Renderer.GLES, it.renderer)
            assertEquals("gpu", it.vo)
            assertEquals(DecodeMode.MEDIACODEC_COPY, it.decodeMode) // zero-copy stalls at start (B-08)
        }
        resolve(VideoFormat.H264_8BIT, setting = RendererSetting.GLES).let {
            assertEquals(Renderer.GLES, it.renderer)
            assertEquals("gpu", it.vo)
        }
    }

    @Test
    fun `zero-copy is turned into copy-back on vulkan`() {
        FormatProfileTable.install(shipped, currentDriver)
        resolve(VideoFormat.VP9, setting = RendererSetting.VULKAN).let {
            assertEquals(Renderer.VULKAN, it.renderer)
            assertEquals(DecodeMode.MEDIACODEC_COPY, it.decodeMode)
        }
    }

    @Test
    fun `quality mode is unchanged by the table`() {
        FormatProfileTable.install(shipped, currentDriver)
        val q = FormatProfileTable.resolve(VideoFormat.VP9, PlaybackMode.QUALITY, RendererSetting.AUTO, true)
        assertEquals(Renderer.VULKAN, q.renderer)
        assertEquals("gpu-next", q.vo)
        assertEquals(DecodeMode.MEDIACODEC_COPY, q.decodeMode)
    }

    @Test
    fun `bad rows are skipped and bad files are rejected`() {
        val json = """{"version":1,"default":{"rows":{
            "hevc":{"hwdec":"bogus","renderer":"gles","vo":"gpu"},
            "vp9":{"hwdec":"mediacodec","renderer":"metal","vo":"gpu"},
            "av1":{"hwdec":"no","renderer":"vulkan","vo":"x11"},
            "nope":{"hwdec":"no","renderer":"gles","vo":"gpu"},
            "other":{"hwdec":"no","renderer":"gles","vo":"gpu"}}}}"""
        assertEquals(setOf(VideoFormat.OTHER), FormatProfileTable.parse(json).default.keys)
        assertFails { FormatProfileTable.parse("""{"version":2,"default":{"rows":{}}}""") }
        assertFails { FormatProfileTable.parse("""{"version":1,"default":{"rows":{}}}""") }
        assertFails { FormatProfileTable.parse("""{"version":1,""") }
        assertFalse(FormatProfileTable.isInstalled)
    }

    @Test
    fun `json reader handles the usual syntax`() {
        val v = JsonLite.parse("""{"a": [1, -2.5e1, true, false, null], "s": "x\"\\\né", "o": {}}""") as Map<*, *>
        assertEquals(listOf(1.0, -25.0, true, false, null), v["a"])
        assertEquals("x\"\\\né", v["s"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
        assertNull(JsonLite.parse(" null "))
        assertFails { JsonLite.parse("[1,]") }
        assertFails { JsonLite.parse("{} x") }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected an exception")
    }
}
