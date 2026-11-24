package org.kde.plasmawave

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.plasmawave.deezer.DeezerHttp
import org.kde.plasmawave.deezer.isAllowedAuthHost
import org.kde.plasmawave.download.DownloadWorker
import org.kde.plasmawave.jam.MqttRelayClient
import org.kde.plasmawave.jam.canonicalJamTrackId
import org.kde.plasmawave.jam.normalizeTrackForJam
import org.kde.plasmawave.jam.parseValidatedLanHostPort
import org.kde.plasmawave.jam.sanitizeRemoteJamTrack
import org.kde.plasmawave.jam.verifyJamCodeConstantTime
import uniffi.plasmawave_core.FfiTrack
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files

class SecurityHardeningTest {

    @Test
    fun jamCodeVerificationRejectsEmptyAndInvalidCodes() {
        assertFalse(verifyJamCodeConstantTime("A7K2M9", ""))
        assertFalse(verifyJamCodeConstantTime("", "A7K2M9"))
        assertFalse(verifyJamCodeConstantTime("", ""))
        assertFalse(verifyJamCodeConstantTime("A7K2M9", "WRONG1"))
        assertFalse(verifyJamCodeConstantTime("A7K2M9", "A7K2M9;DROP"))
        assertTrue(verifyJamCodeConstantTime("A7K2M9", "a7k2m9"))
    }

    @Test
    fun lanHostPortValidationBlocksPublicIpsAndUrlInjection() {
        assertEquals("192.168.1.25:8765", parseValidatedLanHostPort("http://192.168.1.25:8765/"))
        assertEquals("10.0.0.12:1234", parseValidatedLanHostPort("10.0.0.12:1234"))
        assertEquals("172.16.5.4:8080", parseValidatedLanHostPort("172.16.5.4:8080"))
        assertEquals("127.0.0.1:9000", parseValidatedLanHostPort("127.0.0.1:9000"))

        // Reject public internet IPs, SSRF path/query/userinfo injections, and bad ports
        assertNull(parseValidatedLanHostPort("8.8.8.8:8080"))
        assertNull(parseValidatedLanHostPort("172.32.0.1:8080"))
        assertNull(parseValidatedLanHostPort("192.168.1.25:8765/admin"))
        assertNull(parseValidatedLanHostPort("192.168.1.25:8765?x=1"))
        assertNull(parseValidatedLanHostPort("user@192.168.1.25:8765"))
        assertNull(parseValidatedLanHostPort("192.168.1.25:0"))
        assertNull(parseValidatedLanHostPort("192.168.1.25:70000"))
        assertNull(parseValidatedLanHostPort("evil.example.com:8080"))
    }

    @Test
    fun remoteJamTrackSanitizerStripsLocalAndUntrustedUris() {
        val maliciousLocal = FfiTrack(
            id = "deezer:12345",
            provider = "deezer",
            title = "Song\u0000Title",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000uL,
            artworkUrl = "file:///data/data/org.kde.plasmawave/files/secret",
            playbackUri = "content://media/external/audio/media/1",
            isPreview = false,
            origin = "real"
        )
        val cleaned = sanitizeRemoteJamTrack(maliciousLocal)
        assertNotNull(cleaned)
        assertNull(cleaned!!.artworkUrl)
        assertNull(cleaned.playbackUri)
        assertEquals("SongTitle", cleaned.title)

        // Pure local track without a trusted HTTPS Deezer CDN URI must be rejected over remote Jam
        val unplayableLocal = maliciousLocal.copy(id = "local:1", provider = "local")
        assertNull(sanitizeRemoteJamTrack(unplayableLocal))

        // Offline-downloaded Deezer track ("local:download-<digits>") normalizes to streamable "deezer:<digits>"
        val downloadedDeezer = maliciousLocal.copy(
            id = "local:download-987654",
            provider = "local",
            playbackUri = "content://media/external/audio/media/42"
        )
        val normalizedDownload = sanitizeRemoteJamTrack(downloadedDeezer)
        assertNotNull(normalizedDownload)
        assertEquals("deezer:987654", normalizedDownload!!.id)
        assertEquals("deezer", normalizedDownload.provider)
        assertNull(normalizedDownload.playbackUri)
        assertEquals(canonicalJamTrackId("local:download-987654"), canonicalJamTrackId("deezer:987654"))
        assertEquals("deezer:987654", normalizeTrackForJam(downloadedDeezer).id)

        // Valid Deezer HTTPS CDN track passes cleanly
        val validDeezer = maliciousLocal.copy(
            artworkUrl = "https://e-cdns-images.dzcdn.net/images/cover/abc/250x250.jpg",
            playbackUri = "https://cdns-preview-1.dzcdn.net/stream/c-123.mp3"
        )
        val validCleaned = sanitizeRemoteJamTrack(validDeezer)
        assertNotNull(validCleaned)
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/abc/250x250.jpg", validCleaned!!.artworkUrl)
        assertEquals("https://cdns-preview-1.dzcdn.net/stream/c-123.mp3", validCleaned.playbackUri)
    }

