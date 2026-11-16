package org.kde.plasmawave.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.plasmawave.player.selectLookaheadTracks
import uniffi.plasmawave_core.FfiQueueEntry
import uniffi.plasmawave_core.FfiQueueSnapshot
import uniffi.plasmawave_core.FfiRepeatMode
import uniffi.plasmawave_core.FfiTrack

class DeezerPlaybackStabilityTest {

    @Test fun lockedFormatOverridesNetworkQualitySwitchMidTrack() {
        assertEquals("FLAC", selectResolveFormat("FLAC", StreamingQuality.MP3_128))
        assertEquals("MP3_320", selectResolveFormat("MP3_320", StreamingQuality.MP3_128))
        assertEquals("MP3_128", selectResolveFormat("MP3_128", StreamingQuality.FLAC))
    }

    @Test fun unlockedFormatUsesCurrentNetworkQuality() {
        assertEquals("MP3_128", selectResolveFormat(null, StreamingQuality.MP3_128))
        assertEquals("FLAC", selectResolveFormat(null, StreamingQuality.FLAC))
        assertNull(selectResolveFormat(null, StreamingQuality.AUTO))
        assertEquals("MP3_128", selectResolveFormat("MP3_MISC", StreamingQuality.MP3_128))
    }

    @Test fun streamCacheKeyStaysConsistentForSameRequestedQuality() {
        val k1 = deezerStreamCacheKey("deezer:12345", StreamingQuality.AUTO)
        val k2 = deezerStreamCacheKey("12345", StreamingQuality.AUTO)
        assertEquals("deezer:12345:AUTO", k1)
        assertEquals(k1, k2)
    }

    @Test fun lookaheadSelectsUpcomingTracksAndRespectsRepeatModes() {
        val entries = (0..3).map { idx ->
            FfiQueueEntry(
                entryId = idx.toULong(),
                track = FfiTrack(
                    id = "deezer:$idx",
                    provider = "deezer",
                    title = "Track $idx",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000uL,
                    artworkUrl = null,
                    playbackUri = null,
                    isPreview = false,
                    origin = "real"
                )
            )
        }
        val midOff = FfiQueueSnapshot(entries, currentIndex = 1u, isShuffled = false, repeatMode = FfiRepeatMode.OFF)
        assertEquals(listOf("deezer:2", "deezer:3"), selectLookaheadTracks(midOff).map { it.id })

        val endOff = FfiQueueSnapshot(entries, currentIndex = 3u, isShuffled = false, repeatMode = FfiRepeatMode.OFF)
        assertTrue(selectLookaheadTracks(endOff).isEmpty())

        val endAll = FfiQueueSnapshot(entries, currentIndex = 3u, isShuffled = false, repeatMode = FfiRepeatMode.ALL)
        assertEquals(listOf("deezer:0", "deezer:1"), selectLookaheadTracks(endAll).map { it.id })

        val repeatOne = FfiQueueSnapshot(entries, currentIndex = 1u, isShuffled = false, repeatMode = FfiRepeatMode.ONE)
        assertTrue(selectLookaheadTracks(repeatOne).isEmpty())
    }
}
