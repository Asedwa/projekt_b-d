package org.kde.plasmawave.download

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

internal object DownloadStorage {

    fun normalizeTrackId(id: String): String = id.removePrefix("deezer:").trim()

    /** Find an existing audio file on disk or in MediaStore for a given Deezer track ID. */
    fun findExistingTrack(context: Context, trackId: String): Uri? {
        val cleanId = normalizeTrackId(trackId)
        if (cleanId.isEmpty() || !cleanId.all(Char::isDigit)) return null

        // 1. Direct file check in Music/PlasmaWave
        try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
            if (dir.isDirectory) {
                val match = dir.listFiles { file ->
                    file.isFile && file.name.contains("[$cleanId]") && file.length() > 0
                }?.firstOrNull()
                if (match != null) return Uri.fromFile(match)
            }
        } catch (_: Exception) {}

        // 2. Query MediaStore
        try {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%[$cleanId].%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /** Fast scan of all Deezer track IDs already saved in Music/PlasmaWave or MediaStore. */
    fun scanDownloadedTrackIds(context: Context): Set<String> {
        val ids = mutableSetOf<String>()
        val regex = Regex("""\[(\d+)\]""")

        // 1. Direct directory scan (<5ms)
        try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
            if (dir.isDirectory) {
                dir.list()?.forEach { name ->
                    regex.find(name)?.groupValues?.get(1)?.let { ids.add(it) }
                }
            }
        } catch (_: Exception) {}

        // 2. MediaStore query
        try {
            val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%[%]%.%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    regex.find(name)?.groupValues?.get(1)?.let { ids.add(it) }
                }
            }
        } catch (_: Exception) {}

        return ids
    }
}
