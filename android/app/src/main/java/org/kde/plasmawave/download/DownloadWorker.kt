package org.kde.plasmawave.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.kde.plasmawave.deezer.DeezerException
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.StripeInput
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val downloads = Downloads.get(context)
    private val notificationId get() = id.hashCode() and Int.MAX_VALUE
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val task = downloads.find(id) ?: return@withContext Result.failure()
        if (task.state == "cancelled") return@withContext Result.failure()
        task.partialUri?.let { deletePartial(Uri.parse(it)) }
        var partial: Uri? = null
        var connection: HttpURLConnection? = null
        try {
            notifications.createNotificationChannel(NotificationChannel("downloads", "Şarkı indirmeleri", NotificationManager.IMPORTANCE_LOW))
            try {
                setForeground(foreground(task, 0))
            } catch (_: IllegalStateException) {
                // Android can deny starting a foreground service after a deferred
                // network constraint becomes satisfied; ordinary WorkManager work
                // can still run and will be rescheduled if its time window ends.
            }
            downloads.updateActive(id) { it.copy(state = "running", progress = 0, error = null, partialUri = null) }
            val media = DeezerSession.get(applicationContext).resolveDownload(task.track.id.removePrefix("deezer:"), task.quality.format)
            currentCoroutineContext().ensureActive()
            connection = openAudio(media.url)
            val length = connection.contentLengthLong
            partial = createDestination(task)
            downloads.updateActive(id) { it.copy(partialUri = partial.toString()) }
            if (downloads.find(id)?.state == "cancelled") throw CancellationException("İndirme iptal edildi")
            val output = if (partial.scheme == "file") File(partial.path!!).outputStream()
                else applicationContext.contentResolver.openOutputStream(partial) ?: throw IOException("Dosya açılamadı")
            var written = 0L
            var shownProgress = -1
            output.buffered().use { dest ->
                connection.inputStream.buffered().use { source ->
                    val input = StripeInput(source, media.trackId, 0, 0, -1)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        dest.write(buffer, 0, count)
                        written += count
                        val progress = if (length > 0) (written * 100 / length).toInt().coerceIn(0, 99) else 0
                        if (progress != shownProgress) {
                            shownProgress = progress
                            downloads.updateActive(id) { it.copy(progress = progress) }
                            notifications.notify(notificationId, notification(task, progress))
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (downloads.find(id)?.state == "cancelled") throw CancellationException("İndirme iptal edildi")
            if (written == 0L || (length > 0 && written != length)) throw IOException("Eksik ses dosyası")
            publish(partial, task)
            if (!downloads.complete(id, partial.toString())) throw CancellationException("İndirme iptal edildi")
            partial = null
            Result.success()
        } catch (e: CancellationException) {
            downloads.update(id) { if (it.state == "cancelled") it.copy(partialUri = null) else it.copy(state = "queued", progress = 0, partialUri = null) }
            throw e
        } catch (e: Exception) {
            val canRetry = e is IOException && e !is DeezerException && runAttemptCount < 2
            val message = when (e) {
                is DeezerException -> e.message ?: "Deezer şarkıyı sunmadı"
                is SecurityException -> "Müzik klasörüne erişim izni gerekli"
                else -> "İndirme başarısız; bağlantıyı ve cihazın boş alanını kontrol et"
            }
            downloads.updateActive(id) { it.copy(state = if (canRetry) "queued" else "failed", progress = 0, error = message, partialUri = null) }
            if (canRetry) Result.retry() else Result.failure()
        } finally {
            partial?.let(::deletePartial)
            connection?.disconnect()
        }
    }

    private fun foreground(task: SavedDownload, progress: Int): ForegroundInfo {
        val notification = notification(task, progress)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    private fun notification(task: SavedDownload, progress: Int): Notification =
        NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Şarkı indiriliyor")
            .setContentText("${task.track.artist} • ${task.track.title}")
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress, progress == 0).build()

    private fun createDestination(task: SavedDownload): Uri {
        val base = "${task.track.artist} - ${task.track.title} [${task.track.id.substringAfter(':')}]"
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(150).ifBlank { "PlasmaWave" }
        val name = "$base.${task.quality.extension}"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, task.quality.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PlasmaWave/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            return applicationContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Müzik klasörü açılamadı")
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Müzik klasörü oluşturulamadı")
        var file = File(directory, name)
        var suffix = 1
        while (!file.createNewFile()) file = File(directory, "$base (${suffix++}).${task.quality.extension}")
        return Uri.fromFile(file)
    }

    private fun publish(uri: Uri, task: SavedDownload) {
        if (Build.VERSION.SDK_INT >= 29) {
            val count = applicationContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            if (count != 1) throw IOException("Dosya kaydedilemedi")
        } else {
            MediaScannerConnection.scanFile(applicationContext, arrayOf(uri.path), arrayOf(task.quality.mime), null)
        }
    }

    private fun deletePartial(uri: Uri) {
        try {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else applicationContext.contentResolver.delete(uri, null, null)
        } catch (_: Exception) { }
    }

    private fun openAudio(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(5) {
            val host = url.host.lowercase()
            if (url.protocol != "https" || !(host == "dzcdn.net" || host.endsWith(".dzcdn.net") || host == "deezer.com" || host.endsWith(".deezer.com"))) {
                throw DeezerException("Geçersiz Deezer ses kaynağı")
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 PlasmaWave")
            val code = connection.responseCode
            if (code in 200..299) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (code !in 300..399 || location.isNullOrBlank()) throw IOException("Ses dosyası alınamadı")
            url = URL(url, location)
        }
        throw IOException("Çok fazla yönlendirme")
    }
}
