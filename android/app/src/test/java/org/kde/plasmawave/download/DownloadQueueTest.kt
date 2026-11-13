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

    @Test fun fastSkipNormalizesTrackIdsAndRecognizesOnDiskTracks() {
        val quality = DownloadQuality.MP3_320
        // Existing has "deezer:100"
        val existing = listOf(SavedDownload("x", track("deezer:100"), quality, "completed"))
        // On disk has track 200
        val onDisk = setOf("200")

        // Input contains "100" without prefix, "200" with prefix, and a new track "300"
        val input = listOf(track("100"), track("deezer:200"), track("300"))
        val selected = selectTracksToEnqueue(input, quality, existing, onDisk)

        assertEquals(listOf("300"), selected.map { it.id })
    }

    @Test fun largePlaylistWith500AlreadyDownloadedTracksSkipsInstantly() {
        val quality = DownloadQuality.MP3_320
        val onDisk = (1..500).map { it.toString() }.toSet()
        val playlist = (1..500).map { track("deezer:$it") }

        val start = System.currentTimeMillis()
        val selected = selectTracksToEnqueue(playlist, quality, emptyList(), onDisk)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Should skip all 500 tracks", selected.isEmpty())
        assertTrue("Execution should be near-instant (< 50ms), was ${elapsed}ms", elapsed < 50)
    }
}
