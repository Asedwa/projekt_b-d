package org.kde.plasmawave.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

sealed interface PinnedItem {
    val id: String
    data class Track(val track: FfiTrack) : PinnedItem { override val id get() = "track:${track.id}" }
    data class Playlist(val playlist: FfiPlaylist) : PinnedItem { override val id get() = "playlist:${playlist.id}" }
    data object Flow : PinnedItem { override val id get() = "flow:deezer" }
}

internal fun trackJson(track: FfiTrack): JSONObject = JSONObject()
    .put("id", track.id).put("provider", track.provider).put("title", track.title)
    .put("artist", track.artist).put("album", track.album).put("duration", track.durationMs.toString())
    .put("artwork", track.artworkUrl).put("uri", if (track.provider == "deezer") null else track.playbackUri)
    .put("preview", track.isPreview).put("origin", track.origin)

internal fun jsonTrack(json: JSONObject): FfiTrack = FfiTrack(
    id = json.getString("id"), provider = json.getString("provider"), title = json.optString("title"),
    artist = json.optString("artist"), album = json.optString("album"),
    durationMs = json.optString("duration").toULongOrNull() ?: 0uL,
    artworkUrl = json.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
    playbackUri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" },
    isPreview = json.optBoolean("preview"), origin = json.optString("origin", "real")
)

/** Persists display metadata only; account credentials and Deezer media URLs are never stored. */
class PinnedItems(context: Context) {
    private val prefs = context.getSharedPreferences("pinned_items", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(read())
    val items = _items.asStateFlow()

    @Synchronized fun toggle(item: PinnedItem) {
        val current = _items.value
        val next = if (current.any { it.id == item.id }) current.filterNot { it.id == item.id } else current + item
        persist(next)
    }

    @Synchronized fun remove(id: String) {
        val current = _items.value
        if (current.none { it.id == id }) return
        persist(current.filterNot { it.id == id })
    }

    private fun persist(next: List<PinnedItem>) {
        val json = JSONArray()
        next.forEach { entry ->
            when (entry) {
                is PinnedItem.Track -> json.put(JSONObject().put("type", "track").put("data", trackJson(entry.track)))
                is PinnedItem.Playlist -> {
                    val p = entry.playlist
                    json.put(JSONObject().put("type", "playlist").put("data", JSONObject()
                        .put("id", p.id).put("name", p.name).put("description", p.description)
                        .put("local", p.isLocal).put("count", p.trackCount.toLong()).put("artwork", p.artworkUrl).put("origin", p.origin)))
                }
                is PinnedItem.Flow -> json.put(JSONObject().put("type", "flow").put("data", JSONObject()))
            }
        }
        prefs.edit().putString("items", json.toString()).apply()
        _items.value = next
    }

    private fun read(): List<PinnedItem> = try {
        val json = JSONArray(prefs.getString("items", "[]"))
        (0 until json.length()).mapNotNull { i ->
            runCatching {
                val entry = json.getJSONObject(i)
                when (entry.getString("type")) {
                    "track" -> PinnedItem.Track(jsonTrack(entry.getJSONObject("data")))
                    "playlist" -> {
                        val data = entry.getJSONObject("data")
                        PinnedItem.Playlist(FfiPlaylist(
                            id = data.getString("id"), name = data.optString("name"),
                            description = data.optString("description").takeIf { it.isNotBlank() && it != "null" },
                            isLocal = data.optBoolean("local"), trackCount = data.optLong("count").coerceAtLeast(0).toUInt(),
                            artworkUrl = data.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
                            origin = data.optString("origin", "real")
                        ))
                    }
                    "flow" -> PinnedItem.Flow
                    else -> null
                }
            }.getOrNull()
        }
    } catch (_: Exception) { emptyList() }
}
