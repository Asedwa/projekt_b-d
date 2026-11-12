package org.kde.plasmawave.deezer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DeezerException(message: String) : IOException(message)

/** The web-player session only asks Deezer for media permitted to this account. */
internal class DeezerGateway(private val arl: String, private val transport: Transport = HttpTransport()) {
    internal data class Reply(val body: String, val cookies: List<String> = emptyList())
    internal fun interface Transport {
        fun post(url: String, body: String, cookie: String?): Reply
    }
    internal data class Account(val id: String, val name: String, val plan: String, val highQuality: Boolean)
    internal data class Media(val url: String, val trackId: String, val format: String)

    private var sid = ""
    private var apiToken = ""
    private var licenseToken = ""
    private var account: Account? = null

    @Synchronized
    fun login(): Account {
        val result = call("deezer.getUserData", JSONObject(), authenticated = false)
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

    @Synchronized
    fun getAccount(): Account = account ?: login()

    @Synchronized
    fun resolve(trackId: String): Media {
        require(trackId.isNotEmpty() && trackId.all(Char::isDigit)) { "Geçersiz şarkı kimliği" }
        getAccount()
        // Refresh the session and track token once if Deezer rejects an old media grant.
        repeat(2) { attempt ->
            val track = callWithRefresh("song.getData", JSONObject().put("sng_id", trackId))
            val candidates = listOfNotNull(track, track.optJSONObject("FALLBACK"))
            for ((index, candidate) in candidates.withIndex()) {
                val id = if (index == 0) trackId else candidate.optString("SNG_ID")
                val token = candidate.optString("TRACK_TOKEN")
                if (id.isBlank() || !id.all(Char::isDigit) || token.isBlank() || licenseToken.isBlank()) continue
                resolveMedia(id, token)?.let { return it }
            }
            if (attempt == 0) login()
        }
        throw DeezerException("Bu şarkının tamamı hesapta kullanılamıyor")
    }

    private fun resolveMedia(trackId: String, token: String): Media? {
        val formats = JSONArray()
        if (account?.highQuality == true) formats.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", "MP3_320"))
        listOf("MP3_128", "MP3_64", "MP3_MISC").forEach {
            formats.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", it))
        }
        val payload = JSONObject().put("license_token", licenseToken)
            .put("media", JSONArray().put(JSONObject().put("type", "FULL").put("formats", formats)))
            .put("track_tokens", JSONArray().put(token))
        val reply = JSONObject(transport.post("https://media.deezer.com/v1/get_url", payload.toString(), null).body)
        val item = reply.optJSONArray("data")?.optJSONObject(0)
        val media = item?.optJSONArray("media")?.optJSONObject(0) ?: return null
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

    fun playlists(): JSONArray = pages { start ->
        callWithRefresh("deezer.pageProfile", JSONObject()
            .put("user_id", getAccount().id).put("tab", "playlists").put("nb", 100).put("index", start))
            .optJSONObject("TAB")?.optJSONObject("playlists")?.optJSONArray("data")
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
        for (start in 0 until 5000 step 100) {
            val page = load(start) ?: break
            for (index in 0 until page.length()) all.put(page.get(index))
            if (page.length() < 100) break
        }
        return all
    }

    @Synchronized
    private fun callWithRefresh(method: String, body: JSONObject): JSONObject {
        try {
            return call(method, body, authenticated = true)
        } catch (e: TokenExpired) {
            login()
            return call(method, body, authenticated = true)
        }
    }

    private class TokenExpired : IOException()

    private fun call(method: String, body: JSONObject, authenticated: Boolean): JSONObject {
        val token = if (authenticated) apiToken else ""
        val url = "https://www.deezer.com/ajax/gw-light.php?method=$method&input=3&api_version=1.0&api_token=" +
            URLEncoder.encode(token, "UTF-8")
        val cookie = "arl=$arl" + if (sid.isBlank()) "" else "; sid=$sid"
        val response = transport.post(url, body.toString(), cookie)
        response.cookies.forEach { header ->
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith("sid=", ignoreCase = true)) sid = pair.substringAfter('=')
        }
        val envelope = JSONObject(response.body)
        val error = envelope.opt("error")
        val errorText = error?.toString().orEmpty()
        if (errorText.isNotBlank() && errorText != "{}" && errorText != "[]" && errorText != "null") {
            if (authenticated && errorText.contains("TOKEN", ignoreCase = true)) throw TokenExpired()
            throw DeezerException("Deezer isteği başarısız oldu")
        }
        return envelope.optJSONObject("results") ?: throw DeezerException("Deezer yanıtı okunamadı")
    }

    private class HttpTransport : Transport {
        override fun post(url: String, body: String, cookie: String?): Reply {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                if (cookie != null) connection.setRequestProperty("Cookie", cookie)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw DeezerException("Deezer bağlantı hatası (HTTP ${connection.responseCode})")
                val cookies = connection.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value ?: emptyList() }
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return Reply(response, cookies)
            } finally {
                connection.disconnect()
            }
        }
    }
}
