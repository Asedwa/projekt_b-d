package org.kde.plasmawave.download

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.ui.jsonTrack
import org.kde.plasmawave.ui.trackJson
import uniffi.plasmawave_core.FfiTrack
import java.util.UUID
import java.io.File

enum class DownloadQuality(val label: String, val format: String, val extension: String, val mime: String) {
    MP3_320("MP3 • 320 kbps", "MP3_320", "mp3", "audio/mpeg"),
    MP3_128("MP3 • 128 kbps", "MP3_128", "mp3", "audio/mpeg"),
    FLAC("FLAC • Kayıpsız", "FLAC", "flac", "audio/flac")
}

data class SavedDownload(
    val id: String, val track: FfiTrack, val quality: DownloadQuality,
    val state: String = "queued", val progress: Int = 0, val uri: String? = null,
    val error: String? = null, val partialUri: String? = null
)

/** Keeps the UI and completed files available across process restarts. */
class Downloads private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: Downloads? = null
        fun get(context: Context): Downloads = instance ?: synchronized(this) {
            instance ?: Downloads(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("full_downloads", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(read())
    val items = _items.asStateFlow()
    private val _quality = MutableStateFlow(readQuality())
    val quality = _quality.asStateFlow()

    private fun readQuality() = runCatching {
        DownloadQuality.valueOf(prefs.getString("quality", DownloadQuality.MP3_320.name)!!)
    }.getOrDefault(DownloadQuality.MP3_320)

    fun setQuality(quality: DownloadQuality) {
        prefs.edit().putString("quality", quality.name).apply()
        _quality.value = quality
    }

    @Synchronized fun enqueue(track: FfiTrack, quality: DownloadQuality, wifiOnly: Boolean): Boolean {
        if (_items.value.any { it.track.id == track.id && it.quality == quality && it.state in listOf("queued", "running") }) return false
        val id = UUID.randomUUID().toString()
        save(listOf(SavedDownload(id, track, quality)) + _items.value)
        try {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setId(UUID.fromString(id))
                .setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag("plasmawave-download")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            update(id) { it.copy(state = "failed", error = "İndirme başlatılamadı") }
            throw e
        }
        return true
    }

    @Synchronized fun find(id: String): SavedDownload? = _items.value.firstOrNull { it.id == id }
    @Synchronized fun update(id: String, change: (SavedDownload) -> SavedDownload) {
        save(_items.value.map { if (it.id == id) change(it) else it })
    }

    @Synchronized fun updateActive(id: String, change: (SavedDownload) -> SavedDownload) {
        update(id) { if (it.state == "cancelled") it else change(it) }
    }

    @Synchronized fun complete(id: String, uri: String): Boolean {
        val item = find(id) ?: return false
        if (item.state == "cancelled") return false
        update(id) { it.copy(state = "completed", progress = 100, uri = uri, partialUri = null) }
        return true
    }

    @Synchronized fun cancel(id: String) {
        val item = find(id) ?: return
        if (item.state !in listOf("queued", "running")) return
        val partial = item.partialUri
        update(id) { it.copy(state = "cancelled", error = null, partialUri = null) }
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id))
        partial?.let {
            try {
                val uri = Uri.parse(it)
                if (uri.scheme == "file") uri.path?.let { path -> File(path).delete() }
                else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) { }
        }
    }

    @Synchronized fun forget(id: String) {
        save(_items.value.filterNot { it.id == id })
    }

    private fun save(items: List<SavedDownload>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("track", trackJson(it.track)).put("quality", it.quality.name)
                .put("state", it.state).put("progress", it.progress).put("uri", it.uri)
                .put("error", it.error).put("partial", it.partialUri))
        }
        prefs.edit().putString("items", arr.toString()).apply()
        _items.value = items
    }

    private fun read(): List<SavedDownload> = try {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).mapNotNull { index -> runCatching {
            val o = arr.getJSONObject(index)
            fun optional(key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
            SavedDownload(o.getString("id"), jsonTrack(o.getJSONObject("track")),
                DownloadQuality.valueOf(o.getString("quality")), o.optString("state", "queued"),
                o.optInt("progress"), optional("uri"), optional("error"), optional("partial"))
        }.getOrNull() }
    } catch (_: Exception) { emptyList() }
}
