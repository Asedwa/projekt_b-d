package org.kde.plasmawave.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.ui.jsonTrack
import org.kde.plasmawave.ui.trackJson

/** Stores queue entries individually so a long playlist does not rewrite a giant JSON file per song. */
internal class DownloadDatabase(context: Context) : SQLiteOpenHelper(context, "downloads.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE downloads (
            position INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
            track TEXT NOT NULL, quality TEXT NOT NULL, state TEXT NOT NULL,
            progress INTEGER NOT NULL DEFAULT 0, uri TEXT, error TEXT, partial TEXT,
            attempts INTEGER NOT NULL DEFAULT 0
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences("full_downloads", Context.MODE_PRIVATE)
        if (prefs.getBoolean("database_migrated", false)) return
        val list = try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            (0 until arr.length()).mapNotNull { index -> runCatching {
                val json = arr.getJSONObject(index)
                fun optional(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                SavedDownload(json.getString("id"), jsonTrack(json.getJSONObject("track")),
                    DownloadQuality.valueOf(json.getString("quality")), json.optString("state", "queued"),
                    json.optInt("progress"), optional("uri"), optional("error"), optional("partial"))
            }.getOrNull() }
        } catch (_: Exception) { emptyList() }
        insertAll(list)
        prefs.edit().remove("items").putBoolean("database_migrated", true).apply()
    }

    fun load(): List<SavedDownload> {
        val output = mutableListOf<SavedDownload>()
        readableDatabase.query("downloads", arrayOf("id", "track", "quality", "state", "progress", "uri", "error", "partial", "attempts"),
            null, null, null, null, "position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    output.add(SavedDownload(cursor.getString(0), jsonTrack(JSONObject(cursor.getString(1))),
                        DownloadQuality.valueOf(cursor.getString(2)), cursor.getString(3), cursor.getInt(4),
                        cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getInt(8)))
                }
            }
        }
        return output
    }

    fun insertAll(items: List<SavedDownload>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { db.insertWithOnConflict("downloads", null, values(it), SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun update(item: SavedDownload) {
        writableDatabase.update("downloads", values(item), "id = ?", arrayOf(item.id))
    }

    fun remove(id: String) { writableDatabase.delete("downloads", "id = ?", arrayOf(id)) }

    fun cancelAll(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("state", "cancelled"); putNull("error"); putNull("partial") }
            ids.forEach { db.update("downloads", values, "id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun values(item: SavedDownload) = ContentValues().apply {
        put("id", item.id)
        put("track", trackJson(item.track).toString())
        put("quality", item.quality.name)
        put("state", item.state)
        put("progress", item.progress)
        put("uri", item.uri)
        put("error", item.error)
        put("partial", item.partialUri)
        put("attempts", item.attempts)
    }
}
