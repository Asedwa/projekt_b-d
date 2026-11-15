package org.kde.plasmawave.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.io.ByteArrayOutputStream

/**
 * Coil [Fetcher] that extracts album artwork for local/downloaded audio files using
 * Android Q+ `ContentResolver.loadThumbnail` or [MediaMetadataRetriever.getEmbeddedPicture],
 * replacing the deprecated `content://media/external/audio/albumart` URI.
 */
class AudioArtFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val targetRaw = uri.getQueryParameter("target") ?: return null
        val targetUri = Uri.parse(targetRaw)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetUri.scheme == "content") {
            try {
                val bmp = context.contentResolver.loadThumbnail(targetUri, Size(512, 512), null)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                val buffer = Buffer().write(out.toByteArray())
                return SourceResult(
                    source = ImageSource(buffer, context),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK
                )
            } catch (_: Throwable) {
                // Fall back to MediaMetadataRetriever below
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            if (targetUri.scheme == "file" && !targetUri.path.isNullOrBlank()) {
                retriever.setDataSource(targetUri.path)
            } else {
                retriever.setDataSource(context, targetUri)
            }
            val embedded = retriever.embeddedPicture ?: return null
            val buffer = Buffer().write(embedded)
            SourceResult(
                source = ImageSource(buffer, context),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == SCHEME) AudioArtFetcher(context, data) else null
    }

    companion object {
        const val SCHEME = "audioart"

        fun uriFor(audioUri: String): String =
            Uri.Builder()
                .scheme(SCHEME)
                .authority("track")
                .appendQueryParameter("target", audioUri)
                .build()
                .toString()
    }
}
