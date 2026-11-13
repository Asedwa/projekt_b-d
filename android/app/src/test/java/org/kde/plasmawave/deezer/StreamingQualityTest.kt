package org.kde.plasmawave.deezer

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingQualityTest {
    @Test fun selectsIndependentPreferencesForMeteredAndUnmeteredNetworks() {
        assertEquals(StreamingQuality.FLAC, qualityForNetwork(false, StreamingQuality.FLAC, StreamingQuality.MP3_128))
        assertEquals(StreamingQuality.MP3_128, qualityForNetwork(true, StreamingQuality.FLAC, StreamingQuality.MP3_128))
        assertEquals(true, useMobileStreamingQuality(wifi = false, cellular = true, metered = false))
        assertEquals(false, useMobileStreamingQuality(wifi = true, cellular = false, metered = true))
        assertEquals(true, useMobileStreamingQuality(wifi = true, cellular = true, metered = false))
    }
}
