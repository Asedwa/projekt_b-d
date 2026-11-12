package org.kde.plasmawave.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.InputStream

/** Media3 data source that resolves a fresh account-authorized URL on its loader thread. */
@UnstableApi
class DeezerDataSource(private val session: DeezerSession) : DataSource {
    private val upstream = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 PlasmaWave").createDataSource()
    private var requestedUri: Uri? = null
    private var stripeInput: StripeInput? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        requestedUri = dataSpec.uri
        if (dataSpec.uri.scheme != "deezer") return upstream.open(dataSpec)
        val id = dataSpec.uri.lastPathSegment ?: throw java.io.IOException("Geçersiz şarkı kimliği")
        val media = session.resolve(id)
        val aligned = dataSpec.position - dataSpec.position % StripeInput.CHUNK_SIZE
        val skip = (dataSpec.position - aligned).toInt()
        val upstreamSpec = dataSpec.buildUpon().setUri(media.url).setPosition(aligned).setLength(C.LENGTH_UNSET.toLong()).build()
        val upstreamLength = upstream.open(upstreamSpec)
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

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        stripeInput?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = requestedUri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        try { upstream.close() } finally { requestedUri = null; stripeInput = null }
    }
}
