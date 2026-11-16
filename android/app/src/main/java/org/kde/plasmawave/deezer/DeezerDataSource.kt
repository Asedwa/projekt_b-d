package org.kde.plasmawave.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/** Media3 data source that resolves a fresh account-authorized URL on its loader thread. */
@UnstableApi
class DeezerDataSource(private val session: DeezerSession) : DataSource {
    private val upstream = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 PlasmaWave")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()
    private var requestedUri: Uri? = null
    private var stripeInput: StripeInput? = null
    private var activeTrackId: String? = null
    private var activeFormat: String? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        requestedUri = dataSpec.uri
        if (dataSpec.uri.scheme != "deezer") return upstream.open(dataSpec)
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
                upstreamLength = upstream.open(upstreamSpec)
                break
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                runCatching { upstream.close() }
                if (attempt == 0 && (e.responseCode == 403 || e.responseCode == 410)) {
                    attempt++
                    session.invalidateResolvedMedia(id)
                    media = resolveSafe(id, lockedFormat ?: media.format, forceRefresh = true)
                    continue
                }
                throw e
            } catch (e: Exception) {
                runCatching { upstream.close() }
                throw e
            }
        }

        activeTrackId = id
        activeFormat = media.format

        val available = if (upstreamLength == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else (upstreamLength - skip).coerceAtLeast(0)
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
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
        }
        stripeInput = StripeInput(input, media.trackId, aligned, skip, length)
        return length
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

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        stripeInput?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = requestedUri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        try { upstream.close() } finally { requestedUri = null; stripeInput = null }
    }
}
