package org.kde.plasmawave.deezer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder

class DeezerException(message: String) : IOException(message)
internal class TransientDeezerException(message: String) : IOException(message)

/** The web-player session only asks Deezer for media permitted to this account. */
internal class DeezerGateway(private val arl: String, private val transport: Transport = HttpTransport()) {
    internal data class Reply(val body: String, val cookies: List<String> = emptyList())
    internal fun interface Transport {
        fun post(url: String, body: String, cookie: String?): Reply
    }
    internal data class Account(val id: String, val name: String, val plan: String, val highQuality: Boolean)
    internal data class Media(val url: String, val trackId: String, val format: String)

    private val authLock = Any()
    @Volatile private var sid = ""
    @Volatile private var apiToken = ""
    @Volatile private var licenseToken = ""
    @Volatile private var account: Account? = null

    fun login(): Account = synchronized(authLock) {
        val result = withTransientRetry { call("deezer.getUserData", JSONObject(), authenticated = false) }
        val user = result.optJSONObject("USER") ?: throw DeezerException("Deezer oturumu doğrulanamadı")
        val id = user.optString("USER_ID")
        val token = result.optString("checkForm")
        if (id.isBlank() || id == "0" || token.isBlank()) {
            throw DeezerException("Deezer oturumu geçersiz veya süresi dolmuş")
        }
        val options = user.optJSONObject("OPTIONS") ?: JSONObject()
        val highQuality = options.optBoolean("web_hq") || options.optBoolean("mobile_hq") ||
            options.optBoolean("web_lossless") || options.optBoolean("mobile_lossless")
        val offers = result.optJSONArray("OFFERS")
        val offer = offers?.optJSONObject(0)?.optString("title").orEmpty()
        apiToken = token
        licenseToken = options.optString("license_token")
        val name = user.optString("BLOG_NAME").ifBlank { user.optString("FIRSTNAME").ifBlank { "Deezer" } }
        return Account(id, name, offer.ifBlank { if (highQuality) "Deezer Premium" else "Deezer" }, highQuality)
            .also { account = it }
    }

    private fun refreshSessionIfNeeded(staleToken: String?): Account = synchronized(authLock) {
        if (staleToken != null && apiToken.isNotBlank() && apiToken != staleToken && account != null) {
            return account!!
        }
        return login()
    }

    fun getAccount(): Account = account ?: synchronized(authLock) { account ?: login() }

    fun resolve(trackId: String, exactFormat: String? = null): Media {
        require(trackId.isNotEmpty() && trackId.all(Char::isDigit)) { "Geçersiz şarkı kimliği" }
        require(exactFormat == null || exactFormat in setOf("FLAC", "MP3_320", "MP3_128")) { "Geçersiz ses biçimi" }
        getAccount()
        // Refresh the session and track token once if Deezer rejects an old media grant.
        repeat(2) { attempt ->
            val usedToken = apiToken
            val track = callWithRefresh("song.getData", JSONObject().put("sng_id", trackId))
            val candidates = listOfNotNull(track, track.optJSONObject("FALLBACK"))
            for ((index, candidate) in candidates.withIndex()) {
                val id = if (index == 0) trackId else candidate.optString("SNG_ID")
                val token = candidate.optString("TRACK_TOKEN")
                if (id.isBlank() || !id.all(Char::isDigit) || token.isBlank() || licenseToken.isBlank()) continue
                resolveMedia(id, token, exactFormat)?.let { return it }
            }
            if (attempt == 0) refreshSessionIfNeeded(usedToken)
        }
        throw DeezerException(if (exactFormat == null) "Bu şarkının tamamı hesapta kullanılamıyor"
            else "Bu şarkı seçilen $exactFormat biçiminde hesabında kullanılamıyor")
    }

