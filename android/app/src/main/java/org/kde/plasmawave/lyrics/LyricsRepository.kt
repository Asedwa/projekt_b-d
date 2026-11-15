package org.kde.plasmawave.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(
    val timeMs: Long,
    val text: String
)

sealed class LyricsState {
    data object Idle : LyricsState()
    data object Loading : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
    data class Plain(val text: String) : LyricsState()
    data object Instrumental : LyricsState()
    data object NotFound : LyricsState()
    data class Error(val message: String) : LyricsState()
}

/** Fetches and caches synchronized/plain lyrics from LRCLIB. */
object LyricsRepository {
    private val cache = ConcurrentHashMap<String, LyricsState>()

    private fun cacheKey(title: String, artist: String): String =
        "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    fun getCached(title: String, artist: String): LyricsState? =
        cache[cacheKey(title, artist)]

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        forceRefresh: Boolean = false
    ): LyricsState {
        val key = cacheKey(title, artist)
        if (!forceRefresh) {
            cache[key]?.let { if (it !is LyricsState.Error) return it }
        }

        val result = withContext(Dispatchers.IO) {
            try {
                val encTitle = URLEncoder.encode(title.trim(), "UTF-8")
                val encArtist = URLEncoder.encode(artist.trim(), "UTF-8")
                val encAlbum = album?.takeIf { it.isNotBlank() }?.let { URLEncoder.encode(it.trim(), "UTF-8") }
                val durationSec = (durationMs / 1000L).takeIf { it > 0L }

                val getUrl = buildString {
                    append("https://lrclib.net/api/get?track_name=").append(encTitle)
                    append("&artist_name=").append(encArtist)
                    if (encAlbum != null) append("&album_name=").append(encAlbum)
                    if (durationSec != null) append("&duration=").append(durationSec)
                }

                val directJson = httpGetJsonObject(getUrl)
                if (directJson != null) {
                    val parsed = parseLyricsJson(directJson)
                    if (parsed !is LyricsState.NotFound) return@withContext parsed
                }

                // Fallback to search endpoint when exact match is not found
                val searchUrl = "https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist"
                val searchArr = httpGetJsonArray(searchUrl)
                if (searchArr != null && searchArr.length() > 0) {
                    var bestPlain: LyricsState? = null
                    for (i in 0 until searchArr.length()) {
                        val item = searchArr.optJSONObject(i) ?: continue
                        val parsed = parseLyricsJson(item)
                        if (parsed is LyricsState.Synced) return@withContext parsed
                        if (bestPlain == null && parsed !is LyricsState.NotFound) {
                            bestPlain = parsed
                        }
                    }
                    if (bestPlain != null) return@withContext bestPlain
                }

                LyricsState.NotFound
            } catch (e: Exception) {
                LyricsState.Error(e.message ?: "Şarkı sözleri alınamadı")
            }
        }

        if (result !is LyricsState.Error) {
            cache[key] = result
        }
        return result
    }

    private fun parseLyricsJson(json: JSONObject): LyricsState {
        if (json.optBoolean("instrumental", false)) {
            return LyricsState.Instrumental
        }
        val synced = json.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return LyricsState.Synced(lines)
        }
        val plain = json.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (plain != null) {
            return LyricsState.Plain(plain.trim())
        }
        return LyricsState.NotFound
    }

    private fun httpGetJsonObject(urlStr: String): JSONObject? {
        val body = httpGet(urlStr) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun httpGetJsonArray(urlStr: String): JSONArray? {
        val body = httpGet(urlStr) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "PlasmaWave/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    fun parseLrc(raw: String): List<LyricLine> {
        val regex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val result = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { line ->
            val matches = regex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = regex.replace(line, "").trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                val fracStr = m.groupValues[3]
                val msPart = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = min * 60_000L + sec * 1000L + msPart
                result.add(LyricLine(totalMs, text))
            }
        }
        return result.sortedBy { it.timeMs }
    }
}
