package org.kde.plasmawave.ui.components

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves missing or placeholder Deezer album artwork (such as Deezer's empty-string MD5
 * `d41d8cd98f00b204e9800998ecf8427e`) using Deezer's public search API and iTunes Search API.
 */
object ArtworkResolver {

    private const val PREFS_NAME = "artwork_fallback_cache"
    private const val DEEZER_EMPTY_HASH = "d41d8cd98f00b204e9800998ecf8427e"
    private const val ZERO_HASH = "00000000000000000000000000000000"

    private val memoryCache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val prefsLoadLock = Any()
    @Volatile private var prefsLoaded = false

    fun isPlaceholderOrBlank(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        return url.contains(DEEZER_EMPTY_HASH, ignoreCase = true) || url.contains(ZERO_HASH)
    }

    /** Preloads persisted fallback artwork entries off the Main thread once per process. */
    fun warmup(context: Context) {
        ensurePrefsLoaded(context)
    }

    private fun ensurePrefsLoaded(context: Context) {
        if (prefsLoaded) return
        synchronized(prefsLoadLock) {
            if (prefsLoaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.all.forEach { (k, v) ->
                if (v is String) {
                    memoryCache.putIfAbsent(k, v)
                }
            }
            prefsLoaded = true
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
                ensurePrefsLoaded(appContext)
                memoryCache[key]?.let { cached ->
                    return@withContext cached.ifEmpty { null }
                }
                val fetched = queryDeezerSearch(cleanTitle, artist) ?: queryItunesSearch(cleanTitle, artist)
                memoryCache[key] = fetched.orEmpty()
                if (!fetched.isNullOrBlank()) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(key, fetched)
                        .apply()
                }
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

    private fun queryDeezerSearch(title: String, artist: String?): String? {
        val cleanArtist = artist?.trim().orEmpty()
        val query = listOf(title, cleanArtist).filter { it.isNotEmpty() }.joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://api.deezer.com/search?q=$encoded&limit=5") ?: return null
        val data = JSONObject(body).optJSONArray("data") ?: return null
        val titleNorm = normalizeForMatch(title)
        val artistNorm = normalizeForMatch(cleanArtist)

        var firstArtistMatch: String? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val album = item.optJSONObject("album") ?: continue
            val md5 = album.optString("md5_image")
            if (md5.isBlank() || md5.equals(DEEZER_EMPTY_HASH, ignoreCase = true) || md5 == ZERO_HASH) {
                continue
            }
            val cover = album.optString("cover_medium").takeIf { !isPlaceholderOrBlank(it) }
                ?: "https://e-cdns-images.dzcdn.net/images/cover/$md5/250x250-000000-80-0-0.jpg"

            val itemTitle = normalizeForMatch(item.optString("title"))
            val itemArtist = normalizeForMatch(item.optJSONObject("artist")?.optString("name").orEmpty())
            val titleMatches = itemTitle.contains(titleNorm) || titleNorm.contains(itemTitle)
            val artistMatches = artistTokensMatch(artistNorm, itemArtist)
            if (titleMatches && artistMatches) {
                return cover
            }
            if (firstArtistMatch == null && artistMatches) {
                firstArtistMatch = cover
            }
        }
        return firstArtistMatch
    }

    private fun queryItunesSearch(title: String, artist: String?): String? {
        val cleanArtist = artist?.trim().orEmpty()
        val query = listOf(title, cleanArtist).filter { it.isNotEmpty() }.joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://itunes.apple.com/search?term=$encoded&entity=song&limit=3") ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val titleNorm = normalizeForMatch(title)
        val artistNorm = normalizeForMatch(cleanArtist)

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val art = item.optString("artworkUrl100").takeIf { it.startsWith("https://") } ?: continue
            val itemTitle = normalizeForMatch(item.optString("trackName"))
            val itemArtist = normalizeForMatch(item.optString("artistName"))
            val titleMatches = itemTitle.contains(titleNorm) || titleNorm.contains(itemTitle)
            val artistMatches = artistTokensMatch(artistNorm, itemArtist)
            if (titleMatches && artistMatches) {
                return art.replace("100x100bb", "300x300bb")
            }
        }
        return null
    }

    private fun artistTokensMatch(expectedNorm: String, candidateNorm: String): Boolean {
        if (expectedNorm.isEmpty()) return true
        if (candidateNorm.contains(expectedNorm) || expectedNorm.contains(candidateNorm)) return true
        val tokens = expectedNorm.split(' ').filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.all { candidateNorm.contains(it) }
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "PlasmaWave/0.8")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