    private fun resolveMedia(trackId: String, token: String, exactFormat: String?): Media? {
        val formats = JSONArray()
        val requested = if (exactFormat != null) listOf(exactFormat) else
            (if (account?.highQuality == true) listOf("MP3_320") else emptyList()) + listOf("MP3_128", "MP3_64", "MP3_MISC")
        requested.forEach {
            formats.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", it))
        }
        val payload = JSONObject().put("license_token", licenseToken)
            .put("media", JSONArray().put(JSONObject().put("type", "FULL").put("formats", formats)))
            .put("track_tokens", JSONArray().put(token))
        val rawReply = withTransientRetry { transport.post("https://media.deezer.com/v1/get_url", payload.toString(), null).body }
        val reply = parseJson(rawReply)
        val item = reply.optJSONArray("data")?.optJSONObject(0)
        val media = item?.optJSONArray("media")?.optJSONObject(0) ?: return null
        if (exactFormat != null && media.optString("format") != exactFormat) return null
        val source = media.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
        val url = try { URL(source) } catch (_: Exception) { return null }
        val host = url.host.lowercase()
        if (url.protocol != "https" || !(host == "dzcdn.net" || host.endsWith(".dzcdn.net") || host == "deezer.com" || host.endsWith(".deezer.com"))) {
            throw DeezerException("Geçersiz Deezer ses kaynağı")
        }
        return Media(source, trackId, media.optString("format"))
    }

    fun favorites(): JSONArray = pages { start ->
        callWithRefresh("favorite_song.getList", JSONObject()
            .put("user_id", getAccount().id).put("nb", 100).put("start", start)).optJSONArray("data")
    }

    fun setFavorite(trackId: String, favorite: Boolean) {
        requireNumericId(trackId)
        getAccount()
        callWithRefresh(if (favorite) "favorite_song.add" else "favorite_song.remove", JSONObject().put("SNG_ID", trackId))
    }

    fun editPlaylist(playlistId: String, trackId: String, add: Boolean) {
        requireNumericId(playlistId)
        requireNumericId(trackId)
        getAccount()
        val body = JSONObject().put("playlist_id", playlistId)
            .put("songs", JSONArray().put(JSONArray().put(trackId).put(0)))
        if (add) body.put("offset", -1)
        callWithRefresh(if (add) "playlist.addSongs" else "playlist.deleteSongs", body)
    }

