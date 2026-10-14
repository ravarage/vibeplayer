package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReloadPolicyTest {
    private val uri = "content://com.android.providers.media.documents/document/video%3A42"

    @Test
    fun `plain file paths and urls are reloaded as they are`() {
        assertEquals(ReloadPolicy.Plan.Same, ReloadPolicy.plan("/sdcard/Movies/a.mkv", null, canReopen = true))
        assertEquals(ReloadPolicy.Plan.Same, ReloadPolicy.plan("/sdcard/Movies/a.mkv", "/sdcard/Movies/a.mkv", canReopen = false))
        assertEquals(ReloadPolicy.Plan.Same, ReloadPolicy.plan(uri, uri, canReopen = false))
    }

    @Test
    fun `fdclose with a content identity is reopened through the resolver`() {
        assertEquals(ReloadPolicy.Plan.Reopen(uri), ReloadPolicy.plan("fdclose://57", uri, canReopen = true))
    }

    @Test
    fun `fdclose without identity or resolver cannot be reloaded`() {
        assertEquals(ReloadPolicy.Plan.Impossible, ReloadPolicy.plan("fdclose://57", null, canReopen = true))
        assertEquals(ReloadPolicy.Plan.Impossible, ReloadPolicy.plan("fdclose://57", "fdclose://57", canReopen = true))
        assertEquals(ReloadPolicy.Plan.Impossible, ReloadPolicy.plan("fdclose://57", uri, canReopen = false))
    }

    @Test
    fun `fdOf parses only fdclose paths`() {
        assertEquals(57, ReloadPolicy.fdOf("fdclose://57"))
        assertNull(ReloadPolicy.fdOf("fdclose://x"))
        assertNull(ReloadPolicy.fdOf("fdclose://-1"))
        assertNull(ReloadPolicy.fdOf("/sdcard/57"))
    }
}