    @Test
    fun mqttRemainingLengthRejectsOversizedAndMalformedFrames() {
        val validSmall = DataInputStream(ByteArrayInputStream(byteArrayOf(0x7F)))
        assertEquals(127, MqttRelayClient.readRemainingLengthBounded(validSmall, 1024))

        // 2 MB frame (0x80, 0x80, 0x80, 0x01 = 2,097,152) must fail against 512 KB cap
        val oversized = DataInputStream(
            ByteArrayInputStream(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01))
        )
        assertThrows(IOException::class.java) {
            MqttRelayClient.readRemainingLengthBounded(oversized)
        }

        // 5 continuation bytes (violates MQTT v3.1.1 spec) must fail immediately
        val malformed = DataInputStream(
            ByteArrayInputStream(
                byteArrayOf(0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x01)
            )
        )
        assertThrows(IOException::class.java) {
            MqttRelayClient.readRemainingLengthBounded(malformed, Int.MAX_VALUE)
        }
    }

    @Test
    fun authAndMediaHostWhitelistsBlockUntrustedDomainsAndCleartext() {
        assertTrue(isAllowedAuthHost("www.deezer.com"))
        assertTrue(isAllowedAuthHost("accounts.google.com"))
        assertTrue(isAllowedAuthHost("appleid.apple.com"))
        assertFalse(isAllowedAuthHost("googleusercontent.com"))
        assertFalse(isAllowedAuthHost("attacker.googleusercontent.com"))
        assertFalse(isAllowedAuthHost("deezer.com.evil.org"))
        assertFalse(isAllowedAuthHost("notdeezer.com"))

        assertTrue(DeezerHttp.isTrustedMediaUrl("https://cdns-proxy-a.dzcdn.net/ media".trim().replace(" ", "").toHttpUrl()))
        assertFalse(DeezerHttp.isTrustedMediaUrl("http://cdns-proxy-a.dzcdn.net/media".toHttpUrl()))
        assertFalse(DeezerHttp.isTrustedMediaUrl("https://evil.example.com/media".toHttpUrl()))
    }

    @Test
    fun downloadPathSanitizerPreventsPathTraversal() {
        val tempRoot = Files.createTempDirectory("pw_dl_test").toFile()
        try {
            assertEquals("fallback", DownloadWorker.sanitizeDownloadSegment("..", 80, "fallback"))
            assertEquals("fallback", DownloadWorker.sanitizeDownloadSegment("...", 80, "fallback"))
            assertEquals("_secret", DownloadWorker.sanitizeDownloadSegment("../secret", 80, "fallback"))

            val dirDotDot = DownloadWorker.resolveSafeDownloadDirectory(tempRoot, "..")
            assertEquals(tempRoot.canonicalPath, dirDotDot.canonicalPath)

            val dirTraversal = DownloadWorker.resolveSafeDownloadDirectory(tempRoot, "../../etc")
            assertTrue(dirTraversal.canonicalPath.startsWith(tempRoot.canonicalPath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
