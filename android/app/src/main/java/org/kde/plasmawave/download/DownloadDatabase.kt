package org.kde.plasmawave.download

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.ui.RustPrefs
import org.kde.plasmawave.ui.jsonTrack
import org.kde.plasmawave.ui.trackJson
import uniffi.plasmawave_core.FfiSavedDownload

/** Delegates download queue persistence to Rust SQLite (`full_downloads_queue`), migrating legacy `downloads.db` once. */
internal class DownloadDatabase(private val context: Context) {
    private val core get() = PlasmaWaveApp.core

    fun migrateLegacy(context: Context) {
        if (RustPrefs.getBoolean("meta:downloads_migrated_to_rust_v1", false)) return
        val existingRust = runCatching { core.loadFullDownloads() }.getOrDefault(emptyList())
        if (existingRust.isEmpty()) {
            val legacyFromDb = readLegacySqlite(context)
            val legacyFromPrefs = readLegacyPrefs(context)
            val combined = (legacyFromDb + legacyFromPrefs).distinctBy { it.id }
            if (combined.isNotEmpty()) {
                runCatching { core.insertFullDownloads(combined.map { it.toFfi() }) }
            }
        }
        RustPrefs.putBoolean("meta:downloads_migrated_to_rust_v1", true)
    }

    fun load(): List<SavedDownload> =
        runCatching { core.loadFullDownloads().map { it.toUi() } }.getOrDefault(emptyList())

    fun insertAll(items: List<SavedDownload>) {
        if (items.isEmpty()) return
        runCatching { core.insertFullDownloads(items.map { it.toFfi() }) }
    }

    fun update(item: SavedDownload) {
        runCatching { core.updateFullDownload(item.toFfi()) }
    }

    fun remove(id: String) {
        runCatching { core.removeFullDownload(id) }
    }

    fun cancelAll(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching { core.cancelFullDownloads(ids) }
    }

    private fun SavedDownload.toFfi() = FfiSavedDownload(
        id = id,
        track = track,
        quality = quality.name,
        state = state,
        progress = progress,
        uri = uri,
        error = error,
        partialUri = partialUri,
        attempts = attempts,
        playlistId = playlistId,
        playlistName = playlistName
    )

    private fun FfiSavedDownload.toUi() = SavedDownload(
        id = id,
        track = track,
        quality = runCatching { DownloadQuality.valueOf(quality) }.getOrDefault(DownloadQuality.MP3_320),
        state = state,
        progress = progress,
        uri = uri,
        error = error,
        partialUri = partialUri,
        attempts = attempts,
        playlistId = playlistId,
        playlistName = playlistName
    )

    private fun readLegacySqlite(context: Context): List<SavedDownload> {
        val dbFile = context.getDatabasePath("downloads.db")
        if (!dbFile.exists()) return emptyList()
        val helper = object : SQLiteOpenHelper(context, "downloads.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {}
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        return try {
            val output = mutableListOf<SavedDownload>()
            helper.readableDatabase.query(
                "downloads",
                arrayOf("id", "track", "quality", "state", "progress", "uri", "error", "partial", "attempts", "playlist_id", "playlist_name"),
                null, null, null, null, "position ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching {
                        output.add(
                            SavedDownload(
                                id = cursor.getString(0),
                                track = jsonTrack(JSONObject(cursor.getString(1))),
                                quality = DownloadQuality.valueOf(cursor.getString(2)),
                                state = cursor.getString(3),
                                progress = cursor.getInt(4),
                                uri = cursor.getString(5),
                                error = cursor.getString(6),
                                partialUri = cursor.getString(7),
                                attempts = cursor.getInt(8),
                                playlistId = cursor.getString(9),
                                playlistName = cursor.getString(10)
                            )
                        )
                    }
                }
            }
            output
        } catch (_: Exception) {
            emptyList()
        } finally {
            helper.close()
        }
    }

    private fun readLegacyPrefs(context: Context): List<SavedDownload> {
        val prefs = context.getSharedPreferences("full_downloads", Context.MODE_PRIVATE)
        if (prefs.getBoolean("database_migrated", false)) return emptyList()
        return try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    val json = arr.getJSONObject(index)
                    fun optional(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                    SavedDownload(
                        json.getString("id"),
                        jsonTrack(json.getJSONObject("track")),
                        DownloadQuality.valueOf(json.getString("quality")),
                        json.optString("state", "queued"),
                        json.optInt("progress"),
                        optional("uri"),
                        optional("error"),
                        optional("partial")
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            prefs.edit().remove("items").putBoolean("database_migrated", true).apply()
        }
    }
}
