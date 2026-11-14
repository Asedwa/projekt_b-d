package org.kde.plasmawave.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.PlasmaWaveApp
import uniffi.plasmawave_core.FfiPinnedItem
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

sealed interface PinnedItem {
    val id: String
    data class Track(val track: FfiTrack) : PinnedItem { override val id get() = "track:${track.id}" }
    data class Playlist(val playlist: FfiPlaylist) : PinnedItem { override val id get() = "playlist:${playlist.id}" }
    data object Flow : PinnedItem { override val id get() = "flow:deezer" }
}

internal fun PinnedItem.toFfi(): FfiPinnedItem = when (this) {
    is PinnedItem.Track -> FfiPinnedItem(pinId = id, pinType = "track", track = track, playlist = null)
    is PinnedItem.Playlist -> FfiPinnedItem(pinId = id, pinType = "playlist", track = null, playlist = playlist)
    is PinnedItem.Flow -> FfiPinnedItem(pinId = id, pinType = "flow", track = null, playlist = null)
}

internal fun FfiPinnedItem.toUi(): PinnedItem? = when (pinType) {
    "track" -> track?.let { PinnedItem.Track(it) }
    "playlist" -> playlist?.let { PinnedItem.Playlist(it) }
    "flow" -> PinnedItem.Flow
    else -> null
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

/** Persists pinned items in Rust SQLite (core-storage) with automatic legacy SharedPreferences migration. */
class PinnedItems(context: Context) {
    private val core = PlasmaWaveApp.core
    private val prefs = context.getSharedPreferences("pinned_items", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(loadInitial())
    val items = _items.asStateFlow()

    @Synchronized fun toggle(item: PinnedItem) {
        try {
            val updated = core.togglePinnedItem(item.toFfi()).mapNotNull { it.toUi() }
            _items.value = updated
        } catch (_: Exception) {
            val current = _items.value
            val next = if (current.any { it.id == item.id }) current.filterNot { it.id == item.id } else current + item
            _items.value = next
        }
    }

    @Synchronized fun remove(id: String) {
        try {
            val updated = core.removePinnedItem(id).mapNotNull { it.toUi() }
            _items.value = updated
        } catch (_: Exception) {
            _items.value = _items.value.filterNot { it.id == id }
        }
    }

    private fun loadInitial(): List<PinnedItem> {
        return try {
            val rustItems = core.getPinnedItems().mapNotNull { it.toUi() }
            if (rustItems.isNotEmpty() || prefs.getBoolean("migrated_to_rust_v2", false)) {
                return rustItems
            }
            val legacy = readLegacy()
            if (legacy.isNotEmpty()) {
                core.setPinnedItems(legacy.map { it.toFfi() })
            }
            prefs.edit().putBoolean("migrated_to_rust_v2", true).apply()
            legacy
        } catch (_: Exception) {
            readLegacy()
        }
    }

    private fun readLegacy(): List<PinnedItem> = try {
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
