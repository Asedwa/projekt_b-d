package org.kde.plasmawave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.plasmawave_core.FfiTrack

class PinnedItemsTest {
    @Test fun deezerMetadataRoundTripsWithoutPersistingMediaUrls() {
        val track = FfiTrack("deezer:42", "deezer", "Title", "Artist", "Album", 1234uL,
            "https://example.com/cover", "https://example.com/temporary-media", false, "real")
        val json = trackJson(track)
        assertFalse(json.toString().contains("temporary-media"))
        assertEquals(track.copy(playbackUri = null), jsonTrack(json))
    }

    @Test fun localContentUriSurvivesRoundTrip() {
        val track = FfiTrack("local:7", "local", "Title", "Artist", "Album", 0uL,
            null, "content://media/external/audio/media/7", false, "real")
        val result = jsonTrack(trackJson(track))
        assertEquals(track, result)
        assertNull(result.artworkUrl)
    }
}
