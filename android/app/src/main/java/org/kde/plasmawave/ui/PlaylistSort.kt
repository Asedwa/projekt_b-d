package org.kde.plasmawave.ui

import uniffi.plasmawave_core.FfiTrack
import uniffi.plasmawave_core.FfiPlaylist
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlaylistTrackEntry(val track: FfiTrack, val addedAt: Long?, val originalIndex: Int)

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
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
    return when (order) {
        LibraryPlaylistSort.DEFAULT -> entries
        LibraryPlaylistSort.ADDED_NEWEST -> entries.sortedWith(compareByDescending<LibraryPlaylistEntry> { it.addedAt != null }.thenByDescending { it.addedAt })
        LibraryPlaylistSort.ADDED_OLDEST -> entries.sortedWith(compareByDescending<LibraryPlaylistEntry> { it.addedAt != null }.thenBy { it.addedAt })
        LibraryPlaylistSort.NAME_ASC -> entries.sortedWith { a, b -> collator.compare(a.playlist.name, b.playlist.name) }
        LibraryPlaylistSort.NAME_DESC -> entries.sortedWith { a, b -> collator.compare(b.playlist.name, a.playlist.name) }
        LibraryPlaylistSort.MOST_TRACKS -> entries.sortedByDescending { it.playlist.trackCount }
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
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
    fun compareText(left: String, right: String) = collator.compare(left, right)
    return when (order) {
        PlaylistTrackSort.ORIGINAL -> entries.sortedBy { it.originalIndex }
        PlaylistTrackSort.DATE_NEWEST -> entries.sortedWith(compareByDescending<PlaylistTrackEntry> { it.addedAt != null }.thenByDescending { it.addedAt })
        PlaylistTrackSort.DATE_OLDEST -> entries.sortedWith(compareByDescending<PlaylistTrackEntry> { it.addedAt != null }.thenBy { it.addedAt })
        PlaylistTrackSort.TITLE -> entries.sortedWith { a, b -> compareText(a.track.title, b.track.title) }
        PlaylistTrackSort.ARTIST -> entries.sortedWith { a, b -> compareText(a.track.artist, b.track.artist) }
        PlaylistTrackSort.ALBUM -> entries.sortedWith { a, b -> compareText(a.track.album, b.track.album) }
        PlaylistTrackSort.DURATION -> entries.sortedBy { it.track.durationMs }
    }
}
