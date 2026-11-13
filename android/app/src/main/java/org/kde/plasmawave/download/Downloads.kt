package org.kde.plasmawave.download

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.plasmawave_core.FfiTrack
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadQuality(val label: String, val format: String, val extension: String, val mime: String) {
    MP3_320("MP3 • 320 kbps", "MP3_320", "mp3", "audio/mpeg"),
    MP3_128("MP3 • 128 kbps", "MP3_128", "mp3", "audio/mpeg"),
    FLAC("FLAC • Kayıpsız", "FLAC", "flac", "audio/flac")
}

data class SavedDownload(
    val id: String, val track: FfiTrack, val quality: DownloadQuality,
    val state: String = "queued", val progress: Int = 0, val uri: String? = null,
    val error: String? = null, val partialUri: String? = null, val attempts: Int = 0
)

data class EnqueueResult(val added: Int, val skipped: Int)

internal fun selectTracksToEnqueue(
    tracks: List<FfiTrack>,
    quality: DownloadQuality,
    existing: List<SavedDownload>,
    onDiskIds: Set<String> = emptySet()
): List<FfiTrack> {
    val known = existing.asSequence()
        .filter { it.quality == quality && it.state in listOf("queued", "running", "completed") }
        .map { DownloadStorage.normalizeTrackId(it.track.id) }
        .toMutableSet()
    known.addAll(onDiskIds)
    return tracks.filter { it.provider == "deezer" && DownloadStorage.normalizeTrackId(it.id).let { id -> id.isNotBlank() && id.all(Char::isDigit) } }
        .filter { known.add(DownloadStorage.normalizeTrackId(it.id)) }
}

internal fun mayUseDownloadNetwork(mobileAllowed: Boolean, unmetered: Boolean, wifi: Boolean, ethernet: Boolean, cellular: Boolean): Boolean =
    mobileAllowed || (unmetered && !cellular && (wifi || ethernet))

/** A persistent, ordered queue. A single worker drains it, even for large playlists. */
class Downloads private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: Downloads? = null
        fun get(context: Context): Downloads = instance ?: synchronized(this) {
            instance ?: Downloads(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("full_downloads", Context.MODE_PRIVATE)
    private val database = DownloadDatabase(context).apply { migrateLegacy(context) }
    private val _items = MutableStateFlow(database.load())
    val items = _items.asStateFlow()
    private val _quality = MutableStateFlow(runCatching {
        DownloadQuality.valueOf(prefs.getString("quality", DownloadQuality.MP3_320.name)!!)
    }.getOrDefault(DownloadQuality.MP3_320))
    val quality = _quality.asStateFlow()
    // Mobile downloads require a specific opt-in, even on an unmetered mobile plan.
    private val _mobileAllowed = MutableStateFlow(prefs.getBoolean("mobile_allowed", false))
    val mobileAllowed = _mobileAllowed.asStateFlow()
    private val cancelled = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(_items.value.filter { it.state == "cancelled" }.map { it.id })
    }

    fun setQuality(quality: DownloadQuality) {
        prefs.edit().putString("quality", quality.name).apply()
        _quality.value = quality
    }
    fun setMobileAllowed(allowed: Boolean) {
        prefs.edit().putBoolean("mobile_allowed", allowed).apply()
        _mobileAllowed.value = allowed
    }

    @Synchronized fun enqueueAll(tracks: List<FfiTrack>, quality: DownloadQuality): EnqueueResult {
        val diskIds = DownloadStorage.scanDownloadedTrackIds(context)
        val added = selectTracksToEnqueue(tracks, quality, _items.value, diskIds).map { SavedDownload(UUID.randomUUID().toString(), it, quality) }
        if (added.isEmpty()) return EnqueueResult(0, tracks.size)
        database.insertAll(added)
        _items.value = _items.value + added
        try {
            scheduleQueue()
        } catch (e: Exception) {
            added.forEach { update(it.id) { entry -> entry.copy(state = "failed", error = "İndirme başlatılamadı") } }
            throw e
        }
        return EnqueueResult(added.size, tracks.size - added.size)
    }

    fun recover() {
        if (_items.value.any { it.state == "queued" || it.state == "running" }) scheduleQueue()
    }

    private fun scheduleQueue() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("plasmawave-download-queue")
            .build()
        // Appending a wake-up worker closes the race between a running worker
        // finishing and a new playlist being added. Expected errors return success
        // after updating the affected entry, so they never cancel later entries.
        WorkManager.getInstance(context).enqueueUniqueWork("plasmawave-download-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    @Synchronized fun find(id: String): SavedDownload? = _items.value.firstOrNull { it.id == id }
    @Synchronized fun firstPending(): SavedDownload? = _items.value.firstOrNull { it.state == "running" || it.state == "queued" }
    @Synchronized fun nextPending(excludeId: String): SavedDownload? =
        _items.value.firstOrNull { it.id != excludeId && it.state == "queued" }
    fun isCancelled(id: String): Boolean = cancelled.contains(id)

    @Synchronized fun update(id: String, change: (SavedDownload) -> SavedDownload) {
        val current = _items.value.firstOrNull { it.id == id } ?: return
        val next = change(current)
        database.update(next)
        _items.value = _items.value.map { if (it.id == id) next else it }
    }

    @Synchronized fun updateActive(id: String, change: (SavedDownload) -> SavedDownload) {
        update(id) { if (it.state == "cancelled") it else change(it) }
    }

    @Synchronized fun updateProgress(id: String, progress: Int) {
        _items.value = _items.value.map { if (it.id == id && it.state == "running") it.copy(progress = progress) else it }
    }

    @Synchronized fun complete(id: String, uri: String): Boolean {
        val item = find(id) ?: return false
        if (item.state == "cancelled") return false
        update(id) { it.copy(state = "completed", progress = 100, uri = uri, partialUri = null, error = null) }
        return true
    }

    @Synchronized fun cancel(id: String) {
        val item = find(id) ?: return
        if (item.state !in listOf("queued", "running")) return
        cancelled.add(id)
        update(id) { it.copy(state = "cancelled", error = null, partialUri = null) }
        item.partialUri?.let {
            try {
                val uri = Uri.parse(it)
                if (uri.scheme == "file") uri.path?.let { path -> File(path).delete() }
                else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) { }
        }
    }

    @Synchronized fun cancelAll() {
        val targets = _items.value.filter { it.state == "queued" || it.state == "running" }
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()
        cancelled.addAll(ids)
        database.cancelAll(ids.toList())
        _items.value = _items.value.map { if (it.id in ids) it.copy(state = "cancelled", error = null, partialUri = null) else it }
        targets.mapNotNull { it.partialUri }.forEach {
            try {
                val uri = Uri.parse(it)
                if (uri.scheme == "file") uri.path?.let { path -> File(path).delete() }
                else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) { }
        }
    }

    @Synchronized fun forget(id: String) {
        if (find(id)?.state in listOf("queued", "running")) return
        database.remove(id)
        _items.value = _items.value.filterNot { it.id == id }
    }
}
