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

    /** What the cache remembers; everything in it belongs to [driverKey]. */
    data class Stored(
        val driverKey: String?,
        val driverFailed: Boolean,
        val formatFailures: Set<String>
    )

    /** The stored state as it applies to [currentKey]: failures of another driver build are dropped. */
    fun forDriver(stored: Stored, currentKey: String): Stored =
        if (stored.driverKey == currentKey) stored else Stored(currentKey, false, emptySet())

    /**
     * State after a failure on [currentKey]. A driver-wide failure (no Vulkan instance or device)
     * disables Vulkan for every format of this driver build; a per-format one only for [formatKey].
     */
    fun afterFailure(
        stored: Stored,
        currentKey: String,
        formatKey: String?,
        isGlobalDriverFailure: Boolean
    ): Stored {
        val base = forDriver(stored, currentKey)
        return base.copy(
            driverFailed = base.driverFailed || isGlobalDriverFailure,
            formatFailures = if (formatKey != null) base.formatFailures + formatKey else base.formatFailures
        )
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

    val VULKAN_SO_PATHS = listOf(
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
    @Synchronized // with recordVulkanFailure: a driver-change reset here must not overwrite a failure recorded meanwhile
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
    @Synchronized
    fun recordVulkanFailure(
        context: Context,
        format: VideoFormat?,
        isGlobalDriverFailure: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = computeDriverKey()
        val stored = VulkanDriverPolicy.Stored(
            driverKey = prefs.getString(KEY_DRIVER_FINGERPRINT, null),
            driverFailed = prefs.getBoolean(KEY_DRIVER_FAILED, false),
            formatFailures = prefs.getStringSet(KEY_FORMAT_FAILURES, emptySet())?.toSet() ?: emptySet()
        )
        val updated = VulkanDriverPolicy.afterFailure(stored, currentKey, format?.formatKey, isGlobalDriverFailure)
        if (isGlobalDriverFailure) {
            Log.e("VP", "VulkanDriverCache: recording global Vulkan driver failure on $currentKey (Vulkan off for every format until the driver changes)")
        }
        if (format != null) {
            Log.e("VP", "VulkanDriverCache: recording Vulkan failure for format ${format.label} on $currentKey")
        }
        prefs.edit()
            .putString(KEY_DRIVER_FINGERPRINT, currentKey)
            .putBoolean(KEY_DRIVER_FAILED, updated.driverFailed)
            .putStringSet(KEY_FORMAT_FAILURES, updated.formatFailures)
            .apply()
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
