package com.vibeplayer.playback

import android.content.Context
import android.util.Log
import com.vibeplayer.player.FormatProfileTable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the per-format table (assets/format_table.json, P6-06) for this phone's Vulkan driver.
 *
 * The table is keyed by the driver hash: first 16 hex digits of sha256(vulkan.adreno.so), the same
 * identity the benchmark records. Hashing the library is file IO, so it runs once per driver build
 * (Build.FINGERPRINT + mtime, [VulkanDriverCache.computeDriverKey]) and the result is kept in
 * preferences. Until the hash is known, and when it matches no table, the table's default rows apply.
 * Call [loadAsync] early (activity and service onCreate); everything runs on a background thread.
 */
object FormatTableLoader {

    private const val PREFS_NAME = "vulkan_driver_cache"
    private const val KEY_HASH_FOR = "driver_hash_for_key"
    private const val KEY_HASH = "driver_hash"

    private val started = AtomicBoolean(false)

    fun loadAsync(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({ load(app) }, "vp-format-table").apply { isDaemon = true }.start()
    }

    private fun load(context: Context) {
        val json = try {
            context.assets.open(FormatProfileTable.ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("VP", "FormatTable: cannot read asset, keeping built-in rows: ${e.message}")
            return
        }
        try {
            val key = VulkanDriverCache.computeDriverKey()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cached = if (prefs.getString(KEY_HASH_FOR, null) == key) prefs.getString(KEY_HASH, null) else null
            if (cached != null) {
                val source = FormatProfileTable.install(json, cached)
                Log.i("VP", "FormatTable: driver $cached -> table $source")
                return
            }
            // Default rows now, the driver's own rows once the library is hashed.
            FormatProfileTable.install(json, null)
            val hash = hashDriver() ?: "unreadable"
            prefs.edit().putString(KEY_HASH_FOR, key).putString(KEY_HASH, hash).apply()
            val source = FormatProfileTable.install(json, hash)
            Log.i("VP", "FormatTable: driver $hash (hashed for $key) -> table $source")
        } catch (e: Exception) {
            Log.e("VP", "FormatTable: bad table, keeping built-in rows: ${e.message}", e)
        }
    }

    /** First 16 hex digits of sha256 of the Vulkan driver library, or null if none is readable. */
    fun hashDriver(): String? {
        for (path in VulkanDriverCache.VULKAN_SO_PATHS) {
            val file = File(path)
            if (!file.canRead()) continue
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            } catch (e: Exception) {
                Log.w("VP", "FormatTable: cannot hash $path: ${e.message}")
                null
            }
        }
        return null
    }
}
