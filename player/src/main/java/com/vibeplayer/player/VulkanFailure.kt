package com.vibeplayer.player

/** How far a Vulkan failure reaches, which decides how long it is remembered. */
enum class VulkanFailureScope {
    /** Vulkan cannot come up on this driver at all (no instance or device): every format is affected. */
    DRIVER,
    /** The Vulkan device exists but the VO did not start for this file (swapchain, surface, format). */
    FORMAT
}

data class VulkanFailure(
    val scope: VulkanFailureScope,
    /** Engine-side description (e.g. the VO check that fired). */
    val reason: String,
    /** The mpv log line that decided [scope], if any. */
    val evidence: String? = null
)

/**
 * Classifies a Vulkan failure from the mpv log lines of the failing handle. Only messages that
 * libplacebo / mpv print when the Vulkan instance or device cannot be created count as a driver
 * failure; everything else (swapchain, surface, a VO that simply did not configure) stays per
 * format, because a wrong driver-wide verdict disables Vulkan for every file until the driver changes.
 */
object VulkanFailureClassifier {
    /** Substrings of libplacebo src/vulkan/context.c fatal messages for instance/device creation. */
    val DRIVER_MARKERS = listOf(
        "Failed creating instance",
        "Found no suitable device",
        "Failed creating logical device",
        "Failed initializing vulkan device",
        "Vulkan device does not support all required features",
        "Missing device feature",
        // "Device API version 1.1.128 is lower than the minimum required version of 1.2.0, ..."
        "lower than the minimum"
    )

    fun classify(mpvLogLines: List<String>, reason: String): VulkanFailure {
        for (line in mpvLogLines) {
            if (DRIVER_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                return VulkanFailure(VulkanFailureScope.DRIVER, reason, line)
            }
        }
        return VulkanFailure(VulkanFailureScope.FORMAT, reason)
    }
}
