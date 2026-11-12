package org.kde.plasmawave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.plasmawave_core.FfiTrack
import uniffi.plasmawave_core.FfiPlaylist

class PlaylistSortTest {
    private fun entry(id: String, title: String, date: Long?, index: Int) = PlaylistTrackEntry(
        FfiTrack(id, "deezer", title, "Artist", "Album", 60_000uL, null, null, false, "real"), date, index
    )
    @Test fun dateSortUsesRealDatesAndKeepsUnknownLast() {
        val songs = listOf(entry("1", "B", 100, 0), entry("2", "A", null, 1), entry("3", "C", 300, 2), entry("1", "B", 200, 3))
        assertEquals(listOf("3", "1", "1", "2"), sortPlaylistTracks(songs, PlaylistTrackSort.DATE_NEWEST).map { it.track.id })
        assertEquals(listOf(0, 3, 2, 1), sortPlaylistTracks(songs, PlaylistTrackSort.DATE_OLDEST).map { it.originalIndex })
        assertEquals(listOf("2", "1", "1", "3"), sortPlaylistTracks(songs, PlaylistTrackSort.TITLE).map { it.track.id })
    }
    @Test fun parsesDeezerEpochAndDates() {
        assertEquals(1_700_000_000L, parseAddedAt(1_700_000_000))
        assertEquals(1_700_000_000L, parseAddedAt("1700000000000"))
        assertEquals(1_700_000_000L, parseAddedAt("2023-11-14T22:13:20Z"))
        assertNull(parseAddedAt("0"))
        assertNull(parseAddedAt("invalid"))
    }
    @Test fun librarySortUsesAddedDateAndKeepsUnknownLast() {
        fun playlist(id: String, name: String, date: Long?, count: UInt) = LibraryPlaylistEntry(
            FfiPlaylist(id, name, null, false, count, null, "real"), date
        )
        val lists = listOf(playlist("1", "Beta", 100, 5u), playlist("2", "Alpha", null, 10u), playlist("3", "Gamma", 300, 1u))
        assertEquals(listOf("3", "1", "2"), sortLibraryPlaylists(lists, LibraryPlaylistSort.ADDED_NEWEST).map { it.playlist.id })
        assertEquals(listOf("1", "3", "2"), sortLibraryPlaylists(lists, LibraryPlaylistSort.ADDED_OLDEST).map { it.playlist.id })
        assertEquals(listOf("2", "1", "3"), sortLibraryPlaylists(lists, LibraryPlaylistSort.NAME_ASC).map { it.playlist.id })
        assertEquals(listOf("2", "1", "3"), sortLibraryPlaylists(lists, LibraryPlaylistSort.MOST_TRACKS).map { it.playlist.id })
    }
}
