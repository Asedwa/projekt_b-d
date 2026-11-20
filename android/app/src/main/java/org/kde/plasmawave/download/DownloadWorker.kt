package org.kde.plasmawave.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kde.plasmawave.deezer.DeezerException
import org.kde.plasmawave.deezer.DeezerGateway
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.StripeInput
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val downloads = Downloads.get(context)
    private val notificationId get() = id.hashCode() and Int.MAX_VALUE
    private val notifications = context.getSystemService(NotificationManager::class.java)

    companion object { private val queueMutex = Mutex() }
    private class UserCancelled : IOException()
    private class NetworkBlocked : IOException()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        queueMutex.withLock { drainQueue() }
    }

    private suspend fun drainQueue(): Result {
        var foregroundStarted = false
        notifications.createNotificationChannel(NotificationChannel("downloads", "Şarkı indirmeleri", NotificationManager.IMPORTANCE_LOW))
        val prefetched = ConcurrentHashMap<String, Deferred<DeezerGateway.Media?>>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val task = downloads.firstPending() ?: return Result.success()
                val taskId = task.id
                val cleanId = DownloadStorage.normalizeTrackId(task.track.id)

                // Fast-skip 1: Completed previously in another task with a valid URI
                val existingRecord = downloads.items.value.firstOrNull {
                    it.id != taskId && it.state == "completed" && it.uri != null && DownloadStorage.normalizeTrackId(it.track.id) == cleanId
                }
                if (existingRecord?.uri != null) {
                    downloads.complete(taskId, existingRecord.uri)
                    continue
                }

                // Fast-skip 2: Already on disk in Music/PlasmaWave or in MediaStore
                val existingDiskUri = DownloadStorage.findExistingTrack(applicationContext, cleanId)
                if (existingDiskUri != null) {
                    downloads.complete(taskId, existingDiskUri.toString())
                    continue
                }

                if (!downloadNetworkAllowed()) {
                    downloads.updateActive(taskId) { it.copy(state = "queued", error = "Wi-Fi bağlantısı bekleniyor") }
                    return Result.retry()
                }
                task.partialUri?.let { deletePartial(Uri.parse(it)) }
                var partial: Uri? = null
                var connection: HttpURLConnection? = null
                try {
                    if (!foregroundStarted) {
                        try {
                            setForeground(foreground(task, 0))
                            foregroundStarted = true
                        } catch (_: IllegalStateException) {
                            // A deferred job can still run without a foreground service;
                            // WorkManager will resume the queue if its time window ends.
                        }
                    } else notifications.notify(notificationId, notification(task, 0))
                    downloads.updateActive(taskId) { it.copy(state = "running", progress = 0, error = null, partialUri = null) }
                    if (downloads.isCancelled(taskId)) throw UserCancelled()

                    // Look-ahead prefetch for next queued track while this one resolves and downloads
                    val nextTask = downloads.nextPending(taskId)
                    if (nextTask != null && !prefetched.containsKey(nextTask.id)) {
                        val nextCleanId = DownloadStorage.normalizeTrackId(nextTask.track.id)
                        if (DownloadStorage.findExistingTrack(applicationContext, nextCleanId) == null) {
                            prefetched[nextTask.id] = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                                runCatching {
                                    DeezerSession.get(applicationContext).resolveDownload(nextCleanId, nextTask.quality.format)
                                }.getOrNull()
                            }
                        }
                    }

                    val media = prefetched.remove(taskId)?.await()
                        ?: DeezerSession.get(applicationContext).resolveDownload(cleanId, task.quality.format)
                    currentCoroutineContext().ensureActive()
                    val network = allowedNetwork() ?: throw NetworkBlocked()
                    val safeWithoutMobilePermission = isUnmeteredWifiOrEthernet(network)
                    connection = openAudio(media.url, network)
                    val length = connection.contentLengthLong
                    partial = createDestination(task)
                    downloads.updateActive(taskId) { it.copy(partialUri = partial.toString()) }
                    if (downloads.isCancelled(taskId)) throw UserCancelled()
                    val output = if (partial.scheme == "file") File(partial.path!!).outputStream()
                        else applicationContext.contentResolver.openOutputStream(partial) ?: throw IOException("Dosya açılamadı")
                    var written = 0L
                    var lastNetworkCheck = 0L
                    var shownProgress = -1
                    var lastNotificationProgress = -1
                    var lastNotificationTime = 0L
                    var lastDbUpdateTime = 0L
                    var lastDbProgress = -1

                    output.buffered(128 * 1024).use { dest ->
                        connection.inputStream.buffered(128 * 1024).use { source ->
                            val input = StripeInput(source, media.trackId, 0, 0, -1)
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (downloads.isCancelled(taskId)) throw UserCancelled()
                                if (!downloads.mobileAllowed.value && !safeWithoutMobilePermission) throw NetworkBlocked()
                                if (written - lastNetworkCheck >= 4 * 1024 * 1024) {
                                    if (!downloadNetworkAllowed()) throw NetworkBlocked()
                                    lastNetworkCheck = written
                                }
                                val count = input.read(buffer, 0, buffer.size)
                                if (count < 0) break
                                dest.write(buffer, 0, count)
                                written += count
                                val progress = if (length > 0) (written * 100 / length).toInt().coerceIn(0, 99) else 0
                                val now = System.currentTimeMillis()
                                if (progress != shownProgress) {
                                    shownProgress = progress
                                    if (progress >= lastDbProgress + 2 || now - lastDbUpdateTime >= 400L) {
                                        lastDbProgress = progress
                                        lastDbUpdateTime = now
                                        downloads.updateProgress(taskId, progress)
                                    }
                                }
                                if (foregroundStarted && (progress >= lastNotificationProgress + 5 || (now - lastNotificationTime >= 750L && progress != lastNotificationProgress))) {
                                    lastNotificationProgress = progress
                                    lastNotificationTime = now
                                    notifications.notify(notificationId, notification(task, progress))
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (downloads.isCancelled(taskId)) throw UserCancelled()
                    if (!downloadNetworkAllowed()) throw NetworkBlocked()
                    if (written == 0L || (length > 0 && written != length)) throw IOException("Eksik ses dosyası")
                    publish(partial, task)
                    if (!downloads.complete(taskId, partial.toString())) throw UserCancelled()
                    partial = null
                } catch (e: CancellationException) {
                    downloads.update(taskId) { if (it.state == "cancelled") it.copy(partialUri = null)
                        else it.copy(state = "queued", progress = 0, partialUri = null) }
                    throw e
                } catch (_: UserCancelled) {
                    downloads.update(taskId) { it.copy(state = "cancelled", progress = 0, partialUri = null) }
                } catch (_: NetworkBlocked) {
                    downloads.updateActive(taskId) { it.copy(state = "queued", progress = 0, partialUri = null, error = "Wi-Fi bağlantısı bekleniyor") }
                    return Result.retry()
                } catch (e: Exception) {
                    if (downloads.isCancelled(taskId)) {
                        downloads.update(taskId) { it.copy(state = "cancelled", progress = 0, partialUri = null) }
                        continue
                    }
                    val attempts = task.attempts + 1
                    val canRetry = e is IOException && e !is DeezerException && attempts <= 2
                    val message = when (e) {
                        is DeezerException -> e.message ?: "Deezer şarkıyı sunmadı"
                        is SecurityException -> "Müzik klasörüne erişim izni gerekli"
                        else -> "İndirme başarısız; bağlantıyı ve cihazın boş alanını kontrol et"
                    }
                    downloads.updateActive(taskId) { it.copy(state = if (canRetry) "queued" else "failed", progress = 0,
                        error = message, partialUri = null, attempts = attempts) }
                    if (canRetry) return Result.retry()
                } finally {
                    partial?.let(::deletePartial)
                    connection?.disconnect()
                }
            }
        } finally {
            prefetched.values.forEach { it.cancel() }
        }
    }

    private fun downloadNetworkAllowed(): Boolean {
        return allowedNetwork() != null
    }

    private fun allowedNetwork(): Network? {
        return try {
            val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork ?: return null
            val capabilities = manager.getNetworkCapabilities(network) ?: return null
            if (mayUseDownloadNetwork(downloads.mobileAllowed.value,
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) network else null
        } catch (_: Exception) { null }
    }

    private fun isUnmeteredWifiOrEthernet(network: Network): Boolean {
        return try {
            val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            mayUseDownloadNetwork(false, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        } catch (_: Exception) { false }
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
        val playlistSubdir = task.playlistName
            ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
        val relPath = if (playlistSubdir != null) {
            "${Environment.DIRECTORY_MUSIC}/PlasmaWave/$playlistSubdir/"
        } else {
            "${Environment.DIRECTORY_MUSIC}/PlasmaWave/"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, task.quality.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            return applicationContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Müzik klasörü açılamadı")
        }
        @Suppress("DEPRECATION")
        val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
        val directory = if (playlistSubdir != null) File(rootDir, playlistSubdir) else rootDir
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

    private fun openAudio(address: String, network: Network): HttpURLConnection {
        var url = URL(address)
        repeat(5) {
            val host = url.host.lowercase()
            if (url.protocol != "https" || !(host == "dzcdn.net" || host.endsWith(".dzcdn.net") || host == "deezer.com" || host.endsWith(".deezer.com"))) {
                throw DeezerException("Geçersiz Deezer ses kaynağı")
            }
            // Binding the socket to the approved network prevents a Wi-Fi download
            // silently moving to a cellular connection if Android changes routes.
            val connection = network.openConnection(url) as HttpURLConnection
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
