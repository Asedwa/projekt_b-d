package org.kde.plasmawave.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.kde.plasmawave.PlasmaWaveApp
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cached, Rust SQLite-backed key-value store replacing legacy SharedPreferences.
 * Reads are O(1) non-blocking; writes update memory immediately and persist asynchronously on Dispatchers.IO.
 */
object RustPrefs {
    private const val MIGRATION_FLAG = "meta:kv_migrated_v1"
    private val cache = ConcurrentHashMap<String, String>()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Any()
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            runCatching {
                val existing = PlasmaWaveApp.core.getPrefsByPrefix("")
                cache.putAll(existing)
            }
            if (cache[MIGRATION_FLAG] != "true") {
                migrateLegacyPrefs(context.applicationContext)
            }
            initialized = true
        }
    }

    private fun migrateLegacyPrefs(appContext: Context) {
        val batch = HashMap<String, String>()

        fun copyPrefs(prefsName: String, keyPrefix: String, filter: (String) -> Boolean = { true }) {
            runCatching {
                val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                for ((k, v) in prefs.all) {
                    if (!filter(k) || v == null) continue
                    val targetKey = "$keyPrefix$k"
                    if (!cache.containsKey(targetKey)) {
                        val strVal = when (v) {
                            is Boolean, is Int, is Long, is Float, is String -> v.toString()
                            else -> null
                        }
                        if (strVal != null) {
                            batch[targetKey] = strVal
                            cache[targetKey] = strVal
                        }
                    }
                }
            }
        }

        copyPrefs("display_preferences", "display:")
        copyPrefs("audio_settings", "audio:")
        copyPrefs("audio_fx", "audio_fx:")
        copyPrefs("playlist_sort", "sort:")
        copyPrefs("fast_playlist_cache_v1", "cache:") { it == "deezer:playlists" }

        batch[MIGRATION_FLAG] = "true"
        cache[MIGRATION_FLAG] = "true"

        writeScope.launch {
            runCatching { PlasmaWaveApp.core.setPrefsBatch(batch) }
        }
    }

    fun contains(key: String): Boolean = cache.containsKey(key)

    fun getString(key: String, default: String? = null): String? = cache[key] ?: default

    fun getBoolean(key: String, default: Boolean): Boolean =
        cache[key]?.toBooleanStrictOrNull() ?: default

    fun getInt(key: String, default: Int): Int =
        cache[key]?.toIntOrNull() ?: default

    fun getLong(key: String, default: Long): Long =
        cache[key]?.toLongOrNull() ?: default

    fun getFloat(key: String, default: Float): Float =
        cache[key]?.toFloatOrNull() ?: default

    fun putString(key: String, value: String) {
        cache[key] = value
        writeScope.launch { runCatching { PlasmaWaveApp.core.setPref(key, value) } }
    }

    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun putLong(key: String, value: Long) = putString(key, value.toString())
    fun putFloat(key: String, value: Float) = putString(key, value.toString())

    fun remove(key: String) {
        cache.remove(key)
        writeScope.launch { runCatching { PlasmaWaveApp.core.removePref(key) } }
    }
}
