package org.kde.plasmawave.deezer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.plasmawave_core.FfiDeezerUser
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack
import org.kde.plasmawave.ui.PlaylistTrackEntry
import org.kde.plasmawave.ui.LibraryPlaylistEntry
import org.kde.plasmawave.ui.parseAddedAt
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class StreamingQuality(val label: String, val format: String?) {
    AUTO("Otomatik", null), MP3_128("MP3 • 128 kbps", "MP3_128"),
    MP3_320("MP3 • 320 kbps", "MP3_320"), FLAC("FLAC • Kayıpsız", "FLAC")
}

internal fun useMobileStreamingQuality(wifi: Boolean, cellular: Boolean, metered: Boolean): Boolean =
    when { cellular -> true; wifi -> false; else -> metered }

internal fun qualityForNetwork(useMobile: Boolean, wifi: StreamingQuality, mobile: StreamingQuality): StreamingQuality =
    if (useMobile) mobile else wifi

/** Owns the user's session. ARL is encrypted by a non-exportable Android Keystore key. */
class DeezerSession private constructor(context: Context) {
    companion object {
        @Volatile private var instance: DeezerSession? = null
        fun get(context: Context): DeezerSession = instance ?: synchronized(this) {
            instance ?: DeezerSession(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("deezer_session", Context.MODE_PRIVATE)
    private val audioPrefs = context.getSharedPreferences("audio_settings", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private fun readQuality(key: String, default: StreamingQuality): StreamingQuality = runCatching {
        val legacy = audioPrefs.getString("streaming_quality", null)
        StreamingQuality.valueOf(audioPrefs.getString(key, legacy ?: default.name)!!)
    }.getOrDefault(default)
    private val _wifiQuality = MutableStateFlow(readQuality("wifi_quality", StreamingQuality.AUTO))
    private val _mobileQuality = MutableStateFlow(readQuality("mobile_quality", StreamingQuality.MP3_128))
    val wifiQuality = _wifiQuality.asStateFlow()
    val mobileQuality = _mobileQuality.asStateFlow()
    fun setWifiQuality(quality: StreamingQuality) {
        audioPrefs.edit().putString("wifi_quality", quality.name).apply()
        _wifiQuality.value = quality
    }
    fun setMobileQuality(quality: StreamingQuality) {
        audioPrefs.edit().putString("mobile_quality", quality.name).apply()
        _mobileQuality.value = quality
    }
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _lastResolvedMedia = MutableStateFlow<Pair<String, String>?>(null)
    val lastResolvedMedia: StateFlow<Pair<String, String>?> = _lastResolvedMedia.asStateFlow()
    private val _trackFormats = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var gateway: DeezerGateway? = null
    val hasSavedSession: Boolean get() = prefs.contains("session")
    val isAccountHighQuality: Boolean get() = gateway?.getAccount()?.highQuality == true

    @Synchronized
    fun login(arl: String): FfiDeezerUser {
        val value = arl.trim()
        if (value.isEmpty() || value.length > 1024 || value.any { it == ';' || it.isWhitespace() }) {
            throw DeezerException("Geçerli bir Deezer ARL oturumu gir")
        }
        val candidate = DeezerGateway(value)
        val user = candidate.login()
        saveSecret(value)
        gateway = candidate
        _connected.value = true
        return user.toFfi()
    }

    @Synchronized
    fun restore(): FfiDeezerUser? {
        if (gateway != null) return gateway!!.getAccount().toFfi()
        val secret = readSecret() ?: return null
        val candidate = DeezerGateway(secret)
        val user = candidate.login()
        gateway = candidate
        _connected.value = true
        return user.toFfi()
    }

    @Synchronized
    private fun activeGateway(): DeezerGateway {
        if (gateway == null) restore()
        return gateway ?: throw DeezerException("Tam şarkıları dinlemek için Deezer'a giriş yap")
    }

    fun currentStreamingQuality(): StreamingQuality {
        val mobile = try {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities == null) true else useMobileStreamingQuality(
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                connectivity.isActiveNetworkMetered
            )
        } catch (_: Exception) { true }
        return qualityForNetwork(mobile, _wifiQuality.value, _mobileQuality.value)
    }

    fun getResolvedTrackFormat(trackId: String): String? =
        _trackFormats[trackId.removePrefix("deezer:")]

    internal fun resolve(trackId: String): DeezerGateway.Media {
        val quality = currentStreamingQuality()
        val media = try {
            activeGateway().resolve(trackId, quality.format)
        } catch (e: DeezerException) {
            if (quality.format != null) {
                // Graceful fallback to best available quality if exact format isn't available
                activeGateway().resolve(trackId, null)
            } else {
                throw e
            }
        }
        val cleanId = trackId.removePrefix("deezer:")
        _trackFormats[cleanId] = media.format
        _lastResolvedMedia.value = cleanId to media.format
        return media
    }

    internal fun resolveDownload(trackId: String, format: String): DeezerGateway.Media {
        val media = activeGateway().resolve(trackId, format)
        val cleanId = trackId.removePrefix("deezer:")
        _trackFormats[cleanId] = media.format
        return media
    }
    fun setFavorite(trackId: String, favorite: Boolean) =
        activeGateway().setFavorite(trackId.removePrefix("deezer:"), favorite)
    fun editPlaylist(playlistId: String, trackId: String, add: Boolean) =
        activeGateway().editPlaylist(playlistId.removePrefix("deezer:"), trackId.removePrefix("deezer:"), add)
    fun createPlaylist(name: String): String = "deezer:${activeGateway().createPlaylist(name)}"
    fun deletePlaylist(id: String) = activeGateway().deletePlaylist(id.removePrefix("deezer:"))
    fun trackMix(trackId: String): List<FfiTrack> = tracks(activeGateway().trackMix(trackId.removePrefix("deezer:")))
    fun flow(): List<FfiTrack> = tracks(activeGateway().flow())
    fun favoriteTrackEntries(): List<PlaylistTrackEntry> =
        objects(activeGateway().favorites()).mapIndexedNotNull { index, json ->
            track(json)?.let {
                PlaylistTrackEntry(it, parseAddedAt(json.opt("DATE_ADD")) ?: parseAddedAt(json.opt("DATE_FAVORITE")), index)
            }
        }
    fun playlists(): List<LibraryPlaylistEntry> = objects(activeGateway().playlists()).mapNotNull { p ->
        val id = p.optString("PLAYLIST_ID")
        if (id.isBlank() || !id.all(Char::isDigit)) null else {
            val playlist = FfiPlaylist(
                id = "deezer:$id", name = p.optString("TITLE"), description = null, isLocal = false,
                trackCount = p.optLong("NB_SONG").coerceAtLeast(0).toUInt(),
                artworkUrl = playlistCover(id, p.optString("PLAYLIST_PICTURE"), p.optString("PICTURE_TYPE")), origin = "real"
            )
            LibraryPlaylistEntry(
                playlist = playlist,
                addedAt = parseAddedAt(p.opt("DATE_FAVORITE")) ?: parseAddedAt(p.opt("DATE_ADD")),
                modifiedAt = parseAddedAt(p.opt("DATE_MOD"))
            )
        }
    }
    fun playlistTrackEntries(id: String): List<PlaylistTrackEntry> =
        objects(activeGateway().playlistTracks(id.removePrefix("deezer:"))).mapIndexedNotNull { index, json ->
            track(json)?.let { PlaylistTrackEntry(it, parseAddedAt(json.opt("DATE_ADD")), index) }
        }

    @Synchronized
    fun logout() {
        gateway = null
        _connected.value = false
        _trackFormats.clear()
        _lastResolvedMedia.value = null
        prefs.edit().clear().commit()
    }

    private fun DeezerGateway.Account.toFfi() = FfiDeezerUser(
        id = id, name = name, email = null, statusLabel = plan, isPremium = highQuality,
        link = "https://www.deezer.com/profile/$id", pictureMedium = null,
        nbPlaylists = 0u, isAuthenticated = true
    )

    private fun tracks(array: JSONArray): List<FfiTrack> = objects(array).mapNotNull(::track)

    private fun track(t: JSONObject): FfiTrack? {
        val id = t.optString("SNG_ID")
        if (id.isBlank() || !id.all(Char::isDigit)) return null
        return FfiTrack(
            id = "deezer:$id", provider = "deezer", title = t.optString("SNG_TITLE"),
            artist = t.optString("ART_NAME"), album = t.optString("ALB_TITLE"),
            durationMs = (t.optLong("DURATION").coerceAtLeast(0) * 1000).toULong(),
            artworkUrl = cover("cover", t.optString("ALB_PICTURE")), playbackUri = null,
            isPreview = false, origin = "real"
        )
    }

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun playlistCover(id: String, hash: String, pictureType: String): String =
        cover(pictureType.takeIf { it == "cover" || it == "playlist" } ?: "playlist", hash)
            ?: "https://api.deezer.com/playlist/$id/image?size=medium"

    private fun cover(type: String, hash: String): String? =
        hash.takeIf {
            it.matches(Regex("[a-fA-F0-9]{32}")) &&
                !it.equals("d41d8cd98f00b204e9800998ecf8427e", ignoreCase = true) &&
                it != "00000000000000000000000000000000"
        }
            ?.let { "https://e-cdns-images.dzcdn.net/images/$type/$it/250x250-000000-80-0-0.jpg" }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("plasmawave_deezer_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("plasmawave_deezer_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun saveSecret(secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        if (!prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            throw DeezerException("Deezer oturumu cihaza kaydedilemedi")
        }
    }

    private fun readSecret(): String? {
        val value = prefs.getString("session", null) ?: return null
        try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            if (bytes.size <= 12) throw DeezerException("Geçersiz kayıt")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw DeezerException("Kayıtlı Deezer oturumu okunamadı; yeniden giriş yap")
        }
    }
}
