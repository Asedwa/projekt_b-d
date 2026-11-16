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
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Lightweight, zero-dependency MQTT v3.1.1 TCP client for cross-network Jam relay.
 */
class MqttRelayClient(
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onDisconnected: (String?) -> Unit = {}
) {
    companion object {
        private const val TAG = "MqttRelayClient"
        private val BROKERS = listOf(
            "broker.emqx.io" to 1883,
            "broker.hivemq.com" to 1883
        )
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
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.soTimeout = 45_000
                    val out = s.getOutputStream()
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                    sendConnectPacket(out, clientId)
                    // Read CONNACK (4 bytes: 0x20, 0x02, flags, returnCode)
                    val b0 = inp.readUnsignedByte()
                    val remLen = readRemainingLength(inp)
                    val ackPayload = ByteArray(remLen)
                    inp.readFully(ackPayload)
                    if ((b0 and 0xF0) == 0x20 && remLen >= 2 && ackPayload[1].toInt() == 0) {
                        connectedSocket = s
                        socket = s
                        output = out
                        isConnected = true
                        startLoops(inp)
                        onConnected()
                        return@launch
                    } else {
                        s.close()
                        lastErr = "Broker bağlantıyı reddetti"
                    }
                } catch (e: Exception) {
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
                val topicBytes = topicFilter.toByteArray(Charsets.UTF_8)
                val body = ByteArrayOutputStream().apply {
                    // Packet Identifier = 1
                    write(0x00)
                    write(0x01)
                    // Topic filter
                    write((topicBytes.size shr 8) and 0xFF)
                    write(topicBytes.size and 0xFF)
                    write(topicBytes)
                    // QoS 0
                    write(0x00)
                }.toByteArray()

                synchronized(writeLock) {
                    out.write(0x82)
                    writeRemainingLength(out, body.size)
                    out.write(body)
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
                val topicBytes = topic.toByteArray(Charsets.UTF_8)
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                val body = ByteArrayOutputStream().apply {
                    write((topicBytes.size shr 8) and 0xFF)
                    write(topicBytes.size and 0xFF)
                    write(topicBytes)
                    write(payloadBytes)
                }.toByteArray()

                val headerByte = if (retain) 0x31 else 0x30
                synchronized(writeLock) {
                    out.write(headerByte)
                    writeRemainingLength(out, body.size)
                    out.write(body)
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
                    synchronized(writeLock) {
                        out.write(0xC0)
                        out.write(0x00)
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
                        val topicLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                        if (2 + topicLen <= payload.size) {
                            val topic = String(payload, 2, topicLen, Charsets.UTF_8)
                            val qos = (header shr 1) and 0x03
                            val msgOffset = 2 + topicLen + (if (qos > 0) 2 else 0)
                            if (msgOffset <= payload.size) {
                                val msg = String(payload, msgOffset, payload.size - msgOffset, Charsets.UTF_8)
                                onMessage(topic, msg)
                            }
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
        val clientBytes = clientId.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream().apply {
            // Protocol Name "MQTT"
            write(0x00); write(0x04)
            write("MQTT".toByteArray(Charsets.UTF_8))
            // Protocol Level 4 (v3.1.1)
            write(0x04)
            // Connect Flags: Clean Session (0x02)
            write(0x02)
            // Keep Alive = 30s
            write(0x00); write(0x1E)
            // Client ID
            write((clientBytes.size shr 8) and 0xFF)
            write(clientBytes.size and 0xFF)
            write(clientBytes)
        }.toByteArray()

        out.write(0x10)
        writeRemainingLength(out, body.size)
        out.write(body)
        out.flush()
    }

    private fun writeRemainingLength(out: OutputStream, length: Int) {
        var x = length
        do {
            var encoded = x % 128
            x /= 128
            if (x > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (x > 0)
    }

    private fun readRemainingLength(inp: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var digit: Int
        do {
            digit = inp.readUnsignedByte()
            value += (digit and 127) * multiplier
            multiplier *= 128
        } while ((digit and 128) != 0)
        return value
    }
}
