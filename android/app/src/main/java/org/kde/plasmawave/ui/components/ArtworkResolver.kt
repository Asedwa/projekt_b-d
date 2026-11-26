package org.kde.plasmawave.ui.components

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.plasmawave.PlasmaWaveApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Kotlin facade over the Rust `provider-deezer::artwork` resolver and bounded SQLite `artwork_cache`.
 * Keeps an in-memory map for instant UI lookups and `inFlight` deduplication.
 */
object ArtworkResolver {

    private const val LEGACY_PREFS_NAME = "artwork_fallback_cache"
    private const val DEEZER_EMPTY_HASH = "d41d8cd98f00b204e9800998ecf8427e"
    private const val ZERO_HASH = "00000000000000000000000000000000"

    private val memoryCache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val warmupLock = Any()
    @Volatile private var warmedUp = false

    fun isPlaceholderOrBlank(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        return url.contains(DEEZER_EMPTY_HASH, ignoreCase = true) || url.contains(ZERO_HASH)
    }

    /** Preloads bounded persisted fallback artwork entries from Rust SQLite once per process. */
    fun warmup(context: Context) {
        if (warmedUp) return
        synchronized(warmupLock) {
            if (warmedUp) return
            runCatching {
                val cached = PlasmaWaveApp.core.listCachedArtwork(500u)
                cached.forEach { (k, v) -> memoryCache.putIfAbsent(k, v) }
            }
            // Clear legacy unbounded SharedPreferences file if present
            runCatching {
                val legacy = context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                if (legacy.all.isNotEmpty()) {
                    legacy.edit().clear().apply()
                }
            }
            warmedUp = true
        }
    }

    fun isKnownMissing(title: String?, artist: String?): Boolean {
        val key = cacheKey(title, artist) ?: return false
        return memoryCache[key] == ""
    }

    fun getCached(context: Context, title: String?, artist: String?): String? {
        val key = cacheKey(title, artist) ?: return null
        return memoryCache[key]?.ifEmpty { null }
    }

    suspend fun resolve(context: Context, title: String?, artist: String?): String? {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = cacheKey(cleanTitle, artist) ?: return null

        memoryCache[key]?.let { return it.ifEmpty { null } }

        val existing = inFlight[key]
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<String?>()
        val raced = inFlight.putIfAbsent(key, deferred)
        if (raced != null) return raced.await()

        val appContext = context.applicationContext
        return try {
            val resolved = withContext(Dispatchers.IO) {
                warmup(appContext)
                memoryCache[key]?.let { cached ->
                    return@withContext cached.ifEmpty { null }
                }
                val fetched = runCatching {
                    PlasmaWaveApp.core.resolveArtwork(cleanTitle, artist)
                }.getOrNull()
                memoryCache[key] = fetched.orEmpty()
                fetched
            }
            deferred.complete(resolved)
            resolved
        } catch (_: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun cacheKey(title: String?, artist: String?): String? {
        val t = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val a = artist?.trim()?.lowercase().orEmpty()
        return "$a|$t"
    }

    private val DEEZER_SIZE_REGEX = Regex("""/(?:56x56|120x120|250x250|500x500)(?=[-./]|$)""")
    private val ITUNES_SIZE_REGEX = Regex("""/\d+x\d+bb(?=\.)""")

    /** Upgrades CDN cover URLs to 1000x1000 for large full-screen player surfaces. */
    fun highResUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return when {
            url.contains("dzcdn.net/images/", ignoreCase = true) ->
                url.replace(DEEZER_SIZE_REGEX, "/1000x1000")
            url.contains("mzstatic.com/", ignoreCase = true) ->
                url.replace(ITUNES_SIZE_REGEX, "/1000x1000bb")
            else -> url
        }
    }
}
