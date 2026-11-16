package org.kde.plasmawave.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Media3 data source backed by the shared [DeezerHttp.streamClient] connection pool so CDN
 * audio streams reuse warm TLS/HTTP2 sockets and avoid per-skip handshake delays.
 */
@UnstableApi
class DeezerDataSource(private val session: DeezerSession) : BaseDataSource(/* isNetwork = */ true) {
    private var requestedUri: Uri? = null
    private var stripeInput: StripeInput? = null
    private var activeResponse: Response? = null
    private var activeInputStream: InputStream? = null
    private var activeResponseHeaders: Map<String, List<String>> = emptyMap()
    private var activeTrackId: String? = null
    private var activeFormat: String? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        requestedUri = dataSpec.uri
        transferInitializing(dataSpec)
        if (dataSpec.uri.scheme != "deezer") {
            val length = openUpstream(dataSpec)
            opened = true
            transferStarted(dataSpec)
            return length
        }

        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Geçersiz şarkı kimliği")
        // Pin the stream format once playback begins so a mid-song Wi-Fi <-> 4.5G switch
        // never changes byte offsets or triggers HTTP 416 / decoder corruption.
        val lockedFormat = if (dataSpec.position > 0L) {
            (if (activeTrackId == id) activeFormat else null) ?: session.getResolvedTrackFormat(id)
        } else {
            null
        }
        val aligned = dataSpec.position - dataSpec.position % StripeInput.CHUNK_SIZE
        val skip = (dataSpec.position - aligned).toInt()

        var media = resolveSafe(id, lockedFormat, forceRefresh = false)
        var upstreamLength: Long
        var attempt = 0
        while (true) {
            val upstreamSpec = dataSpec.buildUpon()
                .setUri(media.url)
                .setPosition(aligned)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
            try {
                upstreamLength = openUpstream(upstreamSpec)
                break
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                closeUpstream()
                if (attempt == 0 && (e.responseCode == 403 || e.responseCode == 410)) {
                    attempt++
                    session.invalidateResolvedMedia(id)
                    media = resolveSafe(id, lockedFormat ?: media.format, forceRefresh = true)
                    continue
                }
                throw e
            } catch (e: Exception) {
                closeUpstream()
                throw e
            }
        }

        activeTrackId = id
        activeFormat = media.format

        val available = if (upstreamLength == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else (upstreamLength - skip).coerceAtLeast(0)
        val length = when {
            dataSpec.length == C.LENGTH_UNSET.toLong() -> available
            available == C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> minOf(dataSpec.length, available)
        }
        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val count = read(one, 0, 1)
                return if (count == -1) -1 else one[0].toInt() and 0xff
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeInputStream?.read(buffer, offset, length) ?: -1
        }
        stripeInput = StripeInput(input, media.trackId, aligned, skip, length)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun openUpstream(spec: DataSpec): Long {
        closeUpstream()
        val requestBuilder = Request.Builder()
            .url(spec.uri.toString())
            .header("User-Agent", "Mozilla/5.0 PlasmaWave")
        spec.httpRequestHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (spec.position > 0L) {
            requestBuilder.header("Range", "bytes=${spec.position}-")
        }
        val response = DeezerHttp.streamClient.newCall(requestBuilder.build()).execute()
        val code = response.code
        val headers = response.headers.toMultimap()
        if (code !in 200..299) {
            val message = response.message
            response.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code, message, null, headers, spec, ByteArray(0)
            )
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Boş ses akışı yanıtı")
        }
        activeResponse = response
        activeResponseHeaders = headers
        activeInputStream = body.byteStream()
        val contentLength = body.contentLength()
        return if (contentLength >= 0L) contentLength else C.LENGTH_UNSET.toLong()
    }

    private fun resolveSafe(id: String, lockedFormat: String?, forceRefresh: Boolean): DeezerGateway.Media {
        return try {
            session.resolve(id, lockedFormat = lockedFormat, forceRefresh = forceRefresh)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Deezer akışı çözümlenemedi", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = stripeInput?.read(buffer, offset, length)
            ?: activeInputStream?.read(buffer, offset, length)
            ?: C.RESULT_END_OF_INPUT
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = requestedUri
    override fun getResponseHeaders(): Map<String, List<String>> = activeResponseHeaders

    override fun close() {
        try {
            closeUpstream()
        } finally {
            requestedUri = null
            stripeInput = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeUpstream() {
        runCatching { activeInputStream?.close() }
        runCatching { activeResponse?.close() }
        activeInputStream = null
        activeResponse = null
        activeResponseHeaders = emptyMap()
    }
}
