package org.kde.plasmawave.download

import org.junit.Assert.*
import org.junit.Test
import uniffi.plasmawave_core.FfiTrack

class DownloadQueueTest {
    private fun track(id: String, provider: String = "deezer") =
        FfiTrack(id, provider, "title", "artist", "album", 0uL, null, null, false, "real")

    @Test fun bulkSelectionPreservesOrderAndSkipsDuplicatesAndCompletedTracks() {
        val quality = DownloadQuality.FLAC
        val alreadyQueued = SavedDownload("a", track("deezer:1"), quality, "queued")
        val alreadyCompleted = SavedDownload("b", track("deezer:2"), quality, "completed")
        val oldFailure = SavedDownload("c", track("deezer:3"), quality, "failed")
        val otherQuality = SavedDownload("d", track("deezer:4"), DownloadQuality.MP3_128, "completed")
        val input = listOf(track("deezer:2"), track("deezer:4"), track("deezer:3"),
            track("deezer:4"), track("deezer:1"), track("local:5", "local"), track("deezer:bad"))
        assertEquals(listOf("deezer:4", "deezer:3"), selectTracksToEnqueue(input, quality,
            listOf(alreadyQueued, alreadyCompleted, oldFailure, otherQuality)).map { it.id })
    }

    @Test fun cellularRequiresExplicitPermissionEvenWhenUnmetered() {
        assertFalse(mayUseDownloadNetwork(false, unmetered = true, wifi = false, ethernet = false, cellular = true))
        assertFalse(mayUseDownloadNetwork(false, unmetered = true, wifi = true, ethernet = false, cellular = true))
        assertFalse(mayUseDownloadNetwork(false, unmetered = false, wifi = true, ethernet = false, cellular = false))
        assertTrue(mayUseDownloadNetwork(false, unmetered = true, wifi = true, ethernet = false, cellular = false))
        assertTrue(mayUseDownloadNetwork(true, unmetered = false, wifi = false, ethernet = false, cellular = true))
    }
}
