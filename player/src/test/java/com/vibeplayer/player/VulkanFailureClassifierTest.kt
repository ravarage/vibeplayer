package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VulkanFailureClassifierTest {
    private fun classify(vararg lines: String) =
        VulkanFailureClassifier.classify(lines.toList(), "Vulkan VO failed to initialise")

    @Test
    fun `no suitable device is a driver failure`() {
        val line = "E/mpv     (12345): [vo/gpu-next/libplacebo] Found no suitable device, giving up."
        val f = classify(
            "I/mpv     (12345): [vo/gpu-next/libplacebo] Probing for vulkan devices:",
            line,
            "E/mpv     (12345): [vo/gpu-next] Failed initializing any suitable GPU context!",
            "E/mpv     (12345): [vo] Failed initializing any suitable GPU context!"
        )
        assertEquals(VulkanFailureScope.DRIVER, f.scope)
        assertEquals(line, f.evidence)
    }

    @Test
    fun `instance and device creation failures are driver failures`() {
        listOf(
            "E/mpv     (1): [vo/gpu-next/libplacebo] Failed creating instance: VK_ERROR_INCOMPATIBLE_DRIVER",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Failed creating logical device!",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Failed initializing vulkan device",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Vulkan device does not support all required features!",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Missing device feature: timelineSemaphore",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Device API version 1.1.128 is lower than the minimum required version of 1.2.0, cannot proceed!"
        ).forEach { assertEquals(it, VulkanFailureScope.DRIVER, classify(it).scope) }
    }

    @Test
    fun `swapchain surface and generic context errors stay per format`() {
        val f = classify(
            "E/mpv     (1): [vo/gpu-next/libplacebo] Failed creating swapchain: VK_ERROR_INITIALIZATION_FAILED",
            "E/mpv     (1): [vo/gpu-next/vulkan] Failed creating Android surface",
            "E/mpv     (1): [vo/gpu-next/libplacebo] Queue family does not support surface presentation!",
            "E/mpv     (1): [vo/gpu-next] Failed initializing any suitable GPU context!",
            "E/mpv     (1): [vo/gpu-next] Failed presenting frame!"
        )
        assertEquals(VulkanFailureScope.FORMAT, f.scope)
        assertNull(f.evidence)
    }

    @Test
    fun `no log lines means per format`() {
        val f = classify()
        assertEquals(VulkanFailureScope.FORMAT, f.scope)
        assertEquals("Vulkan VO failed to initialise", f.reason)
    }
}
