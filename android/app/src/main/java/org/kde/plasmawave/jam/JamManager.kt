package org.kde.plasmawave.jam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.player.PlaybackManager
import org.kde.plasmawave.ui.jsonTrack
import org.kde.plasmawave.ui.trackJson
import uniffi.plasmawave_core.FfiTrack
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal val JAM_CODE_REGEX = Regex("^[A-Z0-9]{4,12}$")
private val IPV4_LITERAL_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

internal fun isValidJamCode(code: String): Boolean =
    JAM_CODE_REGEX.matches(code)

internal fun verifyJamCodeConstantTime(expectedCode: String, providedCode: String): Boolean {
    val exp = expectedCode.trim().uppercase()
    val prov = providedCode.trim().uppercase()
    if (!isValidJamCode(exp) || !isValidJamCode(prov)) return false
    return try {
        org.kde.plasmawave.PlasmaWaveApp.core.jamConstantTimeEq(exp, prov)
    } catch (_: UninitializedPropertyAccessException) {
        MessageDigest.isEqual(
            exp.toByteArray(Charsets.UTF_8),
            prov.toByteArray(Charsets.UTF_8)
        )
    }
}

internal fun parseValidatedLanHostPort(raw: String): String? {
    val trimmed = raw.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() || it in charArrayOf('/', '?', '#', '@', '\\') }) {
        return null
    }
    val colonIdx = trimmed.lastIndexOf(':')
    if (colonIdx <= 0 || colonIdx == trimmed.lastIndex) return null
    val host = trimmed.substring(0, colonIdx)
    val port = trimmed.substring(colonIdx + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null

    val match = IPV4_LITERAL_REGEX.matchEntire(host) ?: return null
    val octets = (1..4).map { match.groupValues[it].toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    val a = octets[0]
    val b = octets[1]
    val isPrivateOrLoopback = a == 10 ||
        a == 127 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
    return if (isPrivateOrLoopback) "$a.$b.${octets[2]}.${octets[3]}:$port" else null
}

internal fun sanitizeParticipantName(raw: String): String =
    raw.replace(Regex("\\p{Cntrl}"), "").trim().take(48)

internal fun canonicalJamTrackId(rawId: String?): String {
    if (rawId.isNullOrBlank()) return ""
    val trimmed = rawId.trim()
    val downloadDigits = trimmed.removePrefix("local:download-")
    if (downloadDigits != trimmed && downloadDigits.isNotEmpty() && downloadDigits.all(Char::isDigit)) {
        return "deezer:$downloadDigits"
    }
    return trimmed
}

internal fun normalizeTrackForJam(track: FfiTrack): FfiTrack {
    val rawId = track.id.trim()
    val downloadDigits = rawId.removePrefix("local:download-")
    if (downloadDigits != rawId && downloadDigits.isNotEmpty() && downloadDigits.all(Char::isDigit)) {
        return track.copy(
            id = "deezer:$downloadDigits",
            provider = "deezer",
            playbackUri = null,
            artworkUrl = track.artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        )
    }
    if (track.provider == "deezer") {
        val safeArt = track.artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val safeUri = track.playbackUri?.takeIf { it.startsWith("https://", ignoreCase = true) }
        return track.copy(artworkUrl = safeArt, playbackUri = safeUri)
    }
    return track
}

internal fun sanitizeRemoteJamTrack(track: FfiTrack): FfiTrack? {
    val normalized = normalizeTrackForJam(track)
    val cleanId = normalized.id.replace(Regex("\\p{Cntrl}"), "").trim().take(128)
    if (cleanId.isEmpty()) return null
    val safeArtwork = normalized.artworkUrl?.trim()?.takeIf { uri ->
        uri.length <= 1024 && uri.startsWith("https://", ignoreCase = true)
    }
    val safePlaybackUri = normalized.playbackUri?.trim()?.takeIf { uri ->
        if (uri.length > 2048) false
        else runCatching {
            val parsed = URL(uri)
            val h = parsed.host.lowercase()
            parsed.protocol == "https" && (h == "dzcdn.net" || h.endsWith(".dzcdn.net") || h == "deezer.com" || h.endsWith(".deezer.com"))
        }.getOrDefault(false)
    }
    val safeProvider = if (normalized.provider == "deezer") "deezer" else "local"
    if (safeProvider != "deezer" && safePlaybackUri == null) return null
    return normalized.copy(
        id = cleanId,
        provider = safeProvider,
        title = normalized.title.replace(Regex("\\p{Cntrl}"), "").trim().take(256),
        artist = normalized.artist.replace(Regex("\\p{Cntrl}"), "").trim().take(256),
        album = normalized.album.replace(Regex("\\p{Cntrl}"), "").trim().take(256),
        artworkUrl = safeArtwork,
        playbackUri = safePlaybackUri
    )
}

enum class JamMode { NONE, HOST, GUEST }

data class DiscoveredJam(
    val serviceName: String,
    val host: String,
    val port: Int,
    val jamCode: String
) {
    val address: String get() = "$host:$port"
}

data class JamUiState(
    val mode: JamMode = JamMode.NONE,
    val jamCode: String = "",
    val hostAddress: String = "",
    val isRelayConnected: Boolean = false,
    val hostName: String = Build.MODEL.ifBlank { "PlasmaWave" },
    val guestName: String = Build.MODEL.ifBlank { "Misafir" },
    val allowGuestControls: Boolean = true,
    val participants: List<String> = emptyList(),
    val discoveredJams: List<DiscoveredJam> = emptyList(),
    val isScanning: Boolean = false,
    val remoteQueue: List<FfiTrack> = emptyList(),
    val remoteTrack: FfiTrack? = null,
    val remoteIsPlaying: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Local-network Spotify Jam style shared listening session manager.
 * Supports Host (embedded HTTP server + NSD service advertisement) and
 * Guest (NSD auto-discovery + manual IP:Port + playback/queue sync).
 */
class JamManager(
    context: Context,
    private val playbackManager: PlaybackManager
) {
    companion object {
        private const val TAG = "JamManager"
        private const val SERVICE_TYPE = "_plasmawave-jam._tcp."
        private const val POLL_INTERVAL_MS = 1500L
        private const val PARTICIPANT_TTL_MS = 15_000L
        private const val SYNC_DRIFT_THRESHOLD_MS = 2800L
        private const val MAX_PARTICIPANTS = 64
        private const val MAX_REMOTE_QUEUE_SIZE = 200
        private const val MAX_HTTP_BODY_BYTES = 256 * 1024
    }

    private val secureRandom = SecureRandom()

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(JamUiState())
    val state: StateFlow<JamUiState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var guestPollJob: Job? = null
    private var relayPublishJob: Job? = null
    private var guestHeartbeatJob: Job? = null
    private var mqttClient: MqttRelayClient? = null
    private var isRelayGuest: Boolean = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val guestLastSeen = ConcurrentHashMap<String, Long>()
    @Volatile private var pendingLoadTrackKey: String? = null
    @Volatile private var pendingLoadStartMs: Long = 0L
    @Volatile private var lastSeekApplyMs: Long = 0L
    @Volatile private var lastAppliedSeekSeq: Long = -1L
    @Volatile private var hostSeekSeq: Long = 0L
    @Volatile private var lastAckedCmdId: String = ""
    @Volatile private var lastSentGuestCmdId: String = ""
    @Volatile private var pendingGuestActionUntilMs: Long = 0L

    private fun stateTopic(code: String) = "plasmawave/jam/v1/${code.uppercase()}/state"
    private fun cmdTopic(code: String) = "plasmawave/jam/v1/${code.uppercase()}/cmd"

    fun clearMessages() {
        _state.value = _state.value.copy(statusMessage = null, errorMessage = null)
    }

    fun setAllowGuestControls(allowed: Boolean) {
        if (_state.value.mode == JamMode.HOST) {
            _state.value = _state.value.copy(allowGuestControls = allowed)
        }
    }

    fun startHostJam(hostName: String = _state.value.hostName) {
        stopJam()
        val cleanHostName = hostName.trim().ifEmpty { Build.MODEL.ifBlank { "PlasmaWave" } }
        val code = generateJamCode()
        val localIp = findLocalIpv4Address() ?: "127.0.0.1"

        // 1. Start global Internet Relay (MQTT) so anyone on any network can join with the code
        startHostRelay(code)

        // 2. Start local LAN HTTP + NSD discovery for same-Wi-Fi 1-tap join & web browser
        serverJob = scope.launch {
            try {
                val socket = ServerSocket(0)
                serverSocket = socket
                val port = socket.localPort
                val address = "$localIp:$port"

                _state.value = _state.value.copy(
                    mode = JamMode.HOST,
                    jamCode = code,
                    hostAddress = address,
                    hostName = cleanHostName,
                    participants = listOf("$cleanHostName (Ev Sahibi)"),
                    statusMessage = "Jam başlatıldı! Kod: $code",
                    errorMessage = null
                )

                registerNsdService(code, port)

                while (isActive && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Host server error", e)
                _state.value = _state.value.copy(
                    mode = JamMode.HOST,
                    jamCode = code,
                    hostName = cleanHostName,
                    participants = listOf("$cleanHostName (Ev Sahibi)"),
                    statusMessage = "Jam başlatıldı! Kod: $code"
                )
            }
        }
    }

    private fun startHostRelay(code: String) {
        val client = MqttRelayClient(
            onMessage = { topic, payload ->
                if (topic == cmdTopic(code)) {
                    scope.launch { handleRelayCommand(payload) }
                }
            }
        )
        mqttClient = client
        val clientId = "pw-host-$code-${1000 + secureRandom.nextInt(9000)}"
        client.connect(
            clientId = clientId,
            onConnected = {
                _state.value = _state.value.copy(isRelayConnected = true)
                client.subscribe(cmdTopic(code))
                client.publish(stateTopic(code), buildHostStateJson().toString(), retain = true)

                relayPublishJob?.cancel()
                relayPublishJob = scope.launch {
                    var lastSignature = ""
                    var lastPublishMs = 0L
                    var lastPublishedPosMs = 0L
                    var lastPublishedPlaying = false
                    while (isActive && _state.value.mode == JamMode.HOST && client.isConnected) {
                        val pState = playbackManager.overview.value
                        val playing = playbackManager.isPlayingOrBuffering()
                        val currentPos = playbackManager.progress.value.positionMs
                        val now = System.currentTimeMillis()
                        val expectedPos = if (lastPublishedPlaying) {
                            lastPublishedPosMs + (now - lastPublishMs).coerceAtLeast(0L)
                        } else {
                            lastPublishedPosMs
                        }
                        val posJumped = lastPublishMs > 0L && abs(currentPos - expectedPos) > 1500L
                        if (posJumped) {
                            hostSeekSeq++
                        }
                        val sig = "${canonicalJamTrackId(pState.currentEntry?.track?.id)}|$playing|${pState.queueSnapshot?.entries?.size}|${_state.value.allowGuestControls}|$hostSeekSeq"
                        if (sig != lastSignature || posJumped || (playing && now - lastPublishMs >= 3500L) || now - lastPublishMs >= 9000L) {
                            lastSignature = sig
                            lastPublishMs = now
                            lastPublishedPosMs = currentPos
                            lastPublishedPlaying = playing
                            client.publish(stateTopic(code), buildHostStateJson().toString(), retain = true)
                        }
                        delay(400L)
                    }
                }
            },
            onError = { err ->
                Log.w(TAG, "Relay unavailable (LAN still active): $err")
                _state.value = _state.value.copy(isRelayConnected = false)
            }
        )
    }

    private fun recordGuestActivity(rawName: String) {
        val guest = sanitizeParticipantName(rawName)
        if (guest.isBlank()) return
        if (guestLastSeen.containsKey(guest) || guestLastSeen.size < MAX_PARTICIPANTS) {
            guestLastSeen[guest] = System.currentTimeMillis()
            refreshParticipantsList()
        }
    }

    private suspend fun executeHostControlCommand(action: String, positionMs: Long, cmdId: String) {
        val beforeTrackId = playbackManager.overview.value.currentEntry?.track?.id
        withContext(Dispatchers.Main) {
            when (action) {
                "toggle" -> playbackManager.togglePlayPause()
                "next" -> playbackManager.skipNext()
                "prev" -> playbackManager.skipPrevious()
                "seek" -> {
                    playbackManager.seekTo(positionMs.coerceAtLeast(0L))
                    hostSeekSeq++
                }
            }
        }
        if (action == "next" || action == "prev") {
            // Wait briefly for the asynchronous queue snapshot advance (fast SQLite/in-memory step)
            // without blocking on full CDN stream buffering.
            var waited = 0L
            while (waited < 600L && playbackManager.overview.value.currentEntry?.track?.id == beforeTrackId) {
                delay(40L)
                waited += 40L
            }
            hostSeekSeq++
        } else {
            delay(50L)
        }
        if (cmdId.isNotBlank()) {
            lastAckedCmdId = cmdId.take(48)
        }
    }

    private suspend fun handleRelayCommand(payload: String) {
        if (payload.length > MAX_HTTP_BODY_BYTES) return
        val req = runCatching { JSONObject(payload) }.getOrNull() ?: return
        recordGuestActivity(req.optString("guestName", ""))
        when (req.optString("type", "")) {
            "join" -> {
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
            "add_track" -> {
                val trackObj = req.optJSONObject("track") ?: return
                val track = runCatching { jsonTrack(trackObj) }.getOrNull()?.let(::sanitizeRemoteJamTrack) ?: return
                withContext(Dispatchers.Main) {
                    playbackManager.addToQueue(listOf(track))
                }
                delay(80L)
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
            "control" -> {
                if (!_state.value.allowGuestControls) return
                val action = req.optString("action", "")
                val pos = req.optLong("positionMs", 0L).coerceAtLeast(0L)
                val cmdId = req.optString("cmdId", "")
                executeHostControlCommand(action, pos, cmdId)
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
        }
    }

    fun joinJamByCode(jamCode: String, guestName: String = _state.value.guestName) {
        val cleanCode = jamCode.trim().uppercase().replace("PW-", "")
        if (!isValidJamCode(cleanCode)) {
            _state.value = _state.value.copy(errorMessage = "Geçerli bir Jam kodu girin (örn: A7K2M9)")
            return
        }
        val cleanGuest = sanitizeParticipantName(guestName).ifEmpty { Build.MODEL.ifBlank { "Misafir" }.take(48) }

        stopJam()
        isRelayGuest = true

        _state.value = _state.value.copy(
            mode = JamMode.GUEST,
            jamCode = cleanCode,
            hostAddress = "İnternet (Kod: $cleanCode)",
            guestName = cleanGuest,
            statusMessage = "#$cleanCode Jam oturumuna bağlanılıyor...",
            errorMessage = null
        )

        var receivedFirstState = false
        val client = MqttRelayClient(
            onMessage = { topic, payload ->
                if (topic == stateTopic(cleanCode)) {
                    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@MqttRelayClient
                    if (json.optBoolean("ended", false)) {
                        scope.launch {
                            _state.value = _state.value.copy(errorMessage = "Jam oturumu ev sahibi tarafından sonlandırıldı.")
                            stopJam()
                        }
                        return@MqttRelayClient
                    }
                    receivedFirstState = true
                    scope.launch { applyRemoteState(json) }
                }
            },
            onDisconnected = { err ->
                if (_state.value.mode == JamMode.GUEST && isRelayGuest) {
                    _state.value = _state.value.copy(
                        isRelayConnected = false,
                        errorMessage = err ?: "Jam bağlantısı koptu"
                    )
                }
            }
        )
        mqttClient = client
        val clientId = "pw-guest-$cleanCode-${1000 + secureRandom.nextInt(9000)}"
        client.connect(
            clientId = clientId,
            onConnected = {
                _state.value = _state.value.copy(isRelayConnected = true)
                client.subscribe(stateTopic(cleanCode))
                val joinMsg = JSONObject()
                    .put("type", "join")
                    .put("guestName", cleanGuest)
                    .toString()
                client.publish(cmdTopic(cleanCode), joinMsg)

                guestHeartbeatJob?.cancel()
                guestHeartbeatJob = scope.launch {
                    delay(4500L)
                    if (!receivedFirstState && _state.value.mode == JamMode.GUEST && isRelayGuest) {
                        _state.value = _state.value.copy(
                            errorMessage = "#$cleanCode kodlu aktif Jam bulunamadı."
                        )
                        stopJam()
                        return@launch
                    }
                    while (isActive && _state.value.mode == JamMode.GUEST && isRelayGuest) {
                        delay(8000L)
                        client.publish(
                            cmdTopic(cleanCode),
                            JSONObject().put("type", "ping").put("guestName", cleanGuest).toString()
                        )
                    }
                }
            },
            onError = { err ->
                _state.value = _state.value.copy(errorMessage = err)
                stopJam()
            }
        )
    }

    fun joinJam(hostAddress: String, jamCode: String, guestName: String) {
        val cleanAddr = parseValidatedLanHostPort(hostAddress)
        if (cleanAddr == null) {
            _state.value = _state.value.copy(errorMessage = "Geçerli bir yerel ağ IP:Port adresi girin (örn: 192.168.1.25:8765)")
            return
        }
        val cleanCode = jamCode.trim().uppercase().replace("PW-", "")
        if (!isValidJamCode(cleanCode)) {
            _state.value = _state.value.copy(errorMessage = "Geçerli bir Jam kodu girin (örn: A7K2M9)")
            return
        }
        val cleanGuest = sanitizeParticipantName(guestName).ifEmpty { Build.MODEL.ifBlank { "Misafir" }.take(48) }

        stopJam()

        _state.value = _state.value.copy(
            mode = JamMode.GUEST,
            hostAddress = cleanAddr,
            jamCode = cleanCode,
            guestName = cleanGuest,
            statusMessage = "$cleanAddr Jam oturumuna bağlanılıyor...",
            errorMessage = null
        )

        guestPollJob = scope.launch {
            var connectedOnce = false
            while (isActive && _state.value.mode == JamMode.GUEST) {
                try {
                    val encCode = URLEncoder.encode(cleanCode, "UTF-8")
                    val encName = URLEncoder.encode(cleanGuest, "UTF-8")
                    val jsonStr = httpRequest("GET", "http://$cleanAddr/jam/state?code=$encCode&name=$encName")
                    val json = JSONObject(jsonStr)
                    if (json.has("error")) {
                        _state.value = _state.value.copy(
                            errorMessage = json.optString("error", "Jam kodu hatalı")
                        )
                        stopJam()
                        break
                    }
                    connectedOnce = true
                    applyRemoteState(json)
                } catch (e: Exception) {
                    if (!connectedOnce) {
                        _state.value = _state.value.copy(
                            errorMessage = "Jam'e bağlanılamadı: ${e.message}"
                        )
                        stopJam()
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopJam() {
        val wasHost = _state.value.mode == JamMode.HOST
        val oldCode = _state.value.jamCode
        if (wasHost && oldCode.isNotBlank()) {
            mqttClient?.publish(stateTopic(oldCode), JSONObject().put("ended", true).toString(), retain = true)
        }
        relayPublishJob?.cancel()
        relayPublishJob = null
        guestHeartbeatJob?.cancel()
        guestHeartbeatJob = null
        mqttClient?.disconnect()
        mqttClient = null
        isRelayGuest = false

        guestPollJob?.cancel()
        guestPollJob = null
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        unregisterNsdService()
        guestLastSeen.clear()
        pendingLoadTrackKey = null
        pendingLoadStartMs = 0L
        lastSeekApplyMs = 0L
        lastAppliedSeekSeq = -1L
        hostSeekSeq = 0L
        lastAckedCmdId = ""
        lastSentGuestCmdId = ""
        pendingGuestActionUntilMs = 0L
        _state.value = _state.value.copy(
            mode = JamMode.NONE,
            jamCode = "",
            hostAddress = "",
            isRelayConnected = false,
            participants = emptyList(),
            remoteQueue = emptyList(),
            remoteTrack = null
        )
    }

    fun addTrackToJam(track: FfiTrack) {
        val current = _state.value
        val normalized = normalizeTrackForJam(track)
        when (current.mode) {
            JamMode.HOST -> {
                playbackManager.addToQueue(listOf(track))
                _state.value = current.copy(statusMessage = "'${track.title}' Jam sırasına eklendi")
            }
            JamMode.GUEST -> {
                if (isRelayGuest) {
                    val body = JSONObject()
                        .put("type", "add_track")
                        .put("guestName", current.guestName)
                        .put("track", trackJson(normalized))
                        .toString()
                    mqttClient?.publish(cmdTopic(current.jamCode), body)
                    _state.value = _state.value.copy(statusMessage = "'${track.title}' Jam sırasına eklendi")
                } else {
                    scope.launch {
                        try {
                            val body = JSONObject()
                                .put("code", current.jamCode)
                                .put("track", trackJson(normalized))
                                .toString()
                            httpRequest("POST", "http://${current.hostAddress}/jam/queue/add", body)
                            _state.value = _state.value.copy(statusMessage = "'${track.title}' Jam sırasına eklendi")
                        } catch (e: Exception) {
                            _state.value = _state.value.copy(errorMessage = "Şarkı eklenemedi: ${e.message}")
                        }
                    }
                }
            }
            JamMode.NONE -> Unit
        }
    }

    fun sendGuestControl(action: String, positionMs: Long = 0L) {
        val current = _state.value
        if (current.mode != JamMode.GUEST || !current.allowGuestControls) return
        val safePos = positionMs.coerceAtLeast(0L)
        val cmdId = "${System.currentTimeMillis()}-${secureRandom.nextInt(10_000)}"
        lastSentGuestCmdId = cmdId
        pendingGuestActionUntilMs = System.currentTimeMillis() + 2400L

        // Optimistic immediate local execution on Guest so seek/skip/toggle responds in 0ms
        scope.launch(Dispatchers.Main) {
            when (action) {
                "toggle" -> playbackManager.togglePlayPause()
                "seek" -> {
                    playbackManager.seekTo(safePos)
                    lastSeekApplyMs = System.currentTimeMillis()
                }
                "next" -> playbackManager.skipNext()
                "prev" -> playbackManager.skipPrevious()
            }
        }

        if (isRelayGuest) {
            val body = JSONObject()
                .put("type", "control")
                .put("guestName", current.guestName)
                .put("action", action)
                .put("positionMs", safePos)
                .put("cmdId", cmdId)
                .toString()
            mqttClient?.publish(cmdTopic(current.jamCode), body)
        } else {
            scope.launch {
                try {
                    val body = JSONObject()
                        .put("code", current.jamCode)
                        .put("action", action)
                        .put("positionMs", safePos)
                        .put("cmdId", cmdId)
                        .toString()
                    val resp = httpRequest("POST", "http://${current.hostAddress}/jam/control", body)
                    runCatching { JSONObject(resp).optJSONObject("state") }
                        .getOrNull()
                        ?.let { hostState -> applyRemoteState(hostState) }
                } catch (e: Exception) {
                    _state.value = _state.value.copy(errorMessage = "Komut gönderilemedi: ${e.message}")
                }
            }
        }
    }

    fun startDiscovery() {
        val nsd = nsdManager ?: return
        stopDiscovery()
        _state.value = _state.value.copy(isScanning = true, discoveredJams = emptyList())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                _state.value = _state.value.copy(isScanning = false)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _state.value = _state.value.copy(isScanning = false)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _state.value = _state.value.copy(isScanning = false)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val updated = _state.value.discoveredJams.filterNot { it.serviceName == serviceInfo.serviceName }
                _state.value = _state.value.copy(discoveredJams = updated)
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("plasmawave-jam")) return
                if (_state.value.mode == JamMode.HOST && serviceInfo.serviceName.endsWith(_state.value.jamCode)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        val port = info.port
                        val code = info.serviceName.substringAfterLast("-", "")
                        val item = DiscoveredJam(
                            serviceName = info.serviceName,
                            host = host,
                            port = port,
                            jamCode = code
                        )
                        val existing = _state.value.discoveredJams.filterNot { it.address == item.address }
                        _state.value = _state.value.copy(discoveredJams = existing + item)
                    }
                })
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            _state.value = _state.value.copy(isScanning = false)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        _state.value = _state.value.copy(isScanning = false)
    }

    fun release() {
        stopDiscovery()
        stopJam()
    }

    private suspend fun applyRemoteState(json: JSONObject) {
        val hostName = sanitizeParticipantName(json.optString("hostName", "Ev Sahibi")).ifEmpty { "Ev Sahibi" }
        val allowControls = json.optBoolean("allowGuestControls", true)
        val remotePlaying = json.optBoolean("isPlaying", false)
        val remotePos = json.optLong("positionMs", 0L).coerceAtLeast(0L)
        val updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())

        val participantsArr = json.optJSONArray("participants") ?: JSONArray()
        val participants = buildList {
            for (i in 0 until minOf(participantsArr.length(), MAX_PARTICIPANTS)) {
                sanitizeParticipantName(participantsArr.optString(i)).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val remoteTrackJson = json.optJSONObject("currentTrack")
        val remoteTrack = remoteTrackJson?.let { runCatching { jsonTrack(it) }.getOrNull()?.let(::sanitizeRemoteJamTrack) }

        val queueArr = json.optJSONArray("queue") ?: JSONArray()
        val remoteQueue = buildList {
            for (i in 0 until minOf(queueArr.length(), MAX_REMOTE_QUEUE_SIZE)) {
                queueArr.optJSONObject(i)?.let { obj ->
                    runCatching { jsonTrack(obj) }.getOrNull()?.let(::sanitizeRemoteJamTrack)?.let(::add)
                }
            }
        }

        _state.value = _state.value.copy(
            hostName = hostName,
            allowGuestControls = allowControls,
            participants = participants,
            remoteTrack = remoteTrack,
            remoteQueue = remoteQueue,
            remoteIsPlaying = remotePlaying,
            statusMessage = null
        )

        if (remoteTrack == null) return

        val remoteSeekSeq = json.optLong("seekSeq", -1L)
        val ackedCmdId = json.optString("lastCmdId", "")
        val now = System.currentTimeMillis()

        if (lastSentGuestCmdId.isNotEmpty() && ackedCmdId == lastSentGuestCmdId) {
            pendingGuestActionUntilMs = 0L
        } else if (now < pendingGuestActionUntilMs) {
            // Ignore stale in-flight Host state packets sent before Host processed our latest Guest command
            return
        }

        val elapsed = if (remotePlaying) (now - updatedAtMs).coerceIn(0L, 4000L) else 0L
        val targetPos = (remotePos + elapsed).coerceAtLeast(0L)

        withContext(Dispatchers.Main) {
            val localTrackId = playbackManager.overview.value.currentEntry?.track?.id
            val localKey = canonicalJamTrackId(localTrackId)
            val remoteKey = canonicalJamTrackId(remoteTrack.id)

            if (localKey != remoteKey) {
                if (pendingLoadTrackKey == remoteKey && now - pendingLoadStartMs < 2500L) {
                    return@withContext
                }
                pendingLoadTrackKey = remoteKey
                pendingLoadStartMs = now
                lastAppliedSeekSeq = remoteSeekSeq
                lastSeekApplyMs = now

                val tracksToLoad = if (remoteQueue.any { canonicalJamTrackId(it.id) == remoteKey }) {
                    remoteQueue
                } else {
                    listOf(remoteTrack) + remoteQueue
                }
                val startIndex = tracksToLoad.indexOfFirst { canonicalJamTrackId(it.id) == remoteKey }.coerceAtLeast(0)
                playbackManager.playTrackList(tracksToLoad, startIndex, targetPos)
                if (!remotePlaying) {
                    delay(250)
                    playbackManager.setPlaying(false)
                }
                return@withContext
            }

            pendingLoadTrackKey = null

            // Sync play/pause using playWhenReady+buffering awareness so buffering never triggers a false pause!
            if (playbackManager.isPlayingOrBuffering() != remotePlaying) {
                playbackManager.setPlaying(remotePlaying)
            }

            val explicitSeekChanged = remoteSeekSeq >= 0L && remoteSeekSeq != lastAppliedSeekSeq
            val driftThreshold = if (explicitSeekChanged) 900L else SYNC_DRIFT_THRESHOLD_MS
            val localPos = playbackManager.progress.value.positionMs
            val settleElapsed = now - lastSeekApplyMs

            if (abs(localPos - targetPos) > driftThreshold && (explicitSeekChanged || settleElapsed >= 1000L)) {
                lastAppliedSeekSeq = remoteSeekSeq
                lastSeekApplyMs = now
                playbackManager.seekTo(targetPos)
            } else if (explicitSeekChanged) {
                lastAppliedSeekSeq = remoteSeekSeq
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val headerBytes = readHeaders(input) ?: return
            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")

            var contentLength = 0
            for (line in lines.drop(1)) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    val parsedLen = line.substringAfter(":").trim().toIntOrNull()
                    if (parsedLen == null || parsedLen < 0) {
                        writeHttpResponse(output, 400, "application/json", JSONObject().put("error", "Invalid Content-Length").toString())
                        return
                    }
                    if (parsedLen > MAX_HTTP_BODY_BYTES) {
                        writeHttpResponse(output, 413, "application/json", JSONObject().put("error", "Payload too large").toString())
                        return
                    }
                    contentLength = parsedLen
                }
            }
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < contentLength) return
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val (status, contentType, responseBody) = when {
                method == "GET" && path == "/" -> {
                    val params = parseQueryParams(query)
                    val code = params["code"]?.trim()?.uppercase().orEmpty()
                    if (verifyJamCodeConstantTime(_state.value.jamCode, code)) {
                        Triple(200, "text/html; charset=utf-8", buildWebJamHtml(code))
                    } else {
                        Triple(403, "text/html; charset=utf-8", buildLockedWebJamHtml(hasAttempt = code.isNotEmpty()))
                    }
                }
                method == "GET" && path == "/jam/state" -> {
                    val params = parseQueryParams(query)
                    val code = params["code"]?.trim()?.uppercase().orEmpty()
                    val guest = params["name"].orEmpty()
                    val current = _state.value
                    if (!verifyJamCodeConstantTime(current.jamCode, code)) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else {
                        recordGuestActivity(guest)
                        Triple(200, "application/json", buildHostStateJson().toString())
                    }
                }
                method == "POST" && path == "/jam/queue/add" -> {
                    val req = runCatching { JSONObject(body) }.getOrNull()
                    val code = req?.optString("code", "")?.trim()?.uppercase().orEmpty()
                    val trackObj = req?.optJSONObject("track")
                    if (!verifyJamCodeConstantTime(_state.value.jamCode, code)) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else if (trackObj == null) {
                        Triple(400, "application/json", JSONObject().put("error", "Geçersiz şarkı").toString())
                    } else {
                        val track = runCatching { jsonTrack(trackObj) }.getOrNull()?.let(::sanitizeRemoteJamTrack)
                        if (track != null) {
                            withContext(Dispatchers.Main) {
                                playbackManager.addToQueue(listOf(track))
                            }
                            Triple(200, "application/json", JSONObject().put("ok", true).toString())
                        } else {
                            Triple(400, "application/json", JSONObject().put("error", "Şarkı çözümlenemedi").toString())
                        }
                    }
                }
                method == "POST" && path == "/jam/control" -> {
                    val req = runCatching { JSONObject(body) }.getOrNull()
                    val code = req?.optString("code", "")?.trim()?.uppercase().orEmpty()
                    val action = req?.optString("action", "").orEmpty()
                    val pos = (req?.optLong("positionMs", 0L) ?: 0L).coerceAtLeast(0L)
                    val cmdId = req?.optString("cmdId", "").orEmpty()
                    if (!verifyJamCodeConstantTime(_state.value.jamCode, code)) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else if (!_state.value.allowGuestControls) {
                        Triple(403, "application/json", JSONObject().put("error", "Ev sahibi misafir kontrolünü kapattı").toString())
                    } else {
                        executeHostControlCommand(action, pos, cmdId)
                        val hostState = buildHostStateJson()
                        mqttClient?.publish(stateTopic(_state.value.jamCode), hostState.toString(), retain = true)
                        Triple(200, "application/json", JSONObject().put("ok", true).put("state", hostState).toString())
                    }
                }
                else -> Triple(404, "application/json", JSONObject().put("error", "Not found").toString())
            }

            writeHttpResponse(output, status, contentType, responseBody)
        }
    }

    private fun writeHttpResponse(
        output: BufferedOutputStream,
        status: Int,
        contentType: String,
        responseBody: String
    ) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val respBytes = responseBody.toByteArray(Charsets.UTF_8)
        val isHtml = contentType.startsWith("text/html")
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${respBytes.size}\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cache-Control: no-store\r\n")
            if (isHtml) {
                append("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(headers)
        output.write(respBytes)
        output.flush()
    }

    private fun refreshParticipantsList() {
        val now = System.currentTimeMillis()
        guestLastSeen.entries.removeIf { now - it.value > PARTICIPANT_TTL_MS }
        val current = _state.value
        val list = listOf("${current.hostName} (Ev Sahibi)") + guestLastSeen.keys.sorted()
        if (current.participants != list) {
            _state.value = current.copy(participants = list)
        }
    }

    private fun buildHostStateJson(): JSONObject {
        refreshParticipantsList()
        val current = _state.value
        val pState = playbackManager.overview.value
        val playing = playbackManager.isPlayingOrBuffering()
        val progress = playbackManager.progress.value
        val currentTrack = pState.currentEntry?.track?.let(::normalizeTrackForJam)
        val queueArr = JSONArray().apply {
            pState.queueSnapshot?.entries?.forEach { entry ->
                put(trackJson(normalizeTrackForJam(entry.track)))
            }
        }
        val partArr = JSONArray().apply {
            current.participants.forEach { put(it) }
        }
        return JSONObject().apply {
            put("jamCode", current.jamCode)
            put("hostName", current.hostName)
            put("allowGuestControls", current.allowGuestControls)
            put("isPlaying", playing)
            put("positionMs", progress.positionMs)
            put("seekSeq", hostSeekSeq)
            put("lastCmdId", lastAckedCmdId)
            put("updatedAtMs", System.currentTimeMillis())
            put("currentTrack", currentTrack?.let { trackJson(it) } ?: JSONObject.NULL)
            put("queue", queueArr)
            put("participants", partArr)
        }
    }

    private fun buildLockedWebJamHtml(hasAttempt: Boolean): String {
        val errHtml = if (hasAttempt) "<p style=\"color:#f87171\">Jam kodu geçersiz.</p>" else ""
        return """
            <!doctype html>
            <html lang="tr">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>PlasmaWave Jam</title>
              <style>
                body{font-family:system-ui,-apple-system,sans-serif;background:#121318;color:#f5f5f7;margin:0;padding:20px;}
                .card{background:#1d2028;border-radius:16px;padding:18px;max-width:420px;margin:40px auto;border:1px solid #2e323d;}
                h1{font-size:20px;margin:0 0 8px;}
                p{color:#a1a9b1;margin:4px 0 14px;font-size:14px;}
                input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2e323d;background:#121318;color:#fff;font-size:16px;text-transform:uppercase;margin-bottom:12px;}
                button{width:100%;padding:12px;border:none;border-radius:10px;background:#3daee9;color:#fff;font-weight:700;cursor:pointer;}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>PlasmaWave Jam</h1>
                <p>Oturuma katılmak için ev sahibinin paylaştığı Jam kodunu girin.</p>
                $errHtml
                <form method="get" action="/">
                  <input name="code" maxlength="12" pattern="[A-Za-z0-9]{4,12}" placeholder="JAM KODU" required />
                  <button type="submit">Katıl</button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    internal fun buildWebJamHtml(verifiedCode: String): String {
        val safeCode = verifiedCode.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(12)
        return """
            <!doctype html>
            <html lang="tr">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>PlasmaWave Jam • $safeCode</title>
              <style>
                body{font-family:system-ui,-apple-system,sans-serif;background:#121318;color:#f5f5f7;margin:0;padding:20px;}
                .card{background:#1d2028;border-radius:16px;padding:18px;max-width:480px;margin:0 auto 16px;border:1px solid #2e323d;}
                .badge{display:inline-block;background:#3daee922;color:#3daee9;padding:4px 10px;border-radius:999px;font-weight:700;font-size:12px;}
                h1{font-size:20px;margin:8px 0 4px;}
                p{color:#a1a9b1;margin:4px 0;}
                .row{display:flex;gap:8px;margin-top:12px;}
                button{flex:1;padding:12px;border:none;border-radius:10px;background:#3daee9;color:#fff;font-weight:700;cursor:pointer;}
                button.sec{background:#2a2e38;}
                .q-item{padding:8px 0;border-bottom:1px solid #2b2f3a;font-size:14px;}
              </style>
            </head>
            <body>
              <div class="card">
                <span class="badge">JAM KODU: $safeCode</span>
                <h1 id="title">Yükleniyor...</h1>
                <p id="artist">-</p>
                <p id="parts"></p>
                <div class="row">
                  <button class="sec" onclick="ctrl('prev')">⏮ Önceki</button>
                  <button onclick="ctrl('toggle')">⏯ Çal/Duraklat</button>
                  <button class="sec" onclick="ctrl('next')">Sonraki ⏭</button>
                </div>
              </div>
              <div class="card">
                <h3 style="margin-top:0">Sıradaki Şarkılar</h3>
                <div id="queue"></div>
              </div>
              <script>
                const JAM_CODE = '$safeCode';
                async function refresh(){
                  const r = await fetch('/jam/state?code=' + encodeURIComponent(JAM_CODE) + '&name=Web+Misafir');
                  if (!r.ok) return;
                  const d = await r.json();
                  document.getElementById('title').textContent = d.currentTrack ? (d.currentTrack.title || '') : 'Çalan şarkı yok';
                  document.getElementById('artist').textContent = d.currentTrack ? (d.currentTrack.artist || '') : '';
                  document.getElementById('parts').textContent = 'Katılımcılar: ' + (d.participants || []).join(', ');
                  const q = document.getElementById('queue');
                  q.replaceChildren();
                  (d.queue || []).forEach((t, i) => {
                    const div = document.createElement('div');
                    div.className = 'q-item';
                    div.textContent = (i + 1) + '. ' + (t.title || '') + ' — ' + (t.artist || '');
                    q.appendChild(div);
                  });
                }
                async function ctrl(action){
                  await fetch('/jam/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:JAM_CODE,action})});
                  setTimeout(refresh, 300);
                }
                refresh(); setInterval(refresh, 2000);
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var state = 0
        while (buffer.size() < 16_384) {
            val b = input.read()
            if (b == -1) return null
            buffer.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) return buffer.toByteArray() else 0
                else -> 0
            }
        }
        return null
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else {
                val k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    private fun httpRequest(method: String, urlStr: String, jsonBody: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
        } finally {
            conn.disconnect()
        }
    }

    private fun registerNsdService(jamCode: String, port: Int) {
        val nsd = nsdManager ?: return
        unregisterNsdService()
        val info = NsdServiceInfo().apply {
            serviceName = "PlasmaWave-$jamCode"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unregisterNsdService() {
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
    }

    private fun findLocalIpv4Address(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun generateJamCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }
}
