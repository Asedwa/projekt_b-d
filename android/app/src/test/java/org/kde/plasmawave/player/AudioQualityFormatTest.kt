package org.kde.plasmawave.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityFormatTest {

    @Test
    fun formatsFlacCorrectly() {
        // Deezer FLAC
        assertEquals("FLAC • 44,1 kHz • Kayıpsız", formatAudioQuality(null, 44100, 0, deezerFormat = "FLAC"))
        // Hi-res FLAC
        assertEquals("FLAC • 96 kHz • Kayıpsız", formatAudioQuality("audio/flac", 96000, 0, deezerFormat = "FLAC"))
        assertEquals("FLAC • 48 kHz • Kayıpsız", formatAudioQuality("audio/flac", 48000, 0))
    }

    @Test
    fun formatsMp3Correctly() {
        // Deezer 320 kbps
        assertEquals("MP3 • 320 kb/s • 44,1 kHz", formatAudioQuality(null, 44100, 0, deezerFormat = "MP3_320"))
        // Deezer 128 kbps
        assertEquals("MP3 • 128 kb/s • 44,1 kHz", formatAudioQuality(null, 44100, 0, deezerFormat = "MP3_128"))
        // Decoded MP3 bitrate
        assertEquals("MP3 • 320 kb/s • 44,1 kHz", formatAudioQuality("audio/mpeg", 44100, 320000))
        assertEquals("MP3 • 128 kb/s • 44,1 kHz", formatAudioQuality("audio/mpeg", 44100, 128000))
        assertEquals("MP3 • 256 kb/s • 44,1 kHz", formatAudioQuality("audio/mpeg", 44100, 256000))
    }

    @Test
    fun formatsWavAndAacCorrectly() {
        assertEquals("WAV • 44,1 kHz • Kayıpsız", formatAudioQuality("audio/wav", 44100, 0))
        assertEquals("AAC • 256 kb/s • 44,1 kHz", formatAudioQuality("audio/mp4a-latm", 44100, 256000))
    }

    @Test
    fun previewAlwaysShows128Kbps() {
        assertEquals("MP3 • 128 kb/s • 44,1 kHz", formatAudioQuality("audio/flac", 96000, 0, deezerFormat = "FLAC", isPreview = true))
    }
}
