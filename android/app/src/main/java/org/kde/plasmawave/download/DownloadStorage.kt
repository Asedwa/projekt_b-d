package org.kde.plasmawave.download

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import uniffi.plasmawave_core.FfiTrack

object DownloadStorage {

    private val bracketIdRegex = Regex("""\[(\d+)\]""")

    fun normalizeTrackId(id: String): String =
        id.removePrefix("deezer:").removePrefix("local:download-").trim()

    /** Find an existing audio file on disk or in MediaStore for a given Deezer track ID. */
    fun findExistingTrack(context: Context, trackId: String): Uri? {
        val cleanId = normalizeTrackId(trackId)
        if (cleanId.isEmpty() || !cleanId.all(Char::isDigit)) return null

        // 1. Direct file check in Music/PlasmaWave (including playlist subfolders)
        try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
            if (dir.isDirectory) {
                val match = dir.walkTopDown().maxDepth(2).firstOrNull { file ->
                    file.isFile && file.name.contains("[$cleanId]") && file.length() > 0
                }
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

        // 1. Direct directory scan (including playlist subfolders)
        try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
            if (dir.isDirectory) {
                dir.walkTopDown().maxDepth(2).forEach { file ->
                    if (file.isFile) {
                        bracketIdRegex.find(file.name)?.groupValues?.get(1)?.let { ids.add(it) }
                    }
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
                    bracketIdRegex.find(name)?.groupValues?.get(1)?.let { ids.add(it) }
                }
            }
        } catch (_: Exception) {}

        return ids
    }

