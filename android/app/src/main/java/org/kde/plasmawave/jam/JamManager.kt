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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

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
    }

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
    @Volatile private var lastSyncedTrackId: String? = null
    @Volatile private var lastSyncApplyMs: Long = 0L

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
        val clientId = "pw-host-$code-${Random.nextInt(1000, 9999)}"
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
                    while (isActive && _state.value.mode == JamMode.HOST && client.isConnected) {
                        val pState = playbackManager.overview.value
                        val sig = "${pState.currentEntry?.track?.id}|${pState.isPlaying}|${pState.queueSnapshot?.entries?.size}|${_state.value.allowGuestControls}"
                        val now = System.currentTimeMillis()
                        if (sig != lastSignature || (pState.isPlaying && now - lastPublishMs >= 4000L) || now - lastPublishMs >= 10_000L) {
                            lastSignature = sig
                            lastPublishMs = now
                            client.publish(stateTopic(code), buildHostStateJson().toString(), retain = true)
                        }
                        delay(700L)
                    }
                }
            },
            onError = { err ->
                Log.w(TAG, "Relay unavailable (LAN still active): $err")
                _state.value = _state.value.copy(isRelayConnected = false)
            }
        )
    }

    private suspend fun handleRelayCommand(payload: String) {
        val req = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val guest = req.optString("guestName", "").trim()
        if (guest.isNotBlank()) {
            guestLastSeen[guest] = System.currentTimeMillis()
            refreshParticipantsList()
        }
        when (req.optString("type", "")) {
            "join" -> {
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
            "add_track" -> {
                val trackObj = req.optJSONObject("track") ?: return
                val track = runCatching { jsonTrack(trackObj) }.getOrNull() ?: return
                withContext(Dispatchers.Main) {
                    playbackManager.addToQueue(listOf(track))
                }
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
            "control" -> {
                if (!_state.value.allowGuestControls) return
                val action = req.optString("action", "")
                withContext(Dispatchers.Main) {
                    when (action) {
                        "toggle" -> playbackManager.togglePlayPause()
                        "next" -> playbackManager.skipNext()
                        "prev" -> playbackManager.skipPrevious()
                        "seek" -> playbackManager.seekTo(req.optLong("positionMs", 0L))
                    }
                }
                delay(200)
                mqttClient?.publish(stateTopic(_state.value.jamCode), buildHostStateJson().toString(), retain = true)
            }
        }
    }

    fun joinJamByCode(jamCode: String, guestName: String = _state.value.guestName) {
        val cleanCode = jamCode.trim().uppercase().replace("PW-", "")
        if (cleanCode.length < 4) {
            _state.value = _state.value.copy(errorMessage = "Geçerli bir Jam kodu girin (örn: A7K2M9)")
            return
        }
        val cleanGuest = guestName.trim().ifEmpty { Build.MODEL.ifBlank { "Misafir" } }

        stopJam()
        lastSyncedTrackId = null
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
        val clientId = "pw-guest-$cleanCode-${Random.nextInt(1000, 9999)}"
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
        val cleanAddr = hostAddress.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleanAddr.isBlank() || !cleanAddr.contains(':')) {
            _state.value = _state.value.copy(errorMessage = "Geçerli bir IP:Port adresi girin (örn: 192.168.1.25:8765)")
            return
        }
        val cleanCode = jamCode.trim().uppercase()
        val cleanGuest = guestName.trim().ifEmpty { Build.MODEL.ifBlank { "Misafir" } }

        stopJam()
        lastSyncedTrackId = null

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
        lastSyncedTrackId = null
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
                        .put("track", trackJson(track))
                        .toString()
                    mqttClient?.publish(cmdTopic(current.jamCode), body)
                    _state.value = _state.value.copy(statusMessage = "'${track.title}' Jam sırasına eklendi")
                } else {
                    scope.launch {
                        try {
                            val body = JSONObject()
                                .put("code", current.jamCode)
                                .put("track", trackJson(track))
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
        if (current.mode != JamMode.GUEST) return
        if (isRelayGuest) {
            val body = JSONObject()
                .put("type", "control")
                .put("guestName", current.guestName)
                .put("action", action)
                .put("positionMs", positionMs)
                .toString()
            mqttClient?.publish(cmdTopic(current.jamCode), body)
        } else {
            scope.launch {
                try {
                    val body = JSONObject()
                        .put("code", current.jamCode)
                        .put("action", action)
                        .put("positionMs", positionMs)
                        .toString()
                    httpRequest("POST", "http://${current.hostAddress}/jam/control", body)
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
        val hostName = json.optString("hostName", "Ev Sahibi")
        val allowControls = json.optBoolean("allowGuestControls", true)
        val remotePlaying = json.optBoolean("isPlaying", false)
        val remotePos = json.optLong("positionMs", 0L)
        val updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())

        val participantsArr = json.optJSONArray("participants") ?: JSONArray()
        val participants = buildList {
            for (i in 0 until participantsArr.length()) {
                participantsArr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val remoteTrackJson = json.optJSONObject("currentTrack")
        val remoteTrack = remoteTrackJson?.let { runCatching { jsonTrack(it) }.getOrNull() }

        val queueArr = json.optJSONArray("queue") ?: JSONArray()
        val remoteQueue = buildList {
            for (i in 0 until queueArr.length()) {
                queueArr.optJSONObject(i)?.let { obj ->
                    runCatching { jsonTrack(obj) }.getOrNull()?.let(::add)
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

        val localState = playbackManager.overview.value
        val localTrackId = localState.currentEntry?.track?.id
        val now = System.currentTimeMillis()
        val elapsed = if (remotePlaying) (now - updatedAtMs).coerceIn(0L, 4000L) else 0L
        val targetPos = (remotePos + elapsed).coerceAtLeast(0L)

        withContext(Dispatchers.Main) {
            if (localTrackId != remoteTrack.id && lastSyncedTrackId != remoteTrack.id) {
                lastSyncedTrackId = remoteTrack.id
                lastSyncApplyMs = now
                val tracksToLoad = if (remoteQueue.any { it.id == remoteTrack.id }) {
                    remoteQueue
                } else {
                    listOf(remoteTrack) + remoteQueue
                }
                val startIndex = tracksToLoad.indexOfFirst { it.id == remoteTrack.id }.coerceAtLeast(0)
                playbackManager.playTrackList(tracksToLoad, startIndex, targetPos)
                if (!remotePlaying) {
                    delay(250)
                    if (playbackManager.overview.value.isPlaying) {
                        playbackManager.togglePlayPause()
                    }
                }
                return@withContext
            }

            if (now - lastSyncApplyMs < 1200L) return@withContext

            if (localState.isPlaying != remotePlaying) {
                playbackManager.togglePlayPause()
                lastSyncApplyMs = now
            }

            val localPos = playbackManager.progress.value.positionMs
            if (abs(localPos - targetPos) > SYNC_DRIFT_THRESHOLD_MS) {
                playbackManager.seekTo(targetPos)
                lastSyncApplyMs = now
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
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength.coerceAtMost(256 * 1024))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val (status, contentType, responseBody) = when {
                method == "GET" && path == "/" -> {
                    Triple(200, "text/html; charset=utf-8", buildWebJamHtml())
                }
                method == "GET" && path == "/jam/state" -> {
                    val params = parseQueryParams(query)
                    val code = params["code"]?.trim()?.uppercase().orEmpty()
                    val guest = params["name"]?.trim().orEmpty()
                    val current = _state.value
                    if (current.jamCode.isNotEmpty() && code.isNotEmpty() && code != current.jamCode) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else {
                        if (guest.isNotBlank()) {
                            guestLastSeen[guest] = System.currentTimeMillis()
                            refreshParticipantsList()
                        }
                        Triple(200, "application/json", buildHostStateJson().toString())
                    }
                }
                method == "POST" && path == "/jam/queue/add" -> {
                    val req = runCatching { JSONObject(body) }.getOrNull()
                    val code = req?.optString("code", "")?.trim()?.uppercase().orEmpty()
                    val trackObj = req?.optJSONObject("track")
                    if (code.isNotEmpty() && code != _state.value.jamCode) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else if (trackObj == null) {
                        Triple(400, "application/json", JSONObject().put("error", "Geçersiz şarkı").toString())
                    } else {
                        val track = runCatching { jsonTrack(trackObj) }.getOrNull()
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
                    if (code.isNotEmpty() && code != _state.value.jamCode) {
                        Triple(403, "application/json", JSONObject().put("error", "Jam kodu hatalı").toString())
                    } else if (!_state.value.allowGuestControls) {
                        Triple(403, "application/json", JSONObject().put("error", "Ev sahibi misafir kontrolünü kapattı").toString())
                    } else {
                        withContext(Dispatchers.Main) {
                            when (action) {
                                "toggle" -> playbackManager.togglePlayPause()
                                "next" -> playbackManager.skipNext()
                                "prev" -> playbackManager.skipPrevious()
                                "seek" -> playbackManager.seekTo(req?.optLong("positionMs", 0L) ?: 0L)
                            }
                        }
                        Triple(200, "application/json", JSONObject().put("ok", true).toString())
                    }
                }
                else -> Triple(404, "application/json", JSONObject().put("error", "Not found").toString())
            }

            val respBytes = responseBody.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $status OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${respBytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            output.write(headers)
            output.write(respBytes)
            output.flush()
        }
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
        val progress = playbackManager.progress.value
        val currentTrack = pState.currentEntry?.track
        val queueArr = JSONArray().apply {
            pState.queueSnapshot?.entries?.forEach { entry ->
                put(trackJson(entry.track))
            }
        }
        val partArr = JSONArray().apply {
            current.participants.forEach { put(it) }
        }
        return JSONObject().apply {
            put("jamCode", current.jamCode)
            put("hostName", current.hostName)
            put("allowGuestControls", current.allowGuestControls)
            put("isPlaying", pState.isPlaying)
            put("positionMs", progress.positionMs)
            put("updatedAtMs", System.currentTimeMillis())
            put("currentTrack", currentTrack?.let { trackJson(it) } ?: JSONObject.NULL)
            put("queue", queueArr)
            put("participants", partArr)
        }
    }

    private fun buildWebJamHtml(): String {
        val code = _state.value.jamCode
        return """
            <!doctype html>
            <html lang="tr">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>PlasmaWave Jam • $code</title>
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
                <span class="badge">JAM KODU: $code</span>
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
                async function refresh(){
                  const r = await fetch('/jam/state?code=$code&name=Web+Misafir');
                  const d = await r.json();
                  document.getElementById('title').textContent = d.currentTrack ? d.currentTrack.title : 'Çalan şarkı yok';
                  document.getElementById('artist').textContent = d.currentTrack ? d.currentTrack.artist : '';
                  document.getElementById('parts').textContent = 'Katılımcılar: ' + (d.participants || []).join(', ');
                  const q = document.getElementById('queue');
                  q.innerHTML = (d.queue || []).map((t,i)=>'<div class="q-item">'+(i+1)+'. '+t.title+' — '+t.artist+'</div>').join('');
                }
                async function ctrl(action){
                  await fetch('/jam/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:'$code',action})});
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
                3 -> if (b == '\n'.code) break else 0
                else -> 0
            }
        }
        return buffer.toByteArray()
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
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
