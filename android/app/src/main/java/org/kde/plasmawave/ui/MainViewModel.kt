package org.kde.plasmawave.ui

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.player.PlaybackManager
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.DeezerWebCookies
import org.kde.plasmawave.deezer.DeezerException
import java.util.concurrent.atomic.AtomicLong
import uniffi.plasmawave_core.FfiAlbum
import uniffi.plasmawave_core.FfiArtist
import uniffi.plasmawave_core.FfiDeezerUser
import uniffi.plasmawave_core.FfiDownloadTask
import uniffi.plasmawave_core.FfiLocalTrackInput
import uniffi.plasmawave_core.FfiModInfo
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiSettings
import uniffi.plasmawave_core.FfiTheme
import uniffi.plasmawave_core.FfiTrack

const val LOCAL_FAVORITES_ID = "local:favorite_tracks"

enum class SearchCategory {
    TRACKS, ALBUMS, ARTISTS, PLAYLISTS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val core = PlasmaWaveApp.core
    val playbackManager = PlaybackManager.getInstance(application)
    private val deezerSession = DeezerSession.get(application)
    private val deezerGeneration = AtomicLong()
    private val _deezerBusy = MutableStateFlow(false)
    val deezerBusy: StateFlow<Boolean> = _deezerBusy.asStateFlow()
    private val _deezerLoginError = MutableStateFlow<String?>(null)
    val deezerLoginError: StateFlow<String?> = _deezerLoginError.asStateFlow()

    private val _activeTheme = MutableStateFlow(core.getCurrentTheme())
    val activeTheme: StateFlow<FfiTheme> = _activeTheme.asStateFlow()

    private val _availableThemes = MutableStateFlow(core.listThemes())
    val availableThemes: StateFlow<List<FfiTheme>> = _availableThemes.asStateFlow()

    private val _installedMods = MutableStateFlow(core.listMods())
    val installedMods: StateFlow<List<FfiModInfo>> = _installedMods.asStateFlow()

    private val _customTrackActions = MutableStateFlow(core.getCustomTrackActions())
    val customTrackActions: StateFlow<List<FfiModInfo>> = _customTrackActions.asStateFlow()

    private val _playlists = MutableStateFlow<List<FfiPlaylist>>(emptyList())
    val playlists: StateFlow<List<FfiPlaylist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow<List<FfiTrack>>(emptyList())
    val favorites: StateFlow<List<FfiTrack>> = _favorites.asStateFlow()

    private val _localTracks = MutableStateFlow<List<FfiTrack>>(emptyList())
    val localTracks: StateFlow<List<FfiTrack>> = _localTracks.asStateFlow()

    private val _deezerUser = MutableStateFlow<FfiDeezerUser?>(null)
    val deezerUser: StateFlow<FfiDeezerUser?> = _deezerUser.asStateFlow()

    private val _deezerPlaylists = MutableStateFlow<List<LibraryPlaylistEntry>>(emptyList())
    val deezerPlaylists: StateFlow<List<LibraryPlaylistEntry>> = _deezerPlaylists.asStateFlow()
    private val _deezerLibraryLoading = MutableStateFlow(deezerSession.hasSavedSession)
    val deezerLibraryLoading: StateFlow<Boolean> = _deezerLibraryLoading.asStateFlow()

    private val _deezerFavorites = MutableStateFlow<List<FfiTrack>>(emptyList())
    val deezerFavorites: StateFlow<List<FfiTrack>> = _deezerFavorites.asStateFlow()

    private val _deezerFlow = MutableStateFlow<List<FfiTrack>>(emptyList())
    val deezerFlow: StateFlow<List<FfiTrack>> = _deezerFlow.asStateFlow()

    private val _searchCategory = MutableStateFlow(SearchCategory.TRACKS)
    val searchCategory: StateFlow<SearchCategory> = _searchCategory.asStateFlow()