    fun createPlaylist(name: String): String {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 200) { "Geçersiz liste adı" }
        getAccount()
        val result = callWithRefresh("playlist.create", JSONObject().put("title", title).put("description", "")
            .put("songs", JSONArray()).put("status", 1))
        val id = result.optString("value").ifBlank { result.optString("PLAYLIST_ID") }
        requireNumericId(id)
        return id
    }

    fun deletePlaylist(playlistId: String) {
        requireNumericId(playlistId)
        getAccount()
        callWithRefresh("playlist.delete", JSONObject().put("playlist_id", playlistId))
    }

    fun trackMix(trackId: String): JSONArray {
        requireNumericId(trackId)
        getAccount()
        return callWithRefresh("song.getSearchTrackMix", JSONObject().put("sng_id", trackId)
            .put("start_with_input_track", "true")).optJSONArray("data")
            ?: throw DeezerException("Şarkı miksi alınamadı")
    }

    fun flow(): JSONArray {
        getAccount()
        val userId = getAccount().id
        val candidates = listOf(
            "radio.getUserRadio" to JSONObject().put("user_id", userId),
            "smart.getSmartRadio" to JSONObject().put("user_id", userId),
            "radio.getFlow" to JSONObject().put("user_id", userId),
            "user.getFlow" to JSONObject().put("user_id", userId),
            "deezer.pageFlow" to JSONObject().put("user_id", userId)
        )
        for ((method, payload) in candidates) {
            try {
                val res = callWithRefresh(method, payload)
                val data = res.optJSONArray("data")
                    ?: res.optJSONArray("tracks")
                    ?: res.optJSONObject("data")?.optJSONArray("tracks")
                    ?: res.optJSONObject("TAB")?.optJSONObject("tracks")?.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return data
                }
            } catch (_: Exception) {}
        }

        // Algorithmic flow fallback: Interweave user's favorite tracks with smart track mixes
        val favs = favorites()
        if (favs.length() > 0) {
            val combined = JSONArray()
            val seen = mutableSetOf<String>()
            val sampleCount = minOf(favs.length(), 6)
            val sampleIndices = (0 until favs.length()).shuffled().take(sampleCount)
            for (idx in sampleIndices) {
                val favTrack = favs.optJSONObject(idx) ?: continue
                val trackId = favTrack.optString("SNG_ID")
                if (trackId.isNotBlank() && seen.add(trackId)) {
                    combined.put(favTrack)
                }
                try {
                    val mix = trackMix(trackId)
                    for (m in 0 until minOf(mix.length(), 5)) {
                        val mixTrack = mix.optJSONObject(m) ?: continue
                        val mId = mixTrack.optString("SNG_ID")
                        if (mId.isNotBlank() && seen.add(mId)) {
                            combined.put(mixTrack)
                        }
                    }
                } catch (_: Exception) {}
            }
            if (combined.length() > 0) return combined
        }
        throw DeezerException("Deezer Flow yüklenemedi. Favori şarkılarınızı kontrol edin.")
    }

    private fun requireNumericId(id: String) {
        require(id.isNotEmpty() && id.all(Char::isDigit)) { "Geçersiz Deezer kimliği" }
    }

    fun playlists(): JSONArray {
        val all = JSONArray()
        var start = 0
        var previous: String? = null
        while (true) {
            val page = try {
                callWithRefresh("deezer.pageProfile", JSONObject()
                    .put("user_id", getAccount().id).put("tab", "playlists").put("nb", 100).put("index", start))
                    .optJSONObject("TAB")?.optJSONObject("playlists")?.optJSONArray("data")
            } catch (e: Exception) {
                if (start == 0) throw e
                break
            }
            if (page == null) {
                if (start == 0) throw DeezerException("Deezer listesi okunamadı")
                break
            }
            val signature = page.toString()
            if (page.length() > 0 && signature == previous) break
            for (index in 0 until page.length()) all.put(page.get(index))
            if (page.length() < 100) break
            previous = signature
            start += page.length()
            if (start > 100_000) break
        }
        return all
    }

    fun playlistTracks(id: String): JSONArray {
        require(id.isNotEmpty() && id.all(Char::isDigit)) { "Geçersiz liste kimliği" }
        val all = JSONArray()
        var start = 0
        var useLegacyMethod = false
        while (true) {
            val page = try {
                playlistPage(id, start, useLegacyMethod)
            } catch (e: DeezerException) {
                // Some accounts only expose the older playlist endpoint.
                if (start != 0 || useLegacyMethod) throw e
                useLegacyMethod = true
                playlistPage(id, start, useLegacyMethod)
            }
            val count = page.data.length()
            if (count == 0 && page.total != null && start < page.total) {
                throw DeezerException("Çalma listesinin şarkıları alınamadı")
            }
            for (index in 0 until count) all.put(page.data.get(index))
            start += count
            if (count == 0 || (page.total != null && start >= page.total)) break
            if (page.total == null && count < 100) break
        }
        return all
    }

    private data class TrackPage(val data: JSONArray, val total: Int?)

    private fun playlistPage(id: String, start: Int, legacy: Boolean): TrackPage {
        val request = JSONObject().put("playlist_id", id).put("nb", 100).put("start", start)
        val result = if (legacy) {
            callWithRefresh("playlist.getSongs", request)
        } else {
            request.put("lang", "tr").put("tags", true)
            callWithRefresh("deezer.pagePlaylist", request).optJSONObject("SONGS")
                ?: throw DeezerException("Çalma listesi yanıtı okunamadı")
        }
        val data = result.optJSONArray("data") ?: throw DeezerException("Çalma listesi yanıtı okunamadı")
        val total = if (result.has("total") && !result.isNull("total")) result.optInt("total", -1).takeIf { it >= 0 } else null
        return TrackPage(data, total)
    }

    private fun pages(load: (Int) -> JSONArray?): JSONArray {
        val all = JSONArray()
        var start = 0
        var previous: String? = null
        while (true) {
            val page = load(start) ?: throw DeezerException("Deezer listesi okunamadı")
            val signature = page.toString()
            if (page.length() > 0 && signature == previous) throw DeezerException("Deezer listesinin sonraki sayfası alınamadı")
            for (index in 0 until page.length()) all.put(page.get(index))
            if (page.length() < 100) break
            previous = signature
            start += page.length()
            if (start > 100_000) throw DeezerException("Deezer listesi çok büyük")
        }
        return all
    }

    private fun callWithRefresh(method: String, body: JSONObject): JSONObject {
        val usedToken = synchronized(authLock) { apiToken }
        return try {
            withTransientRetry { call(method, body, authenticated = true) }
        } catch (e: TokenExpired) {
            refreshSessionIfNeeded(usedToken)
            withTransientRetry { call(method, body, authenticated = true) }
        }
    }

    private class TokenExpired : IOException()

    private fun call(method: String, body: JSONObject, authenticated: Boolean): JSONObject {
        val (token, currentSid) = synchronized(authLock) {
            (if (authenticated) apiToken else "") to sid
        }
        val url = "https://www.deezer.com/ajax/gw-light.php?method=$method&input=3&api_version=1.0&api_token=" +
            URLEncoder.encode(token, "UTF-8")
        val cookie = "arl=$arl" + if (currentSid.isBlank()) "" else "; sid=$currentSid"
        val response = transport.post(url, body.toString(), cookie)
        if (response.cookies.isNotEmpty()) {
            synchronized(authLock) {
                response.cookies.forEach { header ->
                    val pair = header.substringBefore(';').trim()
                    if (pair.startsWith("sid=", ignoreCase = true)) sid = pair.substringAfter('=')
                }
            }
        }
        val envelope = parseJson(response.body)
        val error = envelope.opt("error")
        val errorText = error?.toString().orEmpty()
        if (errorText.isNotBlank() && errorText != "{}" && errorText != "[]" && errorText != "null") {
            val isAuthError = authenticated && (
                errorText.contains("TOKEN", ignoreCase = true) ||
                errorText.contains("CSRF", ignoreCase = true) ||
                errorText.contains("SESSION", ignoreCase = true) ||
                errorText.contains("EXPIRED", ignoreCase = true) ||
                errorText.contains("NEED_USER_AUTH", ignoreCase = true) ||
                errorText.contains("VALID_TOKEN", ignoreCase = true)
            )
            if (isAuthError) throw TokenExpired()
            throw DeezerException("Deezer isteği başarısız oldu")
        }
        return when (val result = envelope.opt("results")) {
            is JSONObject -> result
            is Boolean -> if (result) JSONObject().put("success", true) else throw DeezerException("Deezer işlemi reddetti")
            is Number -> if (result.toLong() != 0L) JSONObject().put("value", result) else throw DeezerException("Deezer işlemi reddetti")
            is String -> if (result.isNotBlank() && result != "0" && !result.equals("false", ignoreCase = true))
                JSONObject().put("value", result) else throw DeezerException("Deezer işlemi reddetti")
            else -> throw DeezerException("Deezer yanıtı okunamadı")
        }
    }

    private fun parseJson(body: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            throw TransientDeezerException("Deezer yanıtı okunamadı")
        }
    }

    private inline fun <T> withTransientRetry(block: () -> T): T {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (e: TokenExpired) {
                throw e
            } catch (e: DeezerException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) {
                    try { Thread.sleep(200L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                }
            }
        }
        val error = lastError!!
        if (error is TransientDeezerException) {
            throw DeezerException(error.message ?: "Deezer bağlantı hatası")
        }
        throw error
    }

    private class HttpTransport : Transport {
        override fun post(url: String, body: String, cookie: String?): Reply {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .header("Accept", "application/json")
                .apply {
                    if (cookie != null) header("Cookie", cookie)
                }
                .build()
            DeezerHttp.gatewayClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 429 || code in 500..599) {
                    throw TransientDeezerException("Deezer bağlantı hatası (HTTP $code)")
                }
                if (code !in 200..299) throw DeezerException("Deezer bağlantı hatası (HTTP $code)")
                val cookies = response.headers("Set-Cookie")
                val text = response.body?.string().orEmpty()
                return Reply(text, cookies)
            }
        }
    }
}
