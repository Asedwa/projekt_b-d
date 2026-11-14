package org.kde.plasmawave.ui

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.player.PlaybackManager
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.DeezerWebCookies
import org.kde.plasmawave.deezer.DeezerException
import org.kde.plasmawave.deezer.StreamingQuality
import org.kde.plasmawave.download.Downloads
import org.kde.plasmawave.download.DownloadQuality
import org.kde.plasmawave.download.DownloadStorage
import org.kde.plasmawave.download.SavedDownload
import org.kde.plasmawave.ui.components.SeekbarStyle
import org.kde.plasmawave.ui.components.AlbumCoverFrame
import org.kde.plasmawave.ui.components.HeaderLogoStyle
import org.kde.plasmawave.ui.components.HomeFeedType
import org.kde.plasmawave.ui.components.AppLauncherIcon
import org.kde.plasmawave.ui.components.LauncherIconManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
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
const val DEEZER_FAVORITES_ID = "deezer:loved_tracks"
const val LOCAL_DOWNLOADS_ID = "local:downloaded_tracks"

enum class SearchCategory {
    TRACKS, ALBUMS, ARTISTS, PLAYLISTS
}

data class TrackMenuContext(val playlist: FfiPlaylist, val originalIndex: Int)

data class CustomThemeSpec(
    val id: String,
    val name: String,
    val backgroundHex: String,
    val cardHex: String,
    val accentHex: String,
    val textHex: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val core = PlasmaWaveApp.core
    private val displayPrefs = application.getSharedPreferences("display_preferences", android.content.Context.MODE_PRIVATE)
    private val _highRefreshRate = MutableStateFlow(displayPrefs.getBoolean("high_refresh_rate", true))
    val highRefreshRate = _highRefreshRate.asStateFlow()
    fun setHighRefreshRate(enabled: Boolean) {
        displayPrefs.edit().putBoolean("high_refresh_rate", enabled).apply()
        _highRefreshRate.value = enabled
    }

    private val _dynamicColorEnabled = MutableStateFlow(displayPrefs.getBoolean("dynamic_color_enabled", true))
    val dynamicColorEnabled = _dynamicColorEnabled.asStateFlow()
    fun setDynamicColorEnabled(enabled: Boolean) {
        displayPrefs.edit().putBoolean("dynamic_color_enabled", enabled).apply()
        _dynamicColorEnabled.value = enabled
    }

    private val _seekbarStyle = MutableStateFlow(
        runCatching {
            SeekbarStyle.valueOf(displayPrefs.getString("seekbar_style", null) ?: "CLASSIC")
        }.getOrDefault(SeekbarStyle.CLASSIC)
    )
    val seekbarStyle = _seekbarStyle.asStateFlow()
    fun setSeekbarStyle(style: SeekbarStyle) {
        displayPrefs.edit().putString("seekbar_style", style.name).apply()
        _seekbarStyle.value = style
    }

    private val _albumFrame = MutableStateFlow(
        runCatching {
            AlbumCoverFrame.valueOf(displayPrefs.getString("album_frame", null) ?: "MODERN_ROUNDED")
        }.getOrDefault(AlbumCoverFrame.MODERN_ROUNDED)
    )
    val albumFrame = _albumFrame.asStateFlow()
    fun setAlbumFrame(frame: AlbumCoverFrame) {
        displayPrefs.edit().putString("album_frame", frame.name).apply()
        _albumFrame.value = frame
    }

    private val _swipeToSkipEnabled = MutableStateFlow(
        displayPrefs.getBoolean("swipe_to_skip", true)
    )
    val swipeToSkipEnabled = _swipeToSkipEnabled.asStateFlow()
    fun setSwipeToSkipEnabled(enabled: Boolean) {
        displayPrefs.edit().putBoolean("swipe_to_skip", enabled).apply()
        _swipeToSkipEnabled.value = enabled
    }

    private val _flowHeroCardEnabled = MutableStateFlow(displayPrefs.getBoolean("flow_hero_card_enabled", true))
    val flowHeroCardEnabled = _flowHeroCardEnabled.asStateFlow()
    fun setFlowHeroCardEnabled(enabled: Boolean) {
        displayPrefs.edit().putBoolean("flow_hero_card_enabled", enabled).apply()
        _flowHeroCardEnabled.value = enabled
    }

    private val _flowQuickAccessPinned = MutableStateFlow(displayPrefs.getBoolean("flow_quick_access_pinned", true))
    val flowQuickAccessPinned = _flowQuickAccessPinned.asStateFlow()
    fun setFlowQuickAccessPinned(enabled: Boolean) {
        displayPrefs.edit().putBoolean("flow_quick_access_pinned", enabled).apply()
        _flowQuickAccessPinned.value = enabled
    }

    private val _flowLibraryPinned = MutableStateFlow(displayPrefs.getBoolean("flow_library_pinned", true))
    val flowLibraryPinned = _flowLibraryPinned.asStateFlow()
    fun setFlowLibraryPinned(enabled: Boolean) {
        displayPrefs.edit().putBoolean("flow_library_pinned", enabled).apply()
        _flowLibraryPinned.value = enabled
    }

    private val _downloadsQuickAccessPinned = MutableStateFlow(displayPrefs.getBoolean("downloads_quick_access_pinned", true))
    val downloadsQuickAccessPinned = _downloadsQuickAccessPinned.asStateFlow()
    fun setDownloadsQuickAccessPinned(enabled: Boolean) {
        displayPrefs.edit().putBoolean("downloads_quick_access_pinned", enabled).apply()
        _downloadsQuickAccessPinned.value = enabled
    }

    private val _downloadsLibraryPinned = MutableStateFlow(displayPrefs.getBoolean("downloads_library_pinned", true))
    val downloadsLibraryPinned = _downloadsLibraryPinned.asStateFlow()
    fun setDownloadsLibraryPinned(enabled: Boolean) {
        displayPrefs.edit().putBoolean("downloads_library_pinned", enabled).apply()
        _downloadsLibraryPinned.value = enabled
    }

    private val _headerLogoStyle = MutableStateFlow(
        runCatching {
            HeaderLogoStyle.valueOf(displayPrefs.getString("header_logo_style", null) ?: "ORIGINAL")
        }.getOrDefault(HeaderLogoStyle.ORIGINAL)
    )
    val headerLogoStyle = _headerLogoStyle.asStateFlow()
    fun setHeaderLogoStyle(style: HeaderLogoStyle) {
        displayPrefs.edit().putString("header_logo_style", style.name).apply()
        _headerLogoStyle.value = style
    }

    private val _customHeaderLogoTimestamp = MutableStateFlow(System.currentTimeMillis())
    val customHeaderLogoTimestamp = _customHeaderLogoTimestamp.asStateFlow()
    fun refreshCustomHeaderLogo() {
        _customHeaderLogoTimestamp.value = System.currentTimeMillis()
    }
    fun updateCustomHeaderLogo() {
        refreshCustomHeaderLogo()
    }

    private val _customLauncherIconTimestamp = MutableStateFlow(System.currentTimeMillis())
    val customLauncherIconTimestamp = _customLauncherIconTimestamp.asStateFlow()
    fun updateCustomLauncherIcon() {
        _customLauncherIconTimestamp.value = System.currentTimeMillis()
        LauncherIconManager.setLauncherIcon(getApplication(), AppLauncherIcon.CUSTOM, requestPin = true)
        _launcherIcon.value = AppLauncherIcon.CUSTOM
    }

    private val _homeFeedType = MutableStateFlow(
        runCatching {
            HomeFeedType.valueOf(displayPrefs.getString("home_feed_type", null) ?: "FOR_YOU")
        }.getOrDefault(HomeFeedType.FOR_YOU)
    )
    val homeFeedType = _homeFeedType.asStateFlow()
    fun setHomeFeedType(feed: HomeFeedType) {
        displayPrefs.edit().putString("home_feed_type", feed.name).apply()
        _homeFeedType.value = feed
    }

    private val _launcherIcon = MutableStateFlow(
        LauncherIconManager.getSelectedIcon(application).also {
            LauncherIconManager.syncLauncherIcon(application)
        }
    )
    val launcherIcon = _launcherIcon.asStateFlow()
    fun setLauncherIcon(icon: AppLauncherIcon) {
        LauncherIconManager.setLauncherIcon(getApplication(), icon, requestPin = (icon == AppLauncherIcon.CUSTOM))
        _launcherIcon.value = icon
    }

    private val customThemesPrefs = application.getSharedPreferences("user_custom_themes", android.content.Context.MODE_PRIVATE)
    private val _userCustomThemes = MutableStateFlow<List<CustomThemeSpec>>(readUserCustomThemes())
    val userCustomThemes: StateFlow<List<CustomThemeSpec>> = _userCustomThemes.asStateFlow()

    private fun readUserCustomThemes(): List<CustomThemeSpec> = try {
        val raw = customThemesPrefs.getString("themes", "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val obj = arr.getJSONObject(i)
                CustomThemeSpec(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    backgroundHex = obj.optString("backgroundHex", "#141224"),
                    cardHex = obj.optString("cardHex", "#282348"),
                    accentHex = obj.optString("accentHex", "#ED438D"),
                    textHex = obj.optString("textHex", "#F5EEF8")
                )
            }.getOrNull()
        }
    } catch (_: Exception) { emptyList() }

    private fun persistUserCustomThemes(list: List<CustomThemeSpec>) {
        val arr = JSONArray()
        list.forEach { spec ->
            arr.put(
                JSONObject()
                    .put("id", spec.id)
                    .put("name", spec.name)
                    .put("backgroundHex", spec.backgroundHex)
                    .put("cardHex", spec.cardHex)
                    .put("accentHex", spec.accentHex)
                    .put("textHex", spec.textHex)
            )
        }
        customThemesPrefs.edit().putString("themes", arr.toString()).apply()
        _userCustomThemes.value = list
    }

    private val historyPrefs = application.getSharedPreferences("playback_history_v2", android.content.Context.MODE_PRIVATE)
    private val _recentTracks = MutableStateFlow<List<FfiTrack>>(readRecentTracks())
    val recentTracks: StateFlow<List<FfiTrack>> = _recentTracks.asStateFlow()

    private val _mostPlayedTracks = MutableStateFlow<List<FfiTrack>>(readMostPlayedTracks())
    val mostPlayedTracks: StateFlow<List<FfiTrack>> = _mostPlayedTracks.asStateFlow()

    private fun readHistoryEntries(): List<Pair<FfiTrack, Int>> = try {
        val raw = historyPrefs.getString("entries", "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                val track = jsonTrack(obj.getJSONObject("track"))
                val count = obj.optInt("count", 1)
                Pair(track, count)
            }.getOrNull()
        }
    } catch (_: Exception) { emptyList() }

    private fun readRecentTracks(): List<FfiTrack> = readHistoryEntries().map { it.first }
    private fun readMostPlayedTracks(): List<FfiTrack> = readHistoryEntries().sortedByDescending { it.second }.map { it.first }

    fun recordTrackPlay(track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = readHistoryEntries().toMutableList()
            val existingIdx = list.indexOfFirst { it.first.id == track.id }
            val count = if (existingIdx >= 0) list[existingIdx].second + 1 else 1
            if (existingIdx >= 0) list.removeAt(existingIdx)
            list.add(0, Pair(track, count))
            val trimmed = list.take(100)
            val jsonArray = JSONArray()
            trimmed.forEach { (t, c) ->
                jsonArray.put(JSONObject().put("count", c).put("track", trackJson(t)))
            }
            historyPrefs.edit().putString("entries", jsonArray.toString()).apply()
            _recentTracks.value = trimmed.map { it.first }
            _mostPlayedTracks.value = trimmed.sortedByDescending { it.second }.map { it.first }
        }
    }

    private val _externalSearchTrigger = MutableStateFlow<Pair<String, SearchCategory>?>(null)
    val externalSearchTrigger = _externalSearchTrigger.asStateFlow()
    fun navigateToSearch(query: String, category: SearchCategory) {
        _searchCategory.value = category
        _externalSearchTrigger.value = Pair(query, category)
        search(query, category, immediate = true)
    }
    fun clearExternalSearchTrigger() {
        _externalSearchTrigger.value = null
    }
    val playbackManager = PlaybackManager.getInstance(application)
    private val deezerSession = DeezerSession.get(application)
    val wifiQuality = deezerSession.wifiQuality
    val mobileQuality = deezerSession.mobileQuality
    fun setWifiQuality(quality: StreamingQuality) = deezerSession.setWifiQuality(quality)
    fun setMobileQuality(quality: StreamingQuality) = deezerSession.setMobileQuality(quality)
    private val pinnedItems = PinnedItems(application)
    private val fullDownloadManager = Downloads.get(application)
    val fullDownloads = fullDownloadManager.items
    val downloadQuality = fullDownloadManager.quality
    val mobileDownloadsAllowed = fullDownloadManager.mobileAllowed
    fun setDownloadQuality(quality: DownloadQuality) = fullDownloadManager.setQuality(quality)
    fun setMobileDownloadsAllowed(allowed: Boolean) = fullDownloadManager.setMobileAllowed(allowed)
    fun cancelFullDownload(id: String) { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.cancel(id) } }
    fun cancelAllDownloads() { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.cancelAll() } }
    fun forgetFullDownload(id: String) { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.forget(id) } }
    val pins = pinnedItems.items
    fun togglePinnedTrack(track: FfiTrack) = pinnedItems.toggle(PinnedItem.Track(track))
    fun togglePinnedPlaylist(playlist: FfiPlaylist) = pinnedItems.toggle(PinnedItem.Playlist(playlist))
    private val deezerGeneration = AtomicLong()
    private val _deezerBusy = MutableStateFlow(false)
    val deezerBusy: StateFlow<Boolean> = _deezerBusy.asStateFlow()
    private val _deezerLoginError = MutableStateFlow<String?>(null)
    val deezerLoginError: StateFlow<String?> = _deezerLoginError.asStateFlow()

    private val _activeTheme = MutableStateFlow(core.getCurrentTheme())
    val activeTheme: StateFlow<FfiTheme> = _activeTheme.asStateFlow()

    private val _availableThemes = MutableStateFlow<List<FfiTheme>>(emptyList())
    val availableThemes: StateFlow<List<FfiTheme>> = _availableThemes.asStateFlow()

    private val _installedMods = MutableStateFlow<List<FfiModInfo>>(emptyList())
    val installedMods: StateFlow<List<FfiModInfo>> = _installedMods.asStateFlow()

    private val _customTrackActions = MutableStateFlow<List<FfiModInfo>>(emptyList())
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
    private var deezerFavoriteEntries: List<PlaylistTrackEntry> = emptyList()

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

    private val _downloadedTracks = MutableStateFlow<List<FfiTrack>>(emptyList())
    val downloadedTracks: StateFlow<List<FfiTrack>> = _downloadedTracks.asStateFlow()

    private val _settings = MutableStateFlow(core.getSettings())
    val settings: StateFlow<FfiSettings> = _settings.asStateFlow()

    fun updateSettings(settings: FfiSettings) {
        try { _settings.value = core.updateSettings(settings) }
        catch (_: Exception) { _statusMessage.value = "Ayar kaydedilemedi" }
    }

    // Contextual Long-press state
    private val _selectedTrackForMenu = MutableStateFlow<FfiTrack?>(null)
    val selectedTrackForMenu: StateFlow<FfiTrack?> = _selectedTrackForMenu.asStateFlow()
    private val _trackMenuContext = MutableStateFlow<TrackMenuContext?>(null)
    val trackMenuContext: StateFlow<TrackMenuContext?> = _trackMenuContext.asStateFlow()
    private val _playlistRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val playlistRevisions = _playlistRevisions.asStateFlow()
    private fun invalidatePlaylist(id: String) {
        localCoverCache.remove(id)
        _playlistRevisions.update { it + (id to ((it[id] ?: 0L) + 1L)) }
    }
    private val favoriteMutex = Mutex()
    private val localCoverCache = ConcurrentHashMap<String, String>()
    private var searchJob: Job? = null
    private val _mixLoading = MutableStateFlow(false)
    val mixLoading: StateFlow<Boolean> = _mixLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { core.clearDeezerUser() } catch (_: Exception) { }
            try { fullDownloadManager.recover() } catch (_: Exception) { }
            if (!deezerSession.hasSavedSession) {
                try {
                    DeezerWebCookies.readArl()?.let { connectDeezerArl(it) }
                } catch (_: Exception) { }
            }
        }
        viewModelScope.launch {
            playbackManager.currentTrackId.collect { id ->
                if (id != null) {
                    playbackManager.uiState.value.currentEntry?.track?.let { recordTrackPlay(it) }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            fullDownloadManager.items.collect {
                refreshDownloadedTracks()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _deezerFavorites.collect {
                refreshDownloadedTracks()
            }
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
        registerBuiltInThemes()
        _activeTheme.value = core.getCurrentTheme()
        _availableThemes.value = core.listThemes()
    }

    private fun registerBuiltInThemes() {
        val themesJson = listOf(
            """{
  "schemaVersion": 1,
  "id": "mlp.twilight-sparkle",
  "name": "Twilight",
  "author": "projekt_b-d",
  "description": "Twilight lavanta, gece mavisi ve macenta yıldız teması.",
  "dark": true,
  "colors": {
    "background": "#141224",
    "surface": "#1C1932",
    "surfaceVariant": "#262244",
    "card": "#282348",
    "cardPressed": "#352F5E",
    "accent": "#ED438D",
    "accentMuted": "#5B2446",
    "onAccent": "#FCFCFC",
    "text": "#F5EEF8",
    "textSecondary": "#B5A4CB",
    "textDisabled": "#756885",
    "border": "#3E3566",
    "divider": "#2A2346",
    "positive": "#27AE60",
    "warning": "#F67400",
    "negative": "#DA4453",
    "scrim": "#B3000000"
  },
  "shapes": { "smallRadius": 6, "mediumRadius": 10, "largeRadius": 18 },
  "spacing": { "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24 },
  "typography": { "fontFamily": "system", "scale": 1.0 },
  "elevation": { "low": 1, "medium": 4, "high": 12 },
  "motion": { "shortMs": 120, "mediumMs": 220, "longMs": 360 }
}""",
            """{
  "schemaVersion": 1,
  "id": "mlp.pinkie-pie",
  "name": "Pinkie",
  "author": "projekt_b-d",
  "description": "Pinkie neşeli pembe, pamuk şeker ve gökyüzü mavisi teması.",
  "dark": true,
  "colors": {
    "background": "#1E0F18",
    "surface": "#2B1422",
    "surfaceVariant": "#3A1B2F",
    "card": "#3E1C32",
    "cardPressed": "#4F2340",
    "accent": "#FF2E93",
    "accentMuted": "#68133C",
    "onAccent": "#FCFCFC",
    "text": "#FFF0F5",
    "textSecondary": "#E8A2C0",
    "textDisabled": "#8C5B73",
    "border": "#552645",
    "divider": "#3B192F",
    "positive": "#27AE60",
    "warning": "#F67400",
    "negative": "#DA4453",
    "scrim": "#B3000000"
  },
  "shapes": { "smallRadius": 6, "mediumRadius": 10, "largeRadius": 18 },
  "spacing": { "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24 },
  "typography": { "fontFamily": "system", "scale": 1.0 },
  "elevation": { "low": 1, "medium": 4, "high": 12 },
  "motion": { "shortMs": 120, "mediumMs": 220, "longMs": 360 }
}"""
        )
        for (json in themesJson) {
            runCatching { core.importThemeJson(json) }
        }
        val customList = readUserCustomThemes()
        _userCustomThemes.value = customList
        for (spec in customList) {
            runCatching { core.importThemeJson(buildCustomThemeManifestJson(spec)) }
        }
    }

    private fun blendHexColors(hex1: String, hex2: String, ratio: Float): String {
        return try {
            val c1 = android.graphics.Color.parseColor(hex1)
            val c2 = android.graphics.Color.parseColor(hex2)
            val r = (android.graphics.Color.red(c1) * (1f - ratio) + android.graphics.Color.red(c2) * ratio).toInt().coerceIn(0, 255)
            val g = (android.graphics.Color.green(c1) * (1f - ratio) + android.graphics.Color.green(c2) * ratio).toInt().coerceIn(0, 255)
            val b = (android.graphics.Color.blue(c1) * (1f - ratio) + android.graphics.Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
            String.format("#%02X%02X%02X", r, g, b)
        } catch (_: Exception) {
            hex1
        }
    }

    private fun buildCustomThemeManifestJson(spec: CustomThemeSpec): String {
        val bg = spec.backgroundHex
        val card = spec.cardHex
        val accent = spec.accentHex
        val text = spec.textHex
        val surface = blendHexColors(bg, card, 0.5f)
        val surfaceVariant = blendHexColors(bg, card, 0.85f)
        val cardPressed = blendHexColors(card, accent, 0.22f)
        val accentMuted = blendHexColors(bg, accent, 0.35f)
        val textSecondary = blendHexColors(text, bg, 0.32f)
        val textDisabled = blendHexColors(text, bg, 0.55f)
        val border = blendHexColors(card, accent, 0.28f)
        val divider = blendHexColors(bg, card, 0.75f)

        val colorsObj = JSONObject()
            .put("background", bg)
            .put("surface", surface)
            .put("surfaceVariant", surfaceVariant)
            .put("card", card)
            .put("cardPressed", cardPressed)
            .put("accent", accent)
            .put("accentMuted", accentMuted)
            .put("onAccent", "#FCFCFC")
            .put("text", text)
            .put("textSecondary", textSecondary)
            .put("textDisabled", textDisabled)
            .put("border", border)
            .put("divider", divider)
            .put("positive", "#27AE60")
            .put("warning", "#F67400")
            .put("negative", "#DA4453")
            .put("scrim", "#B3000000")

        return JSONObject()
            .put("schemaVersion", 1)
            .put("id", spec.id)
            .put("name", spec.name)
            .put("author", "Custom")
            .put("description", "${spec.name} özel kullanıcı teması")
            .put("dark", true)
            .put("colors", colorsObj)
            .put("shapes", JSONObject().put("smallRadius", 6).put("mediumRadius", 10).put("largeRadius", 18))
            .put("spacing", JSONObject().put("xs", 4).put("sm", 8).put("md", 12).put("lg", 16).put("xl", 24))
            .put("typography", JSONObject().put("fontFamily", "system").put("scale", 1.0))
            .put("elevation", JSONObject().put("low", 1).put("medium", 4).put("high", 12))
            .put("motion", JSONObject().put("shortMs", 120).put("mediumMs", 220).put("longMs", 360))
            .toString()
    }

    fun saveUserCustomTheme(spec: CustomThemeSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = readUserCustomThemes().toMutableList()
                val idx = current.indexOfFirst { it.id == spec.id }
                if (idx >= 0) current[idx] = spec else current.add(spec)
                persistUserCustomThemes(current)
                val manifestJson = buildCustomThemeManifestJson(spec)
                core.importThemeJson(manifestJson)
                try { core.setCustomAccentColor(null) } catch (_: Exception) { }
                val applied = core.setTheme(spec.id)
                _activeTheme.value = applied
                _settings.value = core.getSettings()
                _availableThemes.value = core.listThemes()
                _statusMessage.value = "'${spec.name}' teması kaydedildi ve uygulandı!"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to save custom theme", e)
                _statusMessage.value = "Tema kaydedilemedi: ${e.message}"
            }
        }
    }

    fun deleteUserCustomTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = readUserCustomThemes().filterNot { it.id == themeId }
                persistUserCustomThemes(current)
                if (_activeTheme.value.id == themeId) {
                    try { core.setCustomAccentColor(null) } catch (_: Exception) { }
                    val fallback = core.setTheme("mlp.twilight-sparkle")
                    _activeTheme.value = fallback
                    _settings.value = core.getSettings()
                }
                _statusMessage.value = "Özel tema silindi"
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete custom theme", e)
            }
        }
    }

    fun setTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try { core.setCustomAccentColor(null) } catch (_: Exception) { }
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

    fun createPlaylist(name: String, onDeezer: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (onDeezer) {
                    deezerSession.createPlaylist(name)
                    try { _deezerPlaylists.value = deezerSession.playlists() } catch (_: Exception) { }
                } else { core.createPlaylist(name); refreshPlaylists() }
                _statusMessage.value = "Çalma listesi oluşturuldu"
            } catch (_: Exception) {
                _statusMessage.value = "Liste oluşturulamadı; bağlantıyı ve liste adını kontrol et"
            }
        }
    }

    fun deletePlaylist(playlist: FfiPlaylist, onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (playlist.id.startsWith("deezer:")) {
                    deezerSession.deletePlaylist(playlist.id)
                    try { _deezerPlaylists.value = deezerSession.playlists() } catch (_: Exception) { }
                } else { core.deletePlaylist(playlist.id); refreshPlaylists() }
                pinnedItems.remove("playlist:${playlist.id}")
                localCoverCache.remove(playlist.id)
                _statusMessage.value = "Çalma listesi silindi"
                withContext(Dispatchers.Main) { onDeleted() }
            } catch (_: Exception) { _statusMessage.value = "Liste silinemedi; düzenleme iznini kontrol et" }
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
        refreshDownloadedTracks()
    }

    fun refreshDownloadedTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hints = buildMap<String, FfiTrack> {
                    _recentTracks.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    _favorites.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    _deezerFavorites.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    fullDownloadManager.items.value.forEach { put(DownloadStorage.normalizeTrackId(it.track.id), it.track) }
                }
                val loaded = DownloadStorage.loadAllDownloadedTracks(
                    getApplication(),
                    fullDownloadManager.items.value,
                    hints
                )
                _downloadedTracks.value = loaded
                invalidatePlaylist(LOCAL_DOWNLOADS_ID)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error refreshing downloaded tracks", e)
            }
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
        deezerFavoriteEntries = emptyList()
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
            val entries = deezerSession.favoriteTrackEntries()
            if (generation == deezerGeneration.get()) {
                deezerFavoriteEntries = entries
                _deezerFavorites.value = entries.map { it.track }
            }
        } catch (_: Exception) {
            if (generation == deezerGeneration.get()) _statusMessage.value = "Deezer favorileri yüklenemedi"
        }
    }

    fun setCategory(category: SearchCategory) {
        _searchCategory.value = category
    }

    fun search(query: String, category: SearchCategory = _searchCategory.value, immediate: Boolean = false) {
        _searchCategory.value = category
        searchJob?.cancel()
        val q = query.trim()
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            _searchAlbums.value = emptyList()
            _searchArtists.value = emptyList()
            _searchPlaylists.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            if (!immediate) delay(250)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Search error", e)
            }
        }
    }

    suspend fun getCachedLocalPlaylistArtwork(playlistId: String): String? {
        localCoverCache[playlistId]?.let { return it }
        val url = withContext(Dispatchers.IO) {
            runCatching {
                core.getPlaylistTracks(playlistId).firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            }.getOrNull()
        }
        if (url != null) localCoverCache[playlistId] = url
        return url
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

    fun downloadFullTracks(tracks: List<FfiTrack>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = fullDownloadManager.enqueueAll(tracks, downloadQuality.value)
                _statusMessage.value = if (result.added > 0) "${result.added} şarkı indirme sırasına eklendi" +
                    if (result.skipped > 0) " • ${result.skipped} zaten var veya cihazda mevcut" else ""
                    else if (result.skipped > 0) "Seçilen şarkıların tümü (${result.skipped}) zaten indirildi veya cihazda mevcut"
                    else "İndirilecek şarkı bulunamadı"
            } catch (_: Exception) { _statusMessage.value = "İndirme başlatılamadı" }
        }
    }

    fun playFullDownload(task: SavedDownload) {
        val uri = task.uri ?: return
        val track = task.track.copy(id = "local:download-${task.id}", provider = "local", playbackUri = uri, isPreview = false)
        playbackManager.playTrackList(listOf(track))
    }

    fun retryFullDownload(task: SavedDownload) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (fullDownloadManager.enqueueAll(listOf(task.track), task.quality).added > 0) {
                    fullDownloadManager.forget(task.id)
                    _statusMessage.value = "İndirme yeniden sıraya eklendi"
                } else _statusMessage.value = "Bu şarkı zaten indiriliyor veya indirildi"
            } catch (_: Exception) { _statusMessage.value = "İndirme başlatılamadı" }
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
        if (playlistId == DEEZER_FAVORITES_ID) {
            if (deezerFavoriteEntries.isNotEmpty()) deezerFavoriteEntries else deezerSession.favoriteTrackEntries()
        } else if (playlistId == LOCAL_DOWNLOADS_ID) {
            val current = _downloadedTracks.value.ifEmpty {
                val hints = buildMap<String, FfiTrack> {
                    _recentTracks.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    _favorites.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    _deezerFavorites.value.forEach { put(DownloadStorage.normalizeTrackId(it.id), it) }
                    fullDownloadManager.items.value.forEach { put(DownloadStorage.normalizeTrackId(it.track.id), it.track) }
                }
                DownloadStorage.loadAllDownloadedTracks(
                    getApplication(),
                    fullDownloadManager.items.value,
                    hints
                ).also { _downloadedTracks.value = it }
            }
            current.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
        } else if (playlistId.startsWith("deezer:") && deezerSession.hasSavedSession) {
            deezerSession.playlistTrackEntries(playlistId)
        } else {
            val tracks = if (playlistId == LOCAL_FAVORITES_ID) core.getFavorites() else core.getPlaylistTracks(playlistId)
            tracks.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
        }
    }

    // Long-press sheet triggers
    fun openTrackMenu(track: FfiTrack, playlist: FfiPlaylist? = null, originalIndex: Int = 0) {
        _trackMenuContext.value = playlist?.let { TrackMenuContext(it, originalIndex) }
        _selectedTrackForMenu.value = track
    }

    fun closeTrackMenu() {
        _selectedTrackForMenu.value = null
        _trackMenuContext.value = null
    }

    fun isTrackFavorite(track: FfiTrack): Boolean =
        if (track.provider == "deezer" && deezerSession.hasSavedSession)
            _deezerFavorites.value.any { it.id == track.id }
        else _favorites.value.any { it.id == track.id }

    fun toggleFavorite(track: FfiTrack) {
        if (track.provider == "deezer" && deezerSession.hasSavedSession && _deezerLibraryLoading.value) {
            _statusMessage.value = "Deezer beğenilerin yükleniyor; birazdan tekrar dene"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            favoriteMutex.withLock {
                try {
                    val isFav: Boolean
                    if (track.provider == "deezer" && deezerSession.hasSavedSession) {
                        isFav = !isTrackFavorite(track)
                        deezerSession.setFavorite(track.id, isFav)
                        val entries = if (isFav) listOf(PlaylistTrackEntry(track, Instant.now().epochSecond, 0)) + deezerFavoriteEntries
                            else deezerFavoriteEntries.filterNot { it.track.id == track.id }
                        deezerFavoriteEntries = entries.mapIndexed { index, entry -> entry.copy(originalIndex = index) }
                        _deezerFavorites.value = deezerFavoriteEntries.map { it.track }
                    } else {
                        isFav = core.toggleFavorite(track)
                        refreshFavorites()
                    }
                    invalidatePlaylist(if (track.provider == "deezer" && deezerSession.hasSavedSession) DEEZER_FAVORITES_ID else LOCAL_FAVORITES_ID)
                    _statusMessage.value = if (isFav) "Beğenilen şarkılara eklendi" else "Beğenilen şarkılardan çıkarıldı"
                } catch (_: Exception) {
                    _statusMessage.value = "Beğeni güncellenemedi; bağlantını ve hesabını kontrol et"
                }
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (playlistId.startsWith("deezer:")) {
                    if (track.provider != "deezer") throw DeezerException("Deezer listelerine yalnızca Deezer şarkıları eklenebilir")
                    deezerSession.editPlaylist(playlistId, track.id, add = true)
                } else {
                    core.addTracksToPlaylist(playlistId, listOf(track))
                    refreshPlaylists()
                }
                invalidatePlaylist(playlistId)
                _statusMessage.value = "Şarkı listeye eklendi"
                if (playlistId.startsWith("deezer:")) try { _deezerPlaylists.value = deezerSession.playlists() } catch (_: Exception) { }
            } catch (e: Exception) {
                _statusMessage.value = (e as? DeezerException)?.message ?: "Şarkı eklenemedi; liste düzenleme iznini ve bağlantını kontrol et"
            }
        }
    }

    fun removeTrackFromPlaylist(track: FfiTrack, menu: TrackMenuContext) {
        if (menu.playlist.id == DEEZER_FAVORITES_ID || menu.playlist.id == LOCAL_FAVORITES_ID) {
            if (isTrackFavorite(track)) toggleFavorite(track)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (menu.playlist.id.startsWith("deezer:")) {
                    deezerSession.editPlaylist(menu.playlist.id, track.id, add = false)
                } else {
                    val current = core.getPlaylistTracks(menu.playlist.id).getOrNull(menu.originalIndex)
                    if (current?.id != track.id) throw IllegalStateException("Liste değişti; tekrar dene")
                    core.removeTrackFromPlaylist(menu.playlist.id, menu.originalIndex.toUInt())
                    refreshPlaylists()
                }
                invalidatePlaylist(menu.playlist.id)
                _statusMessage.value = "Şarkı listeden çıkarıldı"
                if (menu.playlist.id.startsWith("deezer:")) try { _deezerPlaylists.value = deezerSession.playlists() } catch (_: Exception) { }
            } catch (_: Exception) {
                _statusMessage.value = "Şarkı çıkarılamadı; liste düzenleme iznini ve bağlantını kontrol et"
            }
        }
    }

    fun startTrackMix(track: FfiTrack) {
        if (_mixLoading.value) return
        if (track.provider != "deezer" || !deezerSession.hasSavedSession) {
            _statusMessage.value = "Şarkı miksi için Deezer hesabı ve Deezer şarkısı gerekli"
            return
        }
        _mixLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mix = (listOf(track) + deezerSession.trackMix(track.id)).distinctBy { it.id }
                if (mix.size < 2) throw DeezerException("Bu şarkı için miks bulunamadı")
                withContext(Dispatchers.Main) { playbackManager.playTrackList(mix) }
                _statusMessage.value = "Şarkı miksi çalıyor"
            } catch (_: Exception) {
                _statusMessage.value = "Şarkı miksi alınamadı"
            } finally { _mixLoading.value = false }
        }
    }

    private val _flowLoading = MutableStateFlow(false)
    val flowLoading: StateFlow<Boolean> = _flowLoading.asStateFlow()

    fun startFlow() {
        if (_flowLoading.value) return
        if (!deezerSession.hasSavedSession) {
            _statusMessage.value = "Deezer Flow için Deezer'a giriş yapın"
            return
        }
        _flowLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Deezer Flow yükleniyor..."
                }
                val tracks = deezerSession.flow()
                if (tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playbackManager.playTrackList(tracks, 0)
                        _statusMessage.value = "Deezer Flow başlatıldı (${tracks.size} şarkı)"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "Deezer Flow için henüz yeterli şarkı verisi yok"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Flow başlatılamadı: ${(e as? DeezerException)?.message ?: e.message}"
                }
            } finally {
                _flowLoading.value = false
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
