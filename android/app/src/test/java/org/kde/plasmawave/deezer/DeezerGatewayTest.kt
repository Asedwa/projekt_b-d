package org.kde.plasmawave.deezer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeezerGatewayTest {
    private val login = """{"error":{},"results":{"checkForm":"csrf","USER":{"USER_ID":42,"BLOG_NAME":"Listener","OPTIONS":{"license_token":"license","web_hq":true}}}}"""

    @Test fun loginAndResolveFullTrackUseAccountAndNotPreview() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val gateway = DeezerGateway("test-session", DeezerGateway.Transport { url, body, cookie ->
            calls.add(Triple(url, body, cookie))
            when {
                url.contains("deezer.getUserData") -> DeezerGateway.Reply(login, listOf("sid=session-id; Path=/"))
                url.contains("song.getData") -> DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"track-token"}}""")
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_320","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
            }
        })
        assertEquals("Listener", gateway.login().name)
        val media = gateway.resolve("1234")
        assertEquals("https://test.dzcdn.net/audio", media.url)
        assertEquals("1234", media.trackId)
        assertTrue(calls[1].third!!.contains("sid=session-id"))
        val payload = JSONObject(calls[2].second)
        assertEquals("license", payload.getString("license_token"))
        assertEquals("track-token", payload.getJSONArray("track_tokens").getString(0))
        assertEquals("FULL", payload.getJSONArray("media").getJSONObject(0).getString("type"))
        assertNull(calls[2].third)
    }

    @Test fun exactDownloadFormatIsRequestedAndMustBeHonored() {
        var requestedFormats: org.json.JSONArray? = null
        var returned = "FLAC"
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("song.getData") -> DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                else -> {
                    requestedFormats = JSONObject(body).getJSONArray("media").getJSONObject(0).getJSONArray("formats")
                    DeezerGateway.Reply("""{"data":[{"media":[{"format":"$returned","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
                }
            }
        })
        assertEquals("FLAC", gateway.resolve("123", "FLAC").format)
        assertEquals(1, requestedFormats!!.length())
        assertEquals("FLAC", requestedFormats!!.getJSONObject(0).getString("format"))
        returned = "MP3_128"
        assertThrows(DeezerException::class.java) { gateway.resolve("123", "FLAC") }
        assertThrows(IllegalArgumentException::class.java) { gateway.resolve("123", "MP3_FAKE") }
    }

    @Test fun expiredGatewayTokenIsRefreshedAndRetried() {
        var logins = 0
        var songs = 0
        val gateway = DeezerGateway("test-session", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("deezer.getUserData") -> { logins++; DeezerGateway.Reply(login) }
                url.contains("song.getData") -> {
                    songs++
                    if (songs == 1) DeezerGateway.Reply("""{"error":{"VALID_TOKEN_REQUIRED":"expired"}}""")
                    else DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                }
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_128","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
            }
        })
        gateway.login()
        gateway.resolve("42")
        assertEquals(2, logins)
        assertEquals(2, songs)
    }

    @Test fun invalidSessionAndUntrustedMediaAreRejected() {
        val invalid = DeezerGateway("bad", DeezerGateway.Transport { _, _, _ ->
            DeezerGateway.Reply("""{"error":{},"results":{"checkForm":"x","USER":{"USER_ID":0}}}""")
        })
        assertThrows(IOException::class.java) { invalid.login() }
        val untrusted = DeezerGateway("x", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("song.getData") -> DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"sources":[{"url":"https://evil.example/audio"}]}]}]}""")
            }
        })
        untrusted.login()
        assertThrows(IOException::class.java) { untrusted.resolve("123") }
    }
    @Test fun fallbackStreamUsesFallbackTrackIdForDecryption() {
        var mediaRequests = 0
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("song.getData") -> DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"original","FALLBACK":{"SNG_ID":"987","TRACK_TOKEN":"fallback"}}}""")
                else -> {
                    mediaRequests++
                    if (mediaRequests == 1) DeezerGateway.Reply("""{"data":[{"errors":[{"code":1}]}]}""")
                    else DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_128","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
                }
            }
        })
        gateway.login()
        assertEquals("987", gateway.resolve("123").trackId)
        assertEquals(2, mediaRequests)
    }

    @Test fun favoritesLoadMultiplePages() {
        val starts = mutableListOf<Int>()
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else {
                val start = JSONObject(body).getInt("start")
                starts.add(start)
                val size = if (start == 0) 100 else 2
                val data = org.json.JSONArray()
                repeat(size) { data.put(JSONObject().put("SNG_ID", start + it)) }
                DeezerGateway.Reply(JSONObject().put("error", JSONObject()).put("results", JSONObject().put("data", data)).toString())
            }
        })
        assertEquals(102, gateway.favorites().length())
        assertEquals(listOf(0, 100), starts)
    }

    @Test fun libraryEditsAndMixUseAuthenticatedEndpoints() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else {
                calls.add(url to JSONObject(body))
                if (url.contains("song.getSearchTrackMix"))
                    DeezerGateway.Reply("""{"error":{},"results":{"data":[{"SNG_ID":"123"}]}}""")
                else if (url.contains("playlist.create")) DeezerGateway.Reply("""{"error":{},"results":456}""")
                else DeezerGateway.Reply("""{"error":{},"results":true}""")
            }
        })
        gateway.setFavorite("123", true)
        gateway.setFavorite("123", false)
        gateway.editPlaylist("456", "123", true)
        gateway.editPlaylist("456", "123", false)
        assertEquals(1, gateway.trackMix("123").length())
        assertEquals("456", gateway.createPlaylist("  Test listesi  "))
        gateway.deletePlaylist("456")
        assertTrue(calls[0].first.contains("favorite_song.add"))
        assertTrue(calls[1].first.contains("favorite_song.remove"))
        assertTrue(calls[2].first.contains("playlist.addSongs"))
        assertEquals("456", calls[2].second.getString("playlist_id"))
        assertEquals("123", calls[2].second.getJSONArray("songs").getJSONArray(0).getString(0))
        assertTrue(calls[3].first.contains("playlist.deleteSongs"))
        assertTrue(calls[4].first.contains("song.getSearchTrackMix"))
        assertTrue(calls[5].first.contains("playlist.create"))
        assertEquals("Test listesi", calls[5].second.getString("title"))
        assertEquals(1, calls[5].second.getInt("status"))
        assertTrue(calls[6].first.contains("playlist.delete"))
    }

    @Test fun favoritesCanExceedFiveThousandAndRepeatedPagesFail() {
        var repeatPage = false
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login) else {
                val start = if (repeatPage) 0 else JSONObject(body).getInt("start")
                val size = if (start < 5100) 100 else 2
                val data = org.json.JSONArray()
                repeat(size) { data.put(JSONObject().put("SNG_ID", start + it)) }
                DeezerGateway.Reply(JSONObject().put("error", JSONObject()).put("results", JSONObject().put("data", data)).toString())
            }
        })
        assertEquals(5102, gateway.favorites().length())
        repeatPage = true
        assertThrows(DeezerException::class.java) { gateway.favorites() }
    }
    @Test fun playlistUsesDeezerPageAndFollowsActualPageSize() {
        val starts = mutableListOf<Int>()
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else {
                assertTrue(url.contains("method=deezer.pagePlaylist"))
                val request = JSONObject(body)
                assertEquals("314", request.getString("playlist_id"))
                val start = request.getInt("start")
                starts.add(start)
                val data = org.json.JSONArray()
                repeat(minOf(50, 125 - start)) { data.put(JSONObject().put("SNG_ID", start + it)) }
                val songs = JSONObject().put("data", data).put("total", 125)
                DeezerGateway.Reply(JSONObject().put("error", JSONObject()).put("results", JSONObject().put("SONGS", songs)).toString())
            }
        })
        assertEquals(125, gateway.playlistTracks("314").length())
        assertEquals(listOf(0, 50, 100), starts)
    }

    @Test fun playlistFallsBackToLegacyEndpointAndReportsMissingData() {
        val methods = mutableListOf<String>()
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("deezer.pagePlaylist") -> {
                    methods.add("page")
                    DeezerGateway.Reply("""{"error":{},"results":{}}""")
                }
                else -> {
                    methods.add("legacy")
                    DeezerGateway.Reply("""{"error":{},"results":{"data":[{"SNG_ID":"4"}],"total":1}}""")
                }
            }
        })
        assertEquals(1, gateway.playlistTracks("314").length())
        assertEquals(listOf("page", "legacy"), methods)
        val invalid = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else DeezerGateway.Reply("""{"error":{},"results":{}}""")
        })
        assertThrows(DeezerException::class.java) { invalid.playlistTracks("314") }
    }

    @Test fun playlistIsNotMisreportedEmptyWhenServerSaysTracksExist() {
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else DeezerGateway.Reply("""{"error":{},"results":{"SONGS":{"data":[],"total":2}}}""")
        })
        assertThrows(DeezerException::class.java) { gateway.playlistTracks("314") }
    }

    @Test fun nonTokenSessionErrorsRefreshAndRetry() {
        var logins = 0
        var calls = 0
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> { logins++; DeezerGateway.Reply(login) }
                url.contains("song.getData") -> {
                    calls++
                    if (calls == 1) DeezerGateway.Reply("""{"error":{"NEED_USER_AUTH_REQUIRED":"Session expired"}}""")
                    else DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                }
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_320","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
            }
        })
        gateway.login()
        assertEquals("MP3_320", gateway.resolve("55").format)
        assertEquals(2, logins)
        assertEquals(2, calls)
    }

    @Test fun transientMalformedHtmlResponseRetriesAndRecovers() {
        var songAttempts = 0
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("song.getData") -> {
                    songAttempts++
                    if (songAttempts == 1) DeezerGateway.Reply("<html>502 Bad Gateway</html>")
                    else DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                }
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_128","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
            }
        })
        gateway.login()
        assertEquals("MP3_128", gateway.resolve("99").format)
        assertEquals(2, songAttempts)
    }

    @Test fun playlistsKeepFirstPageIfSecondPageFails() {
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, body, _ ->
            if (url.contains("getUserData")) DeezerGateway.Reply(login)
            else {
                val index = JSONObject(body).getInt("index")
                if (index == 0) {
                    val data = org.json.JSONArray()
                    repeat(100) { data.put(JSONObject().put("PLAYLIST_ID", (1000 + it).toString()).put("TITLE", "List $it")) }
                    DeezerGateway.Reply(JSONObject().put("error", JSONObject()).put("results", JSONObject().put("TAB", JSONObject().put("playlists", JSONObject().put("data", data)))).toString())
                } else {
                    throw IOException("timeout on page 2")
                }
            }
        })
        assertEquals(100, gateway.playlists().length())
    }

    @Test fun concurrentPlaylistsDoNotBlockTrackResolve() {
        val playlistEntered = java.util.concurrent.CountDownLatch(1)
        val allowPlaylistFinish = java.util.concurrent.CountDownLatch(1)
        val gateway = DeezerGateway("test", DeezerGateway.Transport { url, _, _ ->
            when {
                url.contains("getUserData") -> DeezerGateway.Reply(login)
                url.contains("deezer.pageProfile") -> {
                    playlistEntered.countDown()
                    allowPlaylistFinish.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    DeezerGateway.Reply("""{"error":{},"results":{"TAB":{"playlists":{"data":[]}}}}""")
                }
                url.contains("song.getData") -> DeezerGateway.Reply("""{"error":{},"results":{"TRACK_TOKEN":"t"}}""")
                else -> DeezerGateway.Reply("""{"data":[{"media":[{"format":"MP3_320","sources":[{"url":"https://test.dzcdn.net/audio"}]}]}]}""")
            }
        })
        gateway.login()
        val playlistThread = Thread { gateway.playlists() }
        playlistThread.start()
        assertTrue(playlistEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val media = gateway.resolve("777")
        assertEquals("777", media.trackId)
        allowPlaylistFinish.countDown()
        playlistThread.join(2000)
    }
}