    /**
     * Collects all downloaded tracks from:
     * 1) Completed entries in [savedDownloads]
     * 2) MediaStore entries in Music/PlasmaWave (or matching `[trackId]`)
     * 3) Direct files in `/sdcard/Music/PlasmaWave`
     */
    fun loadAllDownloadedTracks(
        context: Context,
        savedDownloads: List<SavedDownload>,
        trackHints: Map<String, FfiTrack> = emptyMap()
    ): List<FfiTrack> {
        val results = mutableListOf<FfiTrack>()
        val seenCleanIds = mutableSetOf<String>()
        val seenUris = mutableSetOf<String>()

        // 1. Completed items from Downloads DB (newest first)
        savedDownloads
            .filter { it.state == "completed" }
            .asReversed()
            .forEach { item ->
                val cleanId = normalizeTrackId(item.track.id)
                val resolvedUri = item.uri?.takeIf { it.isNotBlank() }
                    ?: findExistingTrack(context, cleanId)?.toString()
                if (resolvedUri != null) {
                    val key = cleanId.ifBlank { item.id }
                    if (seenCleanIds.add(key)) {
                        seenUris.add(resolvedUri)
                        val hint = trackHints[cleanId]
                        results.add(
                            item.track.copy(
                                id = "local:download-$key",
                                provider = "local",
                                title = item.track.title.ifBlank { hint?.title ?: "İndirilen Şarkı" },
                                artist = item.track.artist.ifBlank { hint?.artist ?: "Bilinmeyen Sanatçı" },
                                album = item.track.album.ifBlank { hint?.album ?: "İndirilenler" },
                                durationMs = if (item.track.durationMs > 0uL) item.track.durationMs else (hint?.durationMs ?: 0uL),
                                artworkUrl = item.track.artworkUrl?.takeUnless(org.kde.plasmawave.ui.components.ArtworkResolver::isPlaceholderOrBlank)
                                    ?: hint?.artworkUrl?.takeUnless(org.kde.plasmawave.ui.components.ArtworkResolver::isPlaceholderOrBlank)
                                    ?: org.kde.plasmawave.ui.components.AudioArtFetcher.uriFor(resolvedUri),
                                playbackUri = resolvedUri,
                                isPreview = false,
                                origin = "real"
                            )
                        )
                    }
                }
            }

        // 2. Query MediaStore for files in PlasmaWave or with [trackId] pattern
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
            )
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%[%]%.%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameCol) ?: continue
                    val cleanId = bracketIdRegex.find(displayName)?.groupValues?.get(1).orEmpty()
                    val mediaId = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString()
                    val dedupKey = cleanId.ifBlank { displayName }
                    if (!seenCleanIds.add(dedupKey) || !seenUris.add(contentUri)) continue

                    val (parsedArtist, parsedTitle) = parseArtistAndTitle(displayName)
                    val hint = if (cleanId.isNotBlank()) trackHints[cleanId] else null
                    val rawTitle = cursor.getString(titleCol)?.takeIf { it.isNotBlank() && !it.contains("[$cleanId]") }
                    val rawArtist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val rawAlbum = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L).toULong()
                    val mediaStoreArt = org.kde.plasmawave.ui.components.AudioArtFetcher.uriFor(contentUri)

                    results.add(
                        FfiTrack(
                            id = "local:download-$dedupKey",
                            provider = "local",
                            title = hint?.title ?: parsedTitle.ifBlank { rawTitle ?: displayName.substringBeforeLast('.') },
                            artist = hint?.artist ?: parsedArtist.ifBlank { rawArtist ?: "İndirilen" },
                            album = hint?.album ?: (rawAlbum ?: "İndirilen Müzikler"),
                            durationMs = if (durationMs > 0uL) durationMs else (hint?.durationMs ?: 0uL),
                            artworkUrl = hint?.artworkUrl?.takeUnless(org.kde.plasmawave.ui.components.ArtworkResolver::isPlaceholderOrBlank) ?: mediaStoreArt,
                            playbackUri = contentUri,
                            isPreview = false,
                            origin = "real"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Direct file scan in Music/PlasmaWave for any files not yet indexed in MediaStore
        try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PlasmaWave")
            if (dir.isDirectory) {
                dir.listFiles { f ->
                    f.isFile && f.length() > 0 && (f.name.endsWith(".flac", true) || f.name.endsWith(".mp3", true) || f.name.endsWith(".m4a", true) || f.name.endsWith(".ogg", true))
                }?.sortedByDescending { it.lastModified() }?.forEach { file ->
                    val displayName = file.name
                    val cleanId = bracketIdRegex.find(displayName)?.groupValues?.get(1).orEmpty()
                    val fileUri = Uri.fromFile(file).toString()
                    val dedupKey = cleanId.ifBlank { displayName }
                    if (!seenCleanIds.add(dedupKey) || !seenUris.add(fileUri)) return@forEach

                    val (parsedArtist, parsedTitle) = parseArtistAndTitle(displayName)
                    val hint = if (cleanId.isNotBlank()) trackHints[cleanId] else null

                    results.add(
                        FfiTrack(
                            id = "local:download-$dedupKey",
                            provider = "local",
                            title = hint?.title ?: parsedTitle.ifBlank { displayName.substringBeforeLast('.') },
                            artist = hint?.artist ?: parsedArtist.ifBlank { "İndirilen" },
                            album = hint?.album ?: "İndirilen Müzikler",
                            durationMs = hint?.durationMs ?: 0uL,
                            artworkUrl = hint?.artworkUrl?.takeUnless(org.kde.plasmawave.ui.components.ArtworkResolver::isPlaceholderOrBlank)
                                ?: org.kde.plasmawave.ui.components.AudioArtFetcher.uriFor(fileUri),
                            playbackUri = fileUri,
                            isPreview = false,
                            origin = "real"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return results
    }

    private fun parseArtistAndTitle(fileName: String): Pair<String, String> {
        val withoutExt = fileName.substringBeforeLast('.')
        val withoutId = withoutExt.replace(Regex("""\s*\[\d+\]\s*$"""), "").trim()
        val sepIndex = withoutId.indexOf(" - ")
        return if (sepIndex > 0) {
            val artist = withoutId.substring(0, sepIndex).trim()
            val title = withoutId.substring(sepIndex + 3).trim()
            artist to title
        } else {
            "" to withoutId
        }
    }
}
