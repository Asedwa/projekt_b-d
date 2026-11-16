package org.kde.plasmawave.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
