package org.kde.plasmawave.jam

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Lightweight, zero-dependency MQTT v3.1.1 over TLS (port 8883) client for cross-network Jam relay.
 */
class MqttRelayClient(
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onDisconnected: (String?) -> Unit = {}
) {
    companion object {
        private const val TAG = "MqttRelayClient"
        internal const val MAX_PACKET_BYTES = 512 * 1024
        private val BROKERS = listOf(
            "broker.emqx.io" to 8883,
            "broker.hivemq.com" to 8883
        )

        internal fun readRemainingLengthBounded(
            inp: DataInputStream,
            maxBytes: Int = MAX_PACKET_BYTES
        ): Int {
            var multiplier = 1
            var value = 0
            var bytesRead = 0
            var digit: Int
            do {
                if (bytesRead >= 4) {
                    throw IOException("Malformed MQTT remaining length")
                }
                digit = inp.readUnsignedByte()
                bytesRead++
                value += (digit and 127) * multiplier
                if (value < 0 || value > maxBytes) {
                    throw IOException("MQTT packet exceeds maximum allowed size ($value bytes)")
                }
                multiplier *= 128
            } while ((digit and 128) != 0)
            return value
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private val writeLock = Any()
    @Volatile var isConnected: Boolean = false
        private set

    fun connect(
        clientId: String,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            var connectedSocket: Socket? = null
            var lastErr: String? = null
            for ((host, port) in BROKERS) {
                var rawSocket: Socket? = null
                var tlsSocket: SSLSocket? = null
                try {
                    val tcp = Socket()
                    rawSocket = tcp
                    tcp.connect(InetSocketAddress(host, port), 5000)
                    tcp.soTimeout = 45_000

                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = (sslFactory.createSocket(tcp, host, port, true) as SSLSocket).apply {
                        soTimeout = 45_000
                        enabledProtocols = supportedProtocols
                            .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                            .toTypedArray()
                            .takeIf { it.isNotEmpty() } ?: enabledProtocols
                        sslParameters = sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                            serverNames = listOf(SNIHostName(host))
                        }
                    }
                    tlsSocket = ssl
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        throw IOException("TLS sertifika alan adı doğrulaması başarısız: $host")
                    }

                    val out = ssl.getOutputStream()
                    val inp = DataInputStream(BufferedInputStream(ssl.getInputStream()))
                    sendConnectPacket(out, clientId)
                    // Read CONNACK (4 bytes: 0x20, 0x02, flags, returnCode)
                    val b0 = inp.readUnsignedByte()
                    val remLen = readRemainingLengthBounded(inp, 64)
                    val ackPayload = ByteArray(remLen)
                    inp.readFully(ackPayload)
                    if ((b0 and 0xF0) == 0x20 && remLen >= 2 && ackPayload[1].toInt() == 0) {
                        connectedSocket = ssl
                        socket = ssl
                        output = out
                        isConnected = true
                        startLoops(inp)
                        onConnected()
                        return@launch
                    } else {
                        runCatching { ssl.close() }
                        lastErr = "Broker bağlantıyı reddetti"
                    }
                } catch (e: Exception) {
                    runCatching { tlsSocket?.close() }
                    runCatching { rawSocket?.close() }
                    lastErr = e.message
                    Log.w(TAG, "Broker $host:$port failed: ${e.message}")
                }
            }
            if (connectedSocket == null) {
                isConnected = false
                onError(lastErr ?: "Jam sunucusuna bağlanılamadı")
            }
        }
    }

    fun subscribe(topicFilter: String) {
        scope.launch {
            try {
                val out = output ?: return@launch
                val packet = org.kde.plasmawave.PlasmaWaveApp.core.mqttEncodeSubscribe(1u, topicFilter)
                synchronized(writeLock) {
                    out.write(packet)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subscribe failed: ${e.message}")
            }
        }
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        scope.launch {
            try {
                val out = output ?: return@launch
                val packet = org.kde.plasmawave.PlasmaWaveApp.core.mqttEncodePublish(topic, payload, retain)
                synchronized(writeLock) {
                    out.write(packet)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Publish failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        isConnected = false
        readerJob?.cancel()
        pingJob?.cancel()
        val oldOutput = output
        val oldSocket = socket
        output = null
        socket = null
        scope.cancel()
        if (oldOutput != null || oldSocket != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    oldOutput?.let { out ->
                        synchronized(writeLock) {
                            out.write(0xE0)
                            out.write(0x00)
                            out.flush()
                        }
                    }
                }
                runCatching { oldSocket?.close() }
            }
        }
    }

    private fun startLoops(inp: DataInputStream) {
        pingJob = scope.launch {
            while (isActive && isConnected) {
                delay(20_000)
                try {
                    val out = output ?: break
                    val ping = org.kde.plasmawave.PlasmaWaveApp.core.mqttEncodePingreq()
                    synchronized(writeLock) {
                        out.write(ping)
                        out.flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        readerJob = scope.launch {
            try {
                while (isActive && isConnected) {
                    val header = inp.readUnsignedByte()
                    val remLen = readRemainingLength(inp)
                    val payload = ByteArray(remLen)
                    if (remLen > 0) inp.readFully(payload)

                    val packetType = header and 0xF0
                    if (packetType == 0x30 && payload.size >= 2) {
                        runCatching {
                            org.kde.plasmawave.PlasmaWaveApp.core.mqttDecodePublish(header.toUByte(), payload)
                        }.onSuccess { decoded ->
                            onMessage(decoded.topic, decoded.payload)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    isConnected = false
                    onDisconnected(e.message)
                }
            }
        }
    }

    private fun sendConnectPacket(out: OutputStream, clientId: String) {
        val packet = org.kde.plasmawave.PlasmaWaveApp.core.mqttEncodeConnect(clientId, 30u)
        out.write(packet)
        out.flush()
    }

    private fun readRemainingLength(inp: DataInputStream): Int =
        readRemainingLengthBounded(inp, MAX_PACKET_BYTES)
}