    private val _searchAlbums = MutableStateFlow<List<FfiAlbum>>(emptyList())
    val searchAlbums: StateFlow<List<FfiAlbum>> = _searchAlbums.asStateFlow()

    private val _searchArtists = MutableStateFlow<List<FfiArtist>>(emptyList())
    val searchArtists: StateFlow<List<FfiArtist>> = _searchArtists.asStateFlow()

    private val _searchPlaylists = MutableStateFlow<List<FfiPlaylist>>(emptyList())
    val searchPlaylists: StateFlow<List<FfiPlaylist>> = _searchPlaylists.asStateFlow()

    private val _searchResults = MutableStateFlow<List<FfiTrack>>(emptyList())
    val searchResults: StateFlow<List<FfiTrack>> = _searchResults.asStateFlow()

    private val _downloads = MutableStateFlow<List<FfiDownloadTask>>(emptyList())
    val downloads: StateFlow<List<FfiDownloadTask>> = _downloads.asStateFlow()

    private val _settings = MutableStateFlow(core.getSettings())
    val settings: StateFlow<FfiSettings> = _settings.asStateFlow()

    // Contextual Long-press state
    private val _selectedTrackForMenu = MutableStateFlow<FfiTrack?>(null)
    val selectedTrackForMenu: StateFlow<FfiTrack?> = _selectedTrackForMenu.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        // Recover a sign-in already completed in the embedded browser, including
        // sessions from a previous build that got stuck at profile selection.
        if (!deezerSession.hasSavedSession) {
            try { DeezerWebCookies.readArl()?.let { connectDeezerArl(it) } } catch (_: Exception) { }
        }
        loadData()
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshThemes()
            refreshMods()
            refreshPlaylists()
            refreshFavorites()
            refreshLocalTracks()
            refreshDownloads()
            refreshDeezerData()
        }
    }

    fun refreshThemes() {
        _activeTheme.value = core.getCurrentTheme()
        _availableThemes.value = core.listThemes()
    }

    fun setTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val theme = core.setTheme(themeId)
                _activeTheme.value = theme
                _settings.value = core.getSettings()
                refreshThemes()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to set theme", e)
            }
        }
    }

    fun setCustomAccentColor(hexColor: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTheme = core.setCustomAccentColor(hexColor)
                _activeTheme.value = updatedTheme
                _settings.value = core.getSettings()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Geçersiz renk kodu: ${e.message}"
                }
            }
        }
    }

    fun importThemeJson(jsonStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imported = core.importThemeJson(jsonStr)
                core.setTheme(imported.id)
                refreshThemes()
                _statusMessage.value = "Theme '${imported.name}' imported!"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to import theme: ${e.message}"
            }
        }
    }

    fun refreshMods() {
        _installedMods.value = core.listMods()
        _customTrackActions.value = core.getCustomTrackActions()
    }

    fun setModEnabled(modId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                core.setModEnabled(modId, enabled)
                refreshMods()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to toggle mod", e)
            }
        }
    }

    fun refreshPlaylists() {
        try {
            _playlists.value = core.getPlaylists()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading playlists", e)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                core.createPlaylist(name)
                refreshPlaylists()
            } catch (e: Exception) {
                _statusMessage.value = "Error creating playlist: ${e.message}"
            }
        }
    }

    fun refreshFavorites() {
        try {
            _favorites.value = core.getFavorites()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading favorites", e)
        }
    }

    fun refreshLocalTracks() {
        _localTracks.value = core.getLocalTracks()
    }

    fun refreshDownloads() {
        try {
            _downloads.value = core.listDownloads()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading downloads", e)
        }
    }

    fun connectDeezerArl(arl: String) {
        if (_deezerBusy.value) return
        _deezerBusy.value = true
        _deezerLibraryLoading.value = true
        _deezerLoginError.value = null
        val generation = deezerGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = deezerSession.login(arl)
                if (generation != deezerGeneration.get()) return@launch
                // Remove legacy profile/demo/token state left by earlier versions.
                try { core.clearDeezerUser() } catch (_: Exception) { }
                _deezerUser.value = user
                withContext(Dispatchers.Main) {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }
                loadDeezerLibrary(generation)
                if (generation == deezerGeneration.get()) _statusMessage.value = "Deezer'a ${user.name} olarak giriş yapıldı"
            } catch (e: Exception) {
                if (generation == deezerGeneration.get()) {
                    val message = "Deezer girişi başarısız: ${(e as? DeezerException)?.message ?: "Bağlantıyı kontrol et"}"
                    _deezerLoginError.value = message
                    _statusMessage.value = message
                }
            } finally {
                if (generation == deezerGeneration.get()) {
                    _deezerBusy.value = false
                    _deezerLibraryLoading.value = false
                }
            }
        }
    }

    fun disconnectDeezer() {
        val generation = deezerGeneration.incrementAndGet()
        _deezerBusy.value = true
        playbackManager.stopPlayback()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        _deezerUser.value = null
        _deezerLibraryLoading.value = false
        _deezerPlaylists.value = emptyList()
        _deezerFavorites.value = emptyList()
        _deezerFlow.value = emptyList()
        _statusMessage.value = "Deezer oturumu kapatıldı"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deezerSession.logout()
                try { core.clearDeezerUser() } catch (_: Exception) { }
            } finally {
                if (generation == deezerGeneration.get()) _deezerBusy.value = false
            }
        }
    }

    fun refreshDeezerData() {
        val generation = deezerGeneration.get()
        _deezerLibraryLoading.value = deezerSession.hasSavedSession
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = deezerSession.restore()
                if (generation != deezerGeneration.get()) return@launch
                _deezerUser.value = user
                if (user != null) loadDeezerLibrary(generation)
            } catch (e: Exception) {
                if (generation == deezerGeneration.get()) {
                    _statusMessage.value = "Deezer oturumu açılamadı: ${(e as? DeezerException)?.message ?: "Bağlantıyı kontrol et"}"
                }
            } finally {
                if (generation == deezerGeneration.get()) _deezerLibraryLoading.value = false
            }
        }
    }

    private fun loadDeezerLibrary(generation: Long) {
        try {
            val playlists = deezerSession.playlists()
            if (generation == deezerGeneration.get()) _deezerPlaylists.value = playlists
        } catch (_: Exception) {
            if (generation == deezerGeneration.get()) _statusMessage.value = "Deezer çalma listeleri yüklenemedi"
        }
        if (generation != deezerGeneration.get()) return
        try {
            val favorites = deezerSession.favorites()
            if (generation == deezerGeneration.get()) _deezerFavorites.value = favorites
        } catch (_: Exception) {
            if (generation == deezerGeneration.get()) _statusMessage.value = "Deezer favorileri yüklenemedi"
        }
    }

    fun setCategory(category: SearchCategory) {
        _searchCategory.value = category
    }

    fun search(query: String, category: SearchCategory = _searchCategory.value) {
        _searchCategory.value = category
        viewModelScope.launch(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) {
                _searchResults.value = emptyList()
                _searchAlbums.value = emptyList()
                _searchArtists.value = emptyList()
                _searchPlaylists.value = emptyList()
                return@launch
            }
            try {
                when (category) {
                    SearchCategory.TRACKS -> {
                        _searchResults.value = core.searchAll(q)
                    }
                    SearchCategory.ALBUMS -> {
                        _searchAlbums.value = core.searchDeezerAlbums(q)
                    }
                    SearchCategory.ARTISTS -> {
                        _searchArtists.value = core.searchDeezerArtists(q)
                    }
                    SearchCategory.PLAYLISTS -> {
                        _searchPlaylists.value = core.searchDeezerPlaylists(q)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Search error", e)
            }
        }
    }

    fun downloadTrack(track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = core.downloadTrack(track)
                refreshDownloads()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Downloading: ${track.title}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Download failed: ${e.message}"
                }
            }
        }
    }

    fun playDownloadedTask(task: FfiDownloadTask) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = core.getOfflinePath(task.trackId)
                if (path != null) {
                    val offlineTrack = FfiTrack(
                        id = task.trackId,
                        provider = "local",
                        title = task.fileName.substringBeforeLast('.'),
                        artist = "Downloaded",
                        album = "Offline Storage",
                        durationMs = 0uL,
                        artworkUrl = null,
                        playbackUri = "file://$path",
                        isPreview = task.trackId.startsWith("deezer:"),
                        origin = "real"
                    )
                    withContext(Dispatchers.Main) {
                        playbackManager.playTrackList(listOf(offlineTrack), 0)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error playing offline task", e)
            }
        }
    }

    suspend fun getAlbumTracks(albumId: String): List<FfiTrack> = withContext(Dispatchers.IO) {
        try { core.getDeezerAlbumTracks(albumId) } catch (e: Exception) { emptyList() }
    }

    suspend fun getArtistTopTracks(artistId: String): List<FfiTrack> = withContext(Dispatchers.IO) {
        try { core.getDeezerArtistTopTracks(artistId) } catch (e: Exception) { emptyList() }
    }

    suspend fun getArtistAlbums(artistId: String): List<FfiAlbum> = withContext(Dispatchers.IO) {
        try { core.getDeezerArtistAlbums(artistId) } catch (e: Exception) { emptyList() }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<FfiTrack> =
        getPlaylistTrackEntries(playlistId).map { it.track }

    suspend fun getPlaylistTrackEntries(playlistId: String): List<PlaylistTrackEntry> = withContext(Dispatchers.IO) {
        if (playlistId.startsWith("deezer:") && deezerSession.hasSavedSession) {
            deezerSession.playlistTrackEntries(playlistId)
        } else {
            val tracks = if (playlistId == LOCAL_FAVORITES_ID) core.getFavorites() else core.getPlaylistTracks(playlistId)
            tracks.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
        }
    }

    // Long-press sheet triggers
    fun openTrackMenu(track: FfiTrack) {
        _selectedTrackForMenu.value = track
    }

    fun closeTrackMenu() {
        _selectedTrackForMenu.value = null
    }

    fun toggleFavorite(track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isFav = core.toggleFavorite(track)
                refreshFavorites()
                _statusMessage.value = if (isFav) "Added to favorites" else "Removed from favorites"
            } catch (e: Exception) {
                _statusMessage.value = "Error: ${e.message}"
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                core.addTracksToPlaylist(playlistId, listOf(track))
                refreshPlaylists()
                _statusMessage.value = "Added to playlist"
            } catch (e: Exception) {
                _statusMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun executeModAction(modId: String, track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resultMsg = core.executeTrackModAction(modId, track.id)
                _statusMessage.value = resultMsg
            } catch (e: Exception) {
                _statusMessage.value = "Mod Error: ${e.message}"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun scanDeviceMusic() {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            val inputs = mutableListOf<FfiLocalTrackInput>()

            try {
                resolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val title = cursor.getString(titleCol)
                        val artist = cursor.getString(artistCol)
                        val album = cursor.getString(albumCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val duration = cursor.getLong(durationCol)
                        val displayName = cursor.getString(nameCol)

                        inputs.add(
                            FfiLocalTrackInput(
                                uri = contentUri,
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = duration.toULong(),
                                artworkUri = if (albumId > 0) ContentUris.withAppendedId(
                                    android.net.Uri.parse("content://media/external/audio/albumart"), albumId
                                ).toString() else null,
                                fileName = displayName
                            )
                        )
                    }
                }

                val count = core.ingestLocalTracks(inputs)
                refreshLocalTracks()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Scanned $count tracks"
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error scanning media", e)
            }
        }
    }
}
