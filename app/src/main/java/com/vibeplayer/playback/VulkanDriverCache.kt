package com.vibeplayer.playback

import android.content.Context
import android.os.Build
import android.util.Log
import com.vibeplayer.player.VideoFormat
import java.io.File

/**
 * Pure decision policy for Vulkan availability per driver build and format.
 */
object VulkanDriverPolicy {
    fun shouldOfferVulkan(
        libMpvHasVulkan: Boolean,
        driverFailed: Boolean,
        format: VideoFormat,
        formatFailures: Set<String>
    ): Boolean {
        if (!libMpvHasVulkan) return false
        if (driverFailed) return false
        if (formatFailures.contains(format.formatKey)) return false
        return true
    }

    fun computeDriverKey(
        buildFingerprint: String,
        soMtime: Long
    ): String {
        return "${buildFingerprint}_$soMtime"
    }
}

/**
 * Cache for Vulkan availability and remembered format failures per driver build.
 *
 * Keyed by Build.FINGERPRINT + vulkan.adreno.so mtime, NEVER checking the 1.1 system feature flag.
 * If the driver changes between sessions, cached failures are cleared to re-evaluate on the new driver.
 */
object VulkanDriverCache {

    private const val PREFS_NAME = "vulkan_driver_cache"
    private const val KEY_DRIVER_FINGERPRINT = "driver_fingerprint"
    private const val KEY_DRIVER_FAILED = "driver_failed"
    private const val KEY_FORMAT_FAILURES = "format_failures"

    private val VULKAN_SO_PATHS = listOf(
        "/vendor/lib64/hw/vulkan.adreno.so",
        "/vendor/lib/hw/vulkan.adreno.so",
        "/system/vendor/lib64/hw/vulkan.adreno.so",
        "/system/lib64/hw/vulkan.adreno.so"
    )

    fun getVulkanSoMtime(): Long {
        for (path in VULKAN_SO_PATHS) {
            val file = File(path)
            if (file.exists()) {
                val mtime = file.lastModified()
                if (mtime > 0L) return mtime
            }
        }
        return 0L
    }

    fun computeDriverKey(
        buildFingerprint: String = Build.FINGERPRINT ?: "unknown",
        soMtime: Long = getVulkanSoMtime()
    ): String {
        return VulkanDriverPolicy.computeDriverKey(buildFingerprint, soMtime)
    }

    /**
     * Checks if Vulkan is available for the given [format] on this driver build.
     *
     * Skips Vulkan if:
     * - libmpv was not built with Vulkan support
     * - The Vulkan driver completely failed to initialize on this driver build
     * - This format has a remembered failure on this driver build
     */
    fun isVulkanAvailable(
        context: Context,
        format: VideoFormat,
        libMpvHasVulkan: Boolean
    ): Boolean {
        if (!libMpvHasVulkan) {
            return false
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = computeDriverKey()
        val cachedKey = prefs.getString(KEY_DRIVER_FINGERPRINT, null)

        if (cachedKey != currentKey) {
            // New driver build! Reset cache to probe fresh on this build
            Log.i("VP", "VulkanDriverCache: new driver build detected ($cachedKey -> $currentKey), resetting cache")
            prefs.edit()
                .putString(KEY_DRIVER_FINGERPRINT, currentKey)
                .putBoolean(KEY_DRIVER_FAILED, false)
                .putStringSet(KEY_FORMAT_FAILURES, emptySet())
                .apply()
            return true
        }

        val driverFailed = prefs.getBoolean(KEY_DRIVER_FAILED, false)
        val formatFailures = prefs.getStringSet(KEY_FORMAT_FAILURES, emptySet()) ?: emptySet()
        return VulkanDriverPolicy.shouldOfferVulkan(
            libMpvHasVulkan = libMpvHasVulkan,
            driverFailed = driverFailed,
            format = format,
            formatFailures = formatFailures
        )
    }

    /**
     * Records a failure for a specific format (or global driver failure) on this driver build.
     */
    fun recordVulkanFailure(
        context: Context,
        format: VideoFormat?,
        isGlobalDriverFailure: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = computeDriverKey()
        val editor = prefs.edit().putString(KEY_DRIVER_FINGERPRINT, currentKey)

        if (isGlobalDriverFailure) {
            Log.e("VP", "VulkanDriverCache: recording global Vulkan driver failure on $currentKey")
            editor.putBoolean(KEY_DRIVER_FAILED, true)
        }

        if (format != null) {
            val formatFailures = prefs.getStringSet(KEY_FORMAT_FAILURES, emptySet())?.toMutableSet() ?: mutableSetOf()
            formatFailures.add(format.formatKey)
            Log.e("VP", "VulkanDriverCache: recording Vulkan failure for format ${format.label} on $currentKey")
            editor.putStringSet(KEY_FORMAT_FAILURES, formatFailures)
        }

        editor.apply()
    }

    fun getFormatFailures(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FORMAT_FAILURES, emptySet()) ?: emptySet()
    }

    fun clearCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
