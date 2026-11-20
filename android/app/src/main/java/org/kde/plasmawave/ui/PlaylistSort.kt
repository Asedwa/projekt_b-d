package org.kde.plasmawave.ui

import org.kde.plasmawave.PlasmaWaveApp
import uniffi.plasmawave_core.FfiLibraryPlaylistEntry
import uniffi.plasmawave_core.FfiLibraryPlaylistSort
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiPlaylistTrackEntry
import uniffi.plasmawave_core.FfiPlaylistTrackSort
import uniffi.plasmawave_core.FfiTrack
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlaylistTrackEntry(
    val track: FfiTrack,
    val addedAt: Long?,
    val originalIndex: Int,
    val isUnavailable: Boolean = false
)

internal fun PlaylistTrackEntry.toFfi(): FfiPlaylistTrackEntry = FfiPlaylistTrackEntry(
    track = track,
    addedAt = addedAt,
    originalIndex = originalIndex.coerceAtLeast(0).toUInt()
)

internal fun FfiPlaylistTrackEntry.toUi(): PlaylistTrackEntry = PlaylistTrackEntry(
    track = track,
    addedAt = addedAt,
    originalIndex = originalIndex.toInt()
)

data class LibraryPlaylistEntry(val playlist: FfiPlaylist, val addedAt: Long?, val modifiedAt: Long? = null)

enum class LibraryPlaylistSort(val label: String) {
    DEFAULT("Varsayılan"),
    ADDED_NEWEST("Eklenme tarihi • En yeni"),
    ADDED_OLDEST("Eklenme tarihi • En eski"),
    NAME_ASC("İsim • A–Z"),
    NAME_DESC("İsim • Z–A"),
    MOST_TRACKS("Şarkı sayısı • Çoktan aza")
}

internal fun sortLibraryPlaylists(entries: List<LibraryPlaylistEntry>, order: LibraryPlaylistSort): List<LibraryPlaylistEntry> {
    return when (order) {
        LibraryPlaylistSort.DEFAULT -> entries
        LibraryPlaylistSort.ADDED_NEWEST -> entries.sortedWith(compareByDescending<LibraryPlaylistEntry> { it.addedAt != null }.thenByDescending { it.addedAt })
        LibraryPlaylistSort.ADDED_OLDEST -> entries.sortedWith(compareByDescending<LibraryPlaylistEntry> { it.addedAt != null }.thenBy { it.addedAt })
        LibraryPlaylistSort.NAME_ASC,
        LibraryPlaylistSort.NAME_DESC,
        LibraryPlaylistSort.MOST_TRACKS -> {
            val ffiSort = when (order) {
                LibraryPlaylistSort.NAME_ASC -> FfiLibraryPlaylistSort.TITLE_AZ
                LibraryPlaylistSort.NAME_DESC -> FfiLibraryPlaylistSort.TITLE_ZA
                LibraryPlaylistSort.MOST_TRACKS -> FfiLibraryPlaylistSort.TRACK_COUNT_DESC
                else -> FfiLibraryPlaylistSort.DEFAULT
            }
            try {
                val byId = entries.associateBy { it.playlist.id }
                val sortedPlaylists = PlasmaWaveApp.core.sortLibraryPlaylists(
                    entries.map { FfiLibraryPlaylistEntry(playlist = it.playlist, isPinned = false) },
                    ffiSort
                )
                sortedPlaylists.mapNotNull { byId[it.id] }
            } catch (_: Throwable) {
                val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
                when (order) {
                    LibraryPlaylistSort.NAME_ASC -> entries.sortedWith { a, b -> collator.compare(a.playlist.name, b.playlist.name) }
                    LibraryPlaylistSort.NAME_DESC -> entries.sortedWith { a, b -> collator.compare(b.playlist.name, a.playlist.name) }
                    LibraryPlaylistSort.MOST_TRACKS -> entries.sortedByDescending { it.playlist.trackCount }
                    else -> entries
                }
            }
        }
    }
}

enum class PlaylistTrackSort(val label: String) {
    ORIGINAL("Liste sırası"),
    DATE_NEWEST("Eklenme tarihi • En yeni"),
    DATE_OLDEST("Eklenme tarihi • En eski"),
    TITLE("Şarkı adı • A–Z"),
    ARTIST("Sanatçı • A–Z"),
    ALBUM("Albüm • A–Z"),
    DURATION("Süre • Kısadan uzuna")
}

/** Deezer normally supplies epoch seconds, but some endpoints return date strings. */
internal fun parseAddedAt(value: Any?): Long? {
    val raw = when (value) {
        is Number -> value.toLong()
        is String -> {
            val text = value.trim()
            text.toLongOrNull() ?: try {
                Instant.parse(text).epochSecond
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .toEpochSecond(ZoneOffset.UTC)
                } catch (_: Exception) {
                    try { LocalDate.parse(text).atStartOfDay().toEpochSecond(ZoneOffset.UTC) } catch (_: Exception) { return null }
                }
            }
        }
        else -> return null
    }
    if (raw <= 0) return null
    return if (raw > 9_999_999_999L) raw / 1000 else raw
}

internal fun sortPlaylistTracks(entries: List<PlaylistTrackEntry>, order: PlaylistTrackSort): List<PlaylistTrackEntry> {
    if (entries.size <= 1) return entries
    val ffiSort = when (order) {
        PlaylistTrackSort.ORIGINAL -> FfiPlaylistTrackSort.DEFAULT
        PlaylistTrackSort.DATE_NEWEST -> FfiPlaylistTrackSort.NEWEST_FIRST
        PlaylistTrackSort.DATE_OLDEST -> FfiPlaylistTrackSort.OLDEST_FIRST
        PlaylistTrackSort.TITLE -> FfiPlaylistTrackSort.TITLE_AZ
        PlaylistTrackSort.ARTIST -> FfiPlaylistTrackSort.ARTIST_AZ
        PlaylistTrackSort.ALBUM -> FfiPlaylistTrackSort.ALBUM_AZ
        PlaylistTrackSort.DURATION -> FfiPlaylistTrackSort.DURATION_ASC
    }
    return try {
        PlasmaWaveApp.core.sortPlaylistTracks(
            entries.map { it.toFfi() },
            ffiSort
        ).map { it.toUi() }
    } catch (_: Throwable) {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        fun compareText(left: String, right: String) = collator.compare(left, right)
        when (order) {
            PlaylistTrackSort.ORIGINAL -> entries.sortedBy { it.originalIndex }
            PlaylistTrackSort.DATE_NEWEST -> entries.sortedWith(compareByDescending<PlaylistTrackEntry> { it.addedAt != null }.thenByDescending { it.addedAt })
            PlaylistTrackSort.DATE_OLDEST -> entries.sortedWith(compareByDescending<PlaylistTrackEntry> { it.addedAt != null }.thenBy { it.addedAt })
            PlaylistTrackSort.TITLE -> entries.sortedWith { a, b -> compareText(a.track.title, b.track.title) }
            PlaylistTrackSort.ARTIST -> entries.sortedWith { a, b -> compareText(a.track.artist, b.track.artist) }
            PlaylistTrackSort.ALBUM -> entries.sortedWith { a, b -> compareText(a.track.album, b.track.album) }
            PlaylistTrackSort.DURATION -> entries.sortedBy { it.track.durationMs }
        }
    }
}
