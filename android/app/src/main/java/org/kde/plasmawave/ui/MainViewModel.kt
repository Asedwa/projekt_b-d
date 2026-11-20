package org.kde.plasmawave.ui

import android.app.Application
import android.content.ContentUris
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.jam.JamManager
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
import org.kde.plasmawave.ui.components.HomeWidgetType
import org.kde.plasmawave.ui.components.AppLauncherIcon
import org.kde.plasmawave.ui.components.DownloadStatusSnapshot
import org.kde.plasmawave.ui.components.LauncherIconManager
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import uniffi.plasmawave_core.FfiAlbum
import uniffi.plasmawave_core.FfiArtist
import uniffi.plasmawave_core.FfiCustomThemeSpec
import uniffi.plasmawave_core.FfiDeezerUser
import uniffi.plasmawave_core.FfiDownloadTask
import uniffi.plasmawave_core.FfiHomeWidgetCatalogEntry
import uniffi.plasmawave_core.FfiHomeWidgetItem
import uniffi.plasmawave_core.FfiHomeWidgetPatch
import uniffi.plasmawave_core.FfiHomeWidgetSize
import uniffi.plasmawave_core.FfiHomeWidgetStyle
import uniffi.plasmawave_core.FfiImportBackupReport
import uniffi.plasmawave_core.FfiListeningAnalytics
import uniffi.plasmawave_core.FfiLocalTrackInput
import uniffi.plasmawave_core.FfiModInfo
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiPrivateSessionStatus
import uniffi.plasmawave_core.FfiQuickArtistItem
import uniffi.plasmawave_core.FfiResumePlaybackState
import uniffi.plasmawave_core.FfiSettings
import uniffi.plasmawave_core.FfiTheme
import uniffi.plasmawave_core.FfiTrack
import uniffi.plasmawave_core.FfiTrackListeningStat

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

enum class QuickCardStyle(val title: String, val subtitle: String) {
    THEME_ADAPTIVE("Temaya Uyumlu", "Aktif temanın kart ve vurgu renklerini kullanır"),
    VIBRANT_GRADIENT("Renkli Gradyan", "Canlı renk geçişli klasik ikon kartları")
}

enum class QuickCardLayout(val title: String, val subtitle: String) {
    COMPACT_HORIZONTAL("Kompakt Yatay", "Sol tarafta ikon/kapak, yanında net başlık"),
    SQUARE_GRID("Kare Izgara", "Büyük kare kapaklar ve alt başlık")
}

enum class QuickCardContentMode(val title: String) {
    BOTH("İsim ve Görsel"),
    TITLE_ONLY("Sadece İsim"),
    IMAGE_ONLY("Sadece Fotoğraf")
}

private val QUICK_SHAPE_ROUNDED = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
private val QUICK_SHAPE_SQUIRCLE = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
private val QUICK_SHAPE_SHARP = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

enum class QuickCardShape(val title: String) {
    ROUNDED("Yuvarlak Köşe"),
    SQUIRCLE("Yumuşak Kare"),
    CIRCLE("Kapsül / Daire"),
    SHARP("Keskin Köşe");

    fun toComposeShape(): androidx.compose.ui.graphics.Shape = when (this) {
        ROUNDED -> QUICK_SHAPE_ROUNDED
        SQUIRCLE -> QUICK_SHAPE_SQUIRCLE
        CIRCLE -> androidx.compose.foundation.shape.CircleShape
        SHARP -> QUICK_SHAPE_SHARP
    }
}

internal fun CustomThemeSpec.toFfi(): FfiCustomThemeSpec = FfiCustomThemeSpec(
    id = id,
    name = name,
    backgroundHex = backgroundHex,
    cardHex = cardHex,
    accentHex = accentHex,
    textHex = textHex
)

internal fun FfiCustomThemeSpec.toUi(): CustomThemeSpec = CustomThemeSpec(
    id = id,
    name = name,
    backgroundHex = backgroundHex,
    cardHex = cardHex,
    accentHex = accentHex,
    textHex = textHex
)

data class ResumePlaybackState(
    val track: FfiTrack,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val queueTracks: List<FfiTrack> = emptyList(),
    val queueIndex: Int = 0
)

internal fun ResumePlaybackState.toFfi(): FfiResumePlaybackState = FfiResumePlaybackState(
    track = track,
    positionMs = positionMs.coerceAtLeast(0L).toULong(),
    durationMs = durationMs.coerceAtLeast(0L).toULong(),
    updatedAtMs = updatedAtMs.coerceAtLeast(0L).toULong(),
    queueTracks = queueTracks,
    queueIndex = queueIndex.coerceAtLeast(0).toUInt()
)

internal fun FfiResumePlaybackState.toUi(): ResumePlaybackState = ResumePlaybackState(
    track = track,
    positionMs = positionMs.toLong(),
    durationMs = durationMs.toLong(),
    updatedAtMs = updatedAtMs.toLong(),
    queueTracks = queueTracks,
    queueIndex = queueIndex.toInt()
)

data class StorageBreakdown(
    val dbBytes: Long = 0L,
    val mediaCacheBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val downloadsBytes: Long = 0L
) {
    val totalBytes: Long get() = dbBytes + mediaCacheBytes + imageCacheBytes + downloadsBytes
}

data class TrackListeningStat(
    val track: FfiTrack,
    val playCount: Int,
    val totalListenedMs: Long,
    val lastPlayedAtMs: Long
)

internal fun TrackListeningStat.toFfi(): FfiTrackListeningStat = FfiTrackListeningStat(
    track = track,
    playCount = playCount.coerceAtLeast(1).toUInt(),
    totalListenedMs = totalListenedMs.coerceAtLeast(0L).toULong(),
    lastPlayedAtMs = lastPlayedAtMs.coerceAtLeast(0L).toULong()
)

internal fun FfiTrackListeningStat.toUi(): TrackListeningStat = TrackListeningStat(
    track = track,
    playCount = playCount.toInt(),
    totalListenedMs = totalListenedMs.toLong(),
    lastPlayedAtMs = lastPlayedAtMs.toLong()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val core = PlasmaWaveApp.core
    private val displayPrefs = application.getSharedPreferences("display_preferences", android.content.Context.MODE_PRIVATE)
    private val customThemesPrefs = application.getSharedPreferences("user_custom_themes", android.content.Context.MODE_PRIVATE)
    private val historyPrefs = application.getSharedPreferences("playback_history_v2", android.content.Context.MODE_PRIVATE)
    private val privacyPrefs = application.getSharedPreferences("privacy_and_stats_v1", android.content.Context.MODE_PRIVATE)
    private val resumePrefs = application.getSharedPreferences("resume_playback_v1", android.content.Context.MODE_PRIVATE)
    private val playlistCachePrefs = application.getSharedPreferences("fast_playlist_cache_v1", android.content.Context.MODE_PRIVATE)
    private val playlistTracksMemCache = ConcurrentHashMap<String, List<PlaylistTrackEntry>>()
    private val playlistCacheLock = Any()
    private val playlistCacheVersions = ConcurrentHashMap<String, Long>()

    private fun playlistCacheVersion(playlistId: String): Long =
        playlistCacheVersions[playlistId] ?: 0L

    @Volatile private var startupHydrated = false

    private val initialCoreSettings = runCatching { core.getSettings() }.getOrElse {
        FfiSettings(
            themeId = "org.kde.breeze-dark",
            language = "system",
            showDevFixtures = false,
            downloadsWifiOnly = false,
            downloadStorageLimitMib = 4096u,
            modsEnabled = true,
            deezerUserId = null,
            deezerAccessToken = null,
            customAccentColor = null,
            highRefreshRate = true,
            dynamicColorEnabled = true,
            swipeToSkip = true,
            seekbarStyle = "CLASSIC",
            albumFrame = "MODERN_ROUNDED",
            headerLogoStyle = "ORIGINAL",
            homeFeedType = "FOR_YOU",
            flowHeroCardEnabled = true,
            flowQuickAccessPinned = true,
            flowLibraryPinned = true,
            downloadsQuickAccessPinned = true,
            downloadsLibraryPinned = true,
            resumePlaybackEnabled = true,
            collectListeningStats = true,
            collectListeningHistory = true,
            saveResumePosition = true,
            enabledHomeWidgets = HomeWidgetType.entries.filter { it.defaultEnabled }.map { it.id },
            homeEditHoldSeconds = 3u
        )
    }

    private val _settings = MutableStateFlow(initialCoreSettings)
    val settings: StateFlow<FfiSettings> = _settings.asStateFlow()

    private fun updateCoreSettings(block: (FfiSettings) -> FfiSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val next = block(_settings.value)
                _settings.value = core.updateSettings(next)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to update settings in Rust core", e)
            }
        }
    }

    fun updateSettings(settings: FfiSettings) {
        try { _settings.value = core.updateSettings(settings) }
        catch (_: Exception) { _statusMessage.value = "Ayar kaydedilemedi" }
    }

    private val _highRefreshRate = MutableStateFlow(initialCoreSettings.highRefreshRate)
    val highRefreshRate = _highRefreshRate.asStateFlow()
    fun setHighRefreshRate(enabled: Boolean) {
        _highRefreshRate.value = enabled
        updateCoreSettings { it.copy(highRefreshRate = enabled) }
    }

    private val _dynamicColorEnabled = MutableStateFlow(initialCoreSettings.dynamicColorEnabled)
    val dynamicColorEnabled = _dynamicColorEnabled.asStateFlow()
    fun setDynamicColorEnabled(enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
        updateCoreSettings { it.copy(dynamicColorEnabled = enabled) }
    }

    private val _seekbarStyle = MutableStateFlow(
        runCatching { SeekbarStyle.valueOf(initialCoreSettings.seekbarStyle) }.getOrDefault(SeekbarStyle.CLASSIC)
    )
    val seekbarStyle = _seekbarStyle.asStateFlow()
    fun setSeekbarStyle(style: SeekbarStyle) {
        _seekbarStyle.value = style
        updateCoreSettings { it.copy(seekbarStyle = style.name) }
    }

    private val _albumFrame = MutableStateFlow(
        runCatching { AlbumCoverFrame.valueOf(initialCoreSettings.albumFrame) }.getOrDefault(AlbumCoverFrame.MODERN_ROUNDED)
    )
    val albumFrame = _albumFrame.asStateFlow()
    fun setAlbumFrame(frame: AlbumCoverFrame) {
        _albumFrame.value = frame
        updateCoreSettings { it.copy(albumFrame = frame.name) }
    }

    private val _swipeToSkipEnabled = MutableStateFlow(initialCoreSettings.swipeToSkip)
    val swipeToSkipEnabled = _swipeToSkipEnabled.asStateFlow()
    fun setSwipeToSkipEnabled(enabled: Boolean) {
        _swipeToSkipEnabled.value = enabled
        updateCoreSettings { it.copy(swipeToSkip = enabled) }
    }

    private val _flowHeroCardEnabled = MutableStateFlow(initialCoreSettings.flowHeroCardEnabled)
    val flowHeroCardEnabled = _flowHeroCardEnabled.asStateFlow()
    fun setFlowHeroCardEnabled(enabled: Boolean) {
        _flowHeroCardEnabled.value = enabled
        setHomeWidgetKindEnabled("block_flow_hero", enabled)
    }

    private val _flowQuickAccessPinned = MutableStateFlow(initialCoreSettings.flowQuickAccessPinned)
    val flowQuickAccessPinned = _flowQuickAccessPinned.asStateFlow()
    fun setFlowQuickAccessPinned(enabled: Boolean) {
        _flowQuickAccessPinned.value = enabled
        updateCoreSettings { it.copy(flowQuickAccessPinned = enabled) }
    }

    private val _flowLibraryPinned = MutableStateFlow(initialCoreSettings.flowLibraryPinned)
    val flowLibraryPinned = _flowLibraryPinned.asStateFlow()
    fun setFlowLibraryPinned(enabled: Boolean) {
        _flowLibraryPinned.value = enabled
        updateCoreSettings { it.copy(flowLibraryPinned = enabled) }
    }

    private val _quickCardStyle = MutableStateFlow(
        runCatching {
            QuickCardStyle.valueOf(displayPrefs.getString("quick_card_style", QuickCardStyle.THEME_ADAPTIVE.name) ?: QuickCardStyle.THEME_ADAPTIVE.name)
        }.getOrDefault(QuickCardStyle.THEME_ADAPTIVE)
    )
    val quickCardStyle = _quickCardStyle.asStateFlow()
    fun setQuickCardStyle(style: QuickCardStyle) {
        _quickCardStyle.value = style
        displayPrefs.edit().putString("quick_card_style", style.name).apply()
    }

    private val _quickCardScale = MutableStateFlow(
        displayPrefs.getFloat("quick_card_scale", 1.0f).coerceIn(0.50f, 1.30f)
    )
    val quickCardScale = _quickCardScale.asStateFlow()
    fun setQuickCardScale(scale: Float) {
        val clamped = scale.coerceIn(0.50f, 1.30f)
        _quickCardScale.value = clamped
        displayPrefs.edit().putFloat("quick_card_scale", clamped).apply()
    }

    private val _quickIconScale = MutableStateFlow(
        displayPrefs.getFloat("quick_icon_scale", 1.0f).coerceIn(0.70f, 1.40f)
    )
    val quickIconScale = _quickIconScale.asStateFlow()
    fun setQuickIconScale(scale: Float) {
        val clamped = scale.coerceIn(0.70f, 1.40f)
        _quickIconScale.value = clamped
        displayPrefs.edit().putFloat("quick_icon_scale", clamped).apply()
    }

    private val _quickCardShape = MutableStateFlow(
        runCatching {
            QuickCardShape.valueOf(displayPrefs.getString("quick_card_shape", QuickCardShape.ROUNDED.name) ?: QuickCardShape.ROUNDED.name)
        }.getOrDefault(QuickCardShape.ROUNDED)
    )
    val quickCardShape = _quickCardShape.asStateFlow()
    fun setQuickCardShape(shape: QuickCardShape) {
        _quickCardShape.value = shape
        displayPrefs.edit().putString("quick_card_shape", shape.name).apply()
    }

    private val _quickCardLayout = MutableStateFlow(
        runCatching {
            QuickCardLayout.valueOf(displayPrefs.getString("quick_card_layout", QuickCardLayout.COMPACT_HORIZONTAL.name) ?: QuickCardLayout.COMPACT_HORIZONTAL.name)
        }.getOrDefault(QuickCardLayout.COMPACT_HORIZONTAL)
    )
    val quickCardLayout = _quickCardLayout.asStateFlow()
    fun setQuickCardLayout(layout: QuickCardLayout) {
        _quickCardLayout.value = layout
        displayPrefs.edit().putString("quick_card_layout", layout.name).apply()
    }

    private val _quickCardContentMode = MutableStateFlow(
        runCatching {
            QuickCardContentMode.valueOf(displayPrefs.getString("quick_card_content_mode", QuickCardContentMode.BOTH.name) ?: QuickCardContentMode.BOTH.name)
        }.getOrDefault(QuickCardContentMode.BOTH)
    )
    val quickCardContentMode = _quickCardContentMode.asStateFlow()
    fun setQuickCardContentMode(mode: QuickCardContentMode) {
        _quickCardContentMode.value = mode
        displayPrefs.edit().putString("quick_card_content_mode", mode.name).apply()
    }

    private val _librarySyncWithQuick = MutableStateFlow(
        displayPrefs.getBoolean("library_sync_with_quick", true)
    )
    val librarySyncWithQuick = _librarySyncWithQuick.asStateFlow()

    private val _legacyUiMode = MutableStateFlow(
        displayPrefs.getBoolean("legacy_ui_mode", false)
    )
    val legacyUiMode = _legacyUiMode.asStateFlow()
    fun setLegacyUiMode(enabled: Boolean) {
        _legacyUiMode.value = enabled
        displayPrefs.edit().putBoolean("legacy_ui_mode", enabled).apply()
    }
    fun setLibrarySyncWithQuick(sync: Boolean) {
        if (!sync && _librarySyncWithQuick.value && !displayPrefs.contains("lib_card_style")) {
            // Copy current Quick Access settings as initial baseline when splitting for the first time
            setLibraryCardStyle(_quickCardStyle.value)
            setLibraryCardScale(_quickCardScale.value)
            setLibraryIconScale(_quickIconScale.value)
            setLibraryCardShape(_quickCardShape.value)
            setLibraryCardLayout(_quickCardLayout.value)
            setLibraryCardContentMode(_quickCardContentMode.value)
        }
        _librarySyncWithQuick.value = sync
        displayPrefs.edit().putBoolean("library_sync_with_quick", sync).apply()
    }

    private val _libraryCardStyle = MutableStateFlow(
        runCatching {
            QuickCardStyle.valueOf(displayPrefs.getString("lib_card_style", _quickCardStyle.value.name) ?: _quickCardStyle.value.name)
        }.getOrDefault(_quickCardStyle.value)
    )
    val libraryCardStyle = _libraryCardStyle.asStateFlow()
    fun setLibraryCardStyle(style: QuickCardStyle) {
        _libraryCardStyle.value = style
        displayPrefs.edit().putString("lib_card_style", style.name).apply()
    }

    private val _libraryCardScale = MutableStateFlow(
        displayPrefs.getFloat("lib_card_scale", _quickCardScale.value).coerceIn(0.50f, 1.30f)
    )
    val libraryCardScale = _libraryCardScale.asStateFlow()
    fun setLibraryCardScale(scale: Float) {
        val clamped = scale.coerceIn(0.50f, 1.30f)
        _libraryCardScale.value = clamped
        displayPrefs.edit().putFloat("lib_card_scale", clamped).apply()
    }

    private val _libraryIconScale = MutableStateFlow(
        displayPrefs.getFloat("lib_icon_scale", _quickIconScale.value).coerceIn(0.70f, 1.40f)
    )
    val libraryIconScale = _libraryIconScale.asStateFlow()
    fun setLibraryIconScale(scale: Float) {
        val clamped = scale.coerceIn(0.70f, 1.40f)
        _libraryIconScale.value = clamped
        displayPrefs.edit().putFloat("lib_icon_scale", clamped).apply()
    }

    private val _libraryCardShape = MutableStateFlow(
        runCatching {
            QuickCardShape.valueOf(displayPrefs.getString("lib_card_shape", _quickCardShape.value.name) ?: _quickCardShape.value.name)
        }.getOrDefault(_quickCardShape.value)
    )
    val libraryCardShape = _libraryCardShape.asStateFlow()
    fun setLibraryCardShape(shape: QuickCardShape) {
        _libraryCardShape.value = shape
        displayPrefs.edit().putString("lib_card_shape", shape.name).apply()
    }

    private val _libraryCardLayout = MutableStateFlow(
        runCatching {
            QuickCardLayout.valueOf(displayPrefs.getString("lib_card_layout", _quickCardLayout.value.name) ?: _quickCardLayout.value.name)
        }.getOrDefault(_quickCardLayout.value)
    )
    val libraryCardLayout = _libraryCardLayout.asStateFlow()
    fun setLibraryCardLayout(layout: QuickCardLayout) {
        _libraryCardLayout.value = layout
        displayPrefs.edit().putString("lib_card_layout", layout.name).apply()
    }

    private val _libraryCardContentMode = MutableStateFlow(
        runCatching {
            QuickCardContentMode.valueOf(displayPrefs.getString("lib_card_content_mode", _quickCardContentMode.value.name) ?: _quickCardContentMode.value.name)
        }.getOrDefault(_quickCardContentMode.value)
    )
    val libraryCardContentMode = _libraryCardContentMode.asStateFlow()
    fun setLibraryCardContentMode(mode: QuickCardContentMode) {
        _libraryCardContentMode.value = mode
        displayPrefs.edit().putString("lib_card_content_mode", mode.name).apply()
    }

    private val _downloadsQuickAccessPinned = MutableStateFlow(initialCoreSettings.downloadsQuickAccessPinned)
    val downloadsQuickAccessPinned = _downloadsQuickAccessPinned.asStateFlow()
    fun setDownloadsQuickAccessPinned(enabled: Boolean) {
        _downloadsQuickAccessPinned.value = enabled
        updateCoreSettings { it.copy(downloadsQuickAccessPinned = enabled) }
    }

    private val _downloadsLibraryPinned = MutableStateFlow(initialCoreSettings.downloadsLibraryPinned)
    val downloadsLibraryPinned = _downloadsLibraryPinned.asStateFlow()
    fun setDownloadsLibraryPinned(enabled: Boolean) {
        _downloadsLibraryPinned.value = enabled
        updateCoreSettings { it.copy(downloadsLibraryPinned = enabled) }
    }

    private val _headerLogoStyle = MutableStateFlow(
        runCatching { HeaderLogoStyle.valueOf(initialCoreSettings.headerLogoStyle) }.getOrDefault(HeaderLogoStyle.ORIGINAL)
    )
    val headerLogoStyle = _headerLogoStyle.asStateFlow()
    fun setHeaderLogoStyle(style: HeaderLogoStyle) {
        _headerLogoStyle.value = style
        updateCoreSettings { it.copy(headerLogoStyle = style.name) }
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

    private fun parseHomeFeedTypes(raw: String): Set<HomeFeedType> =
        raw.split(',')
            .mapNotNull { token -> runCatching { HomeFeedType.valueOf(token.trim()) }.getOrNull() }
            .toSet()
            .ifEmpty { setOf(HomeFeedType.FOR_YOU) }

    private val _homeFeedTypes = MutableStateFlow(parseHomeFeedTypes(initialCoreSettings.homeFeedType))
    val homeFeedTypes = _homeFeedTypes.asStateFlow()
    fun toggleHomeFeedType(feed: HomeFeedType) {
        val current = _homeFeedTypes.value
        val next = if (feed in current) {
            if (current.size > 1) current - feed else current
        } else {
            current + feed
        }
        _homeFeedTypes.value = next
        val serialized = HomeFeedType.entries.filter { it in next }.joinToString(",") { it.name }
        displayPrefs.edit().putString("home_feed_type", serialized).apply()
        updateCoreSettings { it.copy(homeFeedType = serialized) }
    }

    private val _launcherIcon = MutableStateFlow(AppLauncherIcon.DEFAULT)
    val launcherIcon = _launcherIcon.asStateFlow()
    fun setLauncherIcon(icon: AppLauncherIcon) {
        LauncherIconManager.setLauncherIcon(getApplication(), icon, requestPin = (icon == AppLauncherIcon.CUSTOM))
        _launcherIcon.value = icon
    }

    private val _userCustomThemes = MutableStateFlow<List<CustomThemeSpec>>(emptyList())
    val userCustomThemes: StateFlow<List<CustomThemeSpec>> = _userCustomThemes.asStateFlow()

    // 1. Kaldığın Yerden Devam Et (UI görünürlüğü & kayıt)
    private val _resumePlaybackEnabled = MutableStateFlow(initialCoreSettings.resumePlaybackEnabled)
    val resumePlaybackEnabled: StateFlow<Boolean> = _resumePlaybackEnabled.asStateFlow()
    fun setResumePlaybackEnabled(enabled: Boolean) {
        _resumePlaybackEnabled.value = enabled
        setHomeWidgetKindEnabled("block_resume_playback", enabled)
    }

    // 2. Veri ve Gizlilik Ayarları (İsteyen istediği zaman açıp kapatabilir)
    private val _collectListeningStatsEnabled = MutableStateFlow(initialCoreSettings.collectListeningStats)
    val collectListeningStatsEnabled: StateFlow<Boolean> = _collectListeningStatsEnabled.asStateFlow()
    fun setCollectListeningStatsEnabled(enabled: Boolean) {
        _collectListeningStatsEnabled.value = enabled
        updateCoreSettings { it.copy(collectListeningStats = enabled) }
    }

    private val _collectListeningHistoryEnabled = MutableStateFlow(initialCoreSettings.collectListeningHistory)
    val collectListeningHistoryEnabled: StateFlow<Boolean> = _collectListeningHistoryEnabled.asStateFlow()
    fun setCollectListeningHistoryEnabled(enabled: Boolean) {
        _collectListeningHistoryEnabled.value = enabled
        updateCoreSettings { it.copy(collectListeningHistory = enabled) }
    }

    private val _saveResumePositionEnabled = MutableStateFlow(initialCoreSettings.saveResumePosition)
    val saveResumePositionEnabled: StateFlow<Boolean> = _saveResumePositionEnabled.asStateFlow()
    fun setSaveResumePositionEnabled(enabled: Boolean) {
        _saveResumePositionEnabled.value = enabled
        updateCoreSettings { it.copy(saveResumePosition = enabled) }
    }

    // 3. Ana Sayfa Düzeni ve Widget Kişiselleştirme (Rust Çekirdekli)
    private val homeLayoutMutex = Mutex()
    private val _homeWidgetCatalog = MutableStateFlow<List<FfiHomeWidgetCatalogEntry>>(emptyList())
    val homeWidgetCatalog: StateFlow<List<FfiHomeWidgetCatalogEntry>> = _homeWidgetCatalog.asStateFlow()

    private val _homeLayout = MutableStateFlow<List<FfiHomeWidgetItem>>(
        runCatching { core.getHomeLayout() }.getOrDefault(emptyList())
    )
    val homeLayout: StateFlow<List<FfiHomeWidgetItem>> = _homeLayout.asStateFlow()

    private var previousHomeLayoutSnapshot: List<FfiHomeWidgetItem>? = null
    private val _canUndoHomeLayout = MutableStateFlow(false)
    val canUndoHomeLayout: StateFlow<Boolean> = _canUndoHomeLayout.asStateFlow()

    private val _homeEditHoldSeconds = MutableStateFlow(
        initialCoreSettings.homeEditHoldSeconds.toInt().coerceIn(3, 10)
    )
    val homeEditHoldSeconds: StateFlow<Int> = _homeEditHoldSeconds.asStateFlow()

    fun setHomeEditHoldSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(3, 10)
        _homeEditHoldSeconds.value = clamped
        updateCoreSettings { it.copy(homeEditHoldSeconds = clamped.toUByte()) }
    }

    private val _enabledHomeWidgets = MutableStateFlow<Set<HomeWidgetType>>(
        computeEnabledWidgets(_homeLayout.value, initialCoreSettings.enabledHomeWidgets)
    )
    val enabledHomeWidgets: StateFlow<Set<HomeWidgetType>> = _enabledHomeWidgets.asStateFlow()

    private fun computeEnabledWidgets(
        layout: List<FfiHomeWidgetItem>,
        fallbackIds: List<String>
    ): Set<HomeWidgetType> {
        if (layout.isEmpty()) {
            return HomeWidgetType.entries.filter { it.id in fallbackIds }.toSet()
        }
        val activeKinds = layout.asSequence().filter { it.enabled }.map { it.kind }.toSet()
        return HomeWidgetType.entries.filter { it.id in activeKinds }.toSet()
    }

    private fun applyHomeLayoutSnapshot(
        layout: List<FfiHomeWidgetItem>,
        recordUndoFrom: List<FfiHomeWidgetItem>? = null
    ) {
        if (recordUndoFrom != null && recordUndoFrom != layout) {
            previousHomeLayoutSnapshot = recordUndoFrom
            _canUndoHomeLayout.value = true
        }
        _homeLayout.value = layout
        _enabledHomeWidgets.value = computeEnabledWidgets(layout, _settings.value.enabledHomeWidgets)
        val hasFlow = layout.any { it.enabled && it.kind == "block_flow_hero" }
        val hasResume = layout.any { it.enabled && it.kind == "block_resume_playback" }
        _flowHeroCardEnabled.value = hasFlow
        _resumePlaybackEnabled.value = hasResume
    }

    private fun mutateHomeLayoutInRust(
        recordUndo: Boolean = true,
        optimistic: ((List<FfiHomeWidgetItem>) -> List<FfiHomeWidgetItem>)? = null,
        rustCall: () -> List<FfiHomeWidgetItem>
    ) {
        val before = _homeLayout.value
        if (optimistic != null) {
            applyHomeLayoutSnapshot(optimistic(before), recordUndoFrom = if (recordUndo) before else null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            homeLayoutMutex.withLock {
                try {
                    val updated = rustCall()
                    applyHomeLayoutSnapshot(updated, recordUndoFrom = if (recordUndo && optimistic == null) before else null)
                    _settings.value = core.getSettings()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Home layout Rust mutation failed", e)
                    applyHomeLayoutSnapshot(before, recordUndoFrom = null)
                    _statusMessage.value = "Ana sayfa düzeni kaydedilemedi"
                }
            }
        }
    }

    fun setHomeWidgetEnabled(widget: HomeWidgetType, enabled: Boolean) {
        setHomeWidgetKindEnabled(widget.id, enabled)
    }

    fun setHomeWidgetKindEnabled(kind: String, enabled: Boolean) {
        mutateHomeLayoutInRust(recordUndo = true) {
            core.setHomeWidgetKindEnabled(kind, enabled)
        }
    }

    fun addHomeWidget(kind: String, insertAt: Int? = null) {
        mutateHomeLayoutInRust(recordUndo = true) {
            core.addHomeWidget(kind, insertAt?.toUInt())
        }
    }

    fun removeHomeWidget(instanceId: String) {
        mutateHomeLayoutInRust(
            recordUndo = true,
            optimistic = { list -> list.filterNot { it.instanceId == instanceId } }
        ) {
            core.removeHomeWidget(instanceId)
        }
    }

    fun moveHomeWidgetStep(instanceId: String, delta: Int) {
        val current = _homeLayout.value
        val fromIdx = current.indexOfFirst { it.instanceId == instanceId }
        if (fromIdx < 0) return
        val toIdx = (fromIdx + delta).coerceIn(0, (current.size - 1).coerceAtLeast(0))
        if (toIdx == fromIdx) return
        mutateHomeLayoutInRust(
            recordUndo = true,
            optimistic = { list ->
                list.toMutableList().apply {
                    val moved = removeAt(fromIdx)
                    add(toIdx, moved)
                }.mapIndexed { idx, item -> item.copy(order = idx.toUInt()) }
            }
        ) {
            core.moveHomeWidget(instanceId, toIdx.toUInt())
        }
    }

    fun reorderHomeWidgets(orderedInstanceIds: List<String>) {
        if (orderedInstanceIds.isEmpty()) return
        mutateHomeLayoutInRust(
            recordUndo = true,
            optimistic = { list ->
                val byId = list.associateBy { it.instanceId }
                val reordered = orderedInstanceIds.mapNotNull { byId[it] }
                val remaining = list.filter { it.instanceId !in orderedInstanceIds }
                (reordered + remaining).mapIndexed { idx, item -> item.copy(order = idx.toUInt()) }
            }
        ) {
            core.reorderHomeWidgets(orderedInstanceIds)
        }
    }

    fun customizeHomeWidget(instanceId: String, patch: FfiHomeWidgetPatch) {
        mutateHomeLayoutInRust(recordUndo = true) {
            core.customizeHomeWidget(instanceId, patch)
        }
    }

    fun undoHomeLayoutChange() {
        val snap = previousHomeLayoutSnapshot ?: return
        previousHomeLayoutSnapshot = null
        _canUndoHomeLayout.value = false
        mutateHomeLayoutInRust(recordUndo = false) {
            core.restoreHomeLayout(snap)
        }
    }

    fun resetHomeLayout() {
        mutateHomeLayoutInRust(recordUndo = true) {
            core.resetHomeLayout()
        }
    }

    // 4. Dinleme İstatistikleri (Hangi şarkı kaç kere dinlendi, toplam dinleme süresi — Rust SQLite v2)
    private val _recentTracks = MutableStateFlow<List<FfiTrack>>(emptyList())
    val recentTracks: StateFlow<List<FfiTrack>> = _recentTracks.asStateFlow()

    private val _mostPlayedTracks = MutableStateFlow<List<FfiTrack>>(emptyList())
    val mostPlayedTracks: StateFlow<List<FfiTrack>> = _mostPlayedTracks.asStateFlow()

    private val _trackListeningStats = MutableStateFlow<List<TrackListeningStat>>(emptyList())
    val trackListeningStats: StateFlow<List<TrackListeningStat>> = _trackListeningStats.asStateFlow()

    private val _totalListeningTimeMs = MutableStateFlow(0L)
    val totalListeningTimeMs: StateFlow<Long> = _totalListeningTimeMs.asStateFlow()

    private val _resumePlaybackState = MutableStateFlow<ResumePlaybackState?>(null)
    val resumePlaybackState: StateFlow<ResumePlaybackState?> = _resumePlaybackState.asStateFlow()

    private fun migrateLegacyPrefsToRustOnce(): Boolean {
        return try {
            if (displayPrefs.getBoolean("migrated_to_rust_v2", false)) return false

            // 1. Migrate UI & Privacy settings from displayPrefs & privacyPrefs
            val cur = core.getSettings()
            val savedWidgets = displayPrefs.getStringSet("enabled_home_widgets", null)?.toList()
                ?: cur.enabledHomeWidgets
            val migratedSettings = cur.copy(
                highRefreshRate = displayPrefs.getBoolean("high_refresh_rate", cur.highRefreshRate),
                dynamicColorEnabled = displayPrefs.getBoolean("dynamic_color_enabled", cur.dynamicColorEnabled),
                swipeToSkip = displayPrefs.getBoolean("swipe_to_skip", cur.swipeToSkip),
                seekbarStyle = displayPrefs.getString("seekbar_style", cur.seekbarStyle) ?: cur.seekbarStyle,
                albumFrame = displayPrefs.getString("album_frame", cur.albumFrame) ?: cur.albumFrame,
                headerLogoStyle = displayPrefs.getString("header_logo_style", cur.headerLogoStyle) ?: cur.headerLogoStyle,
                homeFeedType = displayPrefs.getString("home_feed_type", cur.homeFeedType) ?: cur.homeFeedType,
                flowHeroCardEnabled = displayPrefs.getBoolean("flow_hero_card_enabled", cur.flowHeroCardEnabled),
                flowQuickAccessPinned = displayPrefs.getBoolean("flow_quick_access_pinned", cur.flowQuickAccessPinned),
                flowLibraryPinned = displayPrefs.getBoolean("flow_library_pinned", cur.flowLibraryPinned),
                downloadsQuickAccessPinned = displayPrefs.getBoolean("downloads_quick_access_pinned", cur.downloadsQuickAccessPinned),
                downloadsLibraryPinned = displayPrefs.getBoolean("downloads_library_pinned", cur.downloadsLibraryPinned),
                resumePlaybackEnabled = displayPrefs.getBoolean("resume_playback_enabled", cur.resumePlaybackEnabled),
                collectListeningStats = privacyPrefs.getBoolean("collect_listening_stats", cur.collectListeningStats),
                collectListeningHistory = privacyPrefs.getBoolean("collect_listening_history", cur.collectListeningHistory),
                saveResumePosition = privacyPrefs.getBoolean("save_resume_position", cur.saveResumePosition),
                enabledHomeWidgets = savedWidgets
            )
            core.updateSettings(migratedSettings)

            // 2. Migrate user custom themes
            val rawThemes = customThemesPrefs.getString("themes", null)
            if (!rawThemes.isNullOrBlank()) {
                val arr = JSONArray(rawThemes)
                for (i in 0 until arr.length()) {
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        core.saveUserCustomTheme(
                            FfiCustomThemeSpec(
                                id = obj.getString("id"),
                                name = obj.getString("name"),
                                backgroundHex = obj.optString("backgroundHex", "#141224"),
                                cardHex = obj.optString("cardHex", "#282348"),
                                accentHex = obj.optString("accentHex", "#ED438D"),
                                textHex = obj.optString("textHex", "#F5EEF8")
                            )
                        )
                    }
                }
                // Restore original active theme id if needed
                if (migratedSettings.themeId.isNotBlank()) {
                    runCatching { core.setTheme(migratedSettings.themeId) }
                }
            }

            // 3. Migrate playback history & listening stats
            val rawHistory = historyPrefs.getString("entries", null)
            val legacyHistoryPairs = mutableListOf<Pair<FfiTrack, Int>>()
            if (!rawHistory.isNullOrBlank()) {
                val arr = JSONArray(rawHistory)
                for (i in 0 until arr.length()) {
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        val track = jsonTrack(obj.getJSONObject("track"))
                        val count = obj.optInt("count", 1)
                        legacyHistoryPairs.add(Pair(track, count))
                    }
                }
                if (legacyHistoryPairs.isNotEmpty()) {
                    core.setRecentTracks(legacyHistoryPairs.map { it.first })
                }
            }

            val rawStats = privacyPrefs.getString("track_stats", null)
            if (!rawStats.isNullOrBlank()) {
                val arr = JSONArray(rawStats)
                val batch = (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        TrackListeningStat(
                            track = jsonTrack(obj.getJSONObject("track")),
                            playCount = obj.optInt("playCount", 1),
                            totalListenedMs = obj.optLong("totalListenedMs", 0L),
                            lastPlayedAtMs = obj.optLong("lastPlayedAtMs", System.currentTimeMillis())
                        ).toFfi()
                    }.getOrNull()
                }
                if (batch.isNotEmpty()) {
                    core.upsertListeningStatsBatch(batch)
                }
            } else if (legacyHistoryPairs.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val seeded = legacyHistoryPairs.mapIndexed { idx, (t, count) ->
                    val estDuration = (t.durationMs.toLong().takeIf { it > 0L } ?: 180_000L) * count
                    TrackListeningStat(
                        track = t,
                        playCount = count,
                        totalListenedMs = estDuration,
                        lastPlayedAtMs = now - idx * 60_000L
                    ).toFfi()
                }
                core.upsertListeningStatsBatch(seeded)
            }

            val savedTotalMs = privacyPrefs.getLong("total_listening_time_ms", -1L)
            if (savedTotalMs > 0L) {
                core.setTotalListeningTimeMs(savedTotalMs.toULong())
            }

            // 4. Migrate resume playback state
            val rawResume = resumePrefs.getString("state", null)
            if (!rawResume.isNullOrBlank()) {
                runCatching {
                    val obj = JSONObject(rawResume)
                    val track = jsonTrack(obj.getJSONObject("track"))
                    val qArr = obj.optJSONArray("queue") ?: JSONArray()
                    val queue = (0 until qArr.length()).mapNotNull { i ->
                        runCatching { jsonTrack(qArr.getJSONObject(i)) }.getOrNull()
                    }
                    core.saveResumePlaybackState(
                        ResumePlaybackState(
                            track = track,
                            positionMs = obj.optLong("positionMs", 0L),
                            durationMs = obj.optLong("durationMs", track.durationMs.toLong()),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                            queueTracks = queue.ifEmpty { listOf(track) },
                            queueIndex = obj.optInt("queueIndex", 0)
                        ).toFfi()
                    )
                }
            }

            displayPrefs.edit().putBoolean("migrated_to_rust_v2", true).apply()
            true
        } catch (e: Exception) {
            Log.e("MainViewModel", "Legacy prefs migration error", e)
            false
        }
    }

    private fun saveResumePlaybackSnapshot(
        track: FfiTrack,
        positionMs: Long,
        durationMs: Long,
        queueTracks: List<FfiTrack>,
        queueIndex: Int
    ) {
        if (!_saveResumePositionEnabled.value) return
        val safeDur = durationMs.takeIf { it > 0L } ?: track.durationMs.toLong().takeIf { it > 0L } ?: 210_000L
        val safePos = positionMs.coerceIn(0L, safeDur)
        val trimmedQueue = queueTracks.take(30).ifEmpty { listOf(track) }
        val safeIdx = queueIndex.coerceIn(0, (trimmedQueue.size - 1).coerceAtLeast(0))
        val state = ResumePlaybackState(
            track = track,
            positionMs = safePos,
            durationMs = safeDur,
            updatedAtMs = System.currentTimeMillis(),
            queueTracks = trimmedQueue,
            queueIndex = safeIdx
        )
        _resumePlaybackState.value = state
        viewModelScope.launch(Dispatchers.IO) {
            try {
                core.saveResumePlaybackState(state.toFfi())
            } catch (_: Exception) {}
        }
    }

    fun resumeSavedPlayback() {
        val state = _resumePlaybackState.value ?: return
        val curId = playbackManager.uiState.value.currentEntry?.track?.id
        if (curId == state.track.id) {
            playbackManager.seekTo(state.positionMs)
            if (!playbackManager.uiState.value.isPlaying) {
                playbackManager.togglePlayPause()
            }
        } else {
            val queue = state.queueTracks.ifEmpty { listOf(state.track) }
            val idx = queue.indexOfFirst { it.id == state.track.id }.takeIf { it >= 0 } ?: 0
            playbackManager.playTrackList(queue, idx, state.positionMs)
        }
    }

    fun recordTrackPlay(track: FfiTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis().toULong()
                core.recordTrackPlay(
                    track = track,
                    collectStats = _collectListeningStatsEnabled.value,
                    collectHistory = _collectListeningHistoryEnabled.value,
                    timestampMs = now
                )
                if (_collectListeningHistoryEnabled.value) {
                    _recentTracks.value = core.getRecentTracks(100u)
                    _mostPlayedTracks.value = core.getMostPlayedTracks(100u)
                }
                if (_collectListeningStatsEnabled.value) {
                    _trackListeningStats.value = core.getListeningStats(300u).map { it.toUi() }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "recordTrackPlay failed", e)
            }
        }
    }

    private fun recordActiveListeningTick(track: FfiTrack, deltaMs: Long) {
        if (!_collectListeningStatsEnabled.value || deltaMs <= 0L) return
        try {
            val now = System.currentTimeMillis().toULong()
            val updatedTotal = core.recordListeningTick(
                track = track,
                deltaMs = deltaMs.toULong(),
                collectStats = true,
                timestampMs = now
            )
            _totalListeningTimeMs.value = updatedTotal.toLong()
            _trackListeningStats.value = core.getListeningStats(300u).map { it.toUi() }
        } catch (e: Exception) {
            Log.e("MainViewModel", "recordActiveListeningTick failed", e)
        }
    }

    private val _privateSessionStatus = MutableStateFlow(
        FfiPrivateSessionStatus(active = false, untilEpochMs = 0uL)
    )
    val privateSessionStatus: StateFlow<FfiPrivateSessionStatus> = _privateSessionStatus.asStateFlow()

    private val _storageBreakdown = MutableStateFlow(StorageBreakdown())
    val storageBreakdown: StateFlow<StorageBreakdown> = _storageBreakdown.asStateFlow()

    fun setPrivateSession(enabled: Boolean, durationMs: Long = 0L) {
        val status = core.setPrivateSession(
            enabled = enabled,
            durationMs = durationMs.coerceAtLeast(0L).toULong(),
            nowMs = System.currentTimeMillis().toULong()
        )
        _privateSessionStatus.value = status
        _statusMessage.value = if (status.active) {
            if (durationMs > 0L) "Gizli dinleme oturumu açıldı (${durationMs / 3_600_000L} saat)"
            else "Gizli dinleme oturumu açıldı"
        } else {
            "Gizli dinleme oturumu kapatıldı"
        }
    }

    fun refreshPrivateSessionStatus() {
        _privateSessionStatus.value = core.getPrivateSessionStatus(System.currentTimeMillis().toULong())
    }

    suspend fun loadListeningAnalytics(periodId: String, sortBy: String): FfiListeningAnalytics? =
        withContext(Dispatchers.IO) {
            runCatching {
                core.getListeningAnalytics(
                    periodId = periodId,
                    sortBy = sortBy,
                    nowMs = System.currentTimeMillis().toULong()
                )
            }.getOrNull()
        }

    suspend fun buildListeningStatsCsv(): String = withContext(Dispatchers.IO) {
        runCatching { core.buildListeningStatsCsv() }.getOrDefault("")
    }

    suspend fun importUserDataFromJson(rawJson: String, merge: Boolean): Result<FfiImportBackupReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val report = core.importUserDataJson(rawJson, merge)
                _trackListeningStats.value = core.getListeningStats(300u).map { it.toUi() }
                _totalListeningTimeMs.value = core.getTotalListeningTimeMs().toLong()
                _recentTracks.value = core.getRecentTracks(100u)
                _mostPlayedTracks.value = core.getMostPlayedTracks(100u)
                _availableThemes.value = core.listThemes()
                report
            }
        }

    fun clearListeningHistoryOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { core.clearListeningHistoryOnly() }
            _recentTracks.value = emptyList()
            _statusMessage.value = "Son çalınanlar geçmişi temizlendi"
        }
    }

    fun clearListeningStatsOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { core.clearListeningStatsOnly() }
            _trackListeningStats.value = emptyList()
            _mostPlayedTracks.value = emptyList()
            _totalListeningTimeMs.value = 0L
            _statusMessage.value = "Dinleme sayaçları ve süreler sıfırlandı"
        }
    }

    fun clearLast24hData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { core.clearLast24hData(System.currentTimeMillis().toULong()) }
            _recentTracks.value = runCatching { core.getRecentTracks(100u) }.getOrDefault(emptyList())
            _trackListeningStats.value = runCatching { core.getListeningStats(300u).map { it.toUi() } }.getOrDefault(emptyList())
            _statusMessage.value = "Son 24 saatin verileri temizlendi"
        }
    }

    fun deleteSingleTrackStat(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { core.deleteSingleTrackStat(trackId) }
            _trackListeningStats.value = runCatching { core.getListeningStats(300u).map { it.toUi() } }.getOrDefault(emptyList())
            _recentTracks.value = runCatching { core.getRecentTracks(100u) }.getOrDefault(emptyList())
            _mostPlayedTracks.value = runCatching { core.getMostPlayedTracks(100u) }.getOrDefault(emptyList())
            _statusMessage.value = "Şarkı istatistiği silindi"
        }
    }

    fun createPlaylistFromStats(name: String, tracks: List<FfiTrack>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val created = core.createPlaylist(name)
                core.addTracksToPlaylist(created.id, tracks)
                _playlists.value = core.getPlaylists()
                _statusMessage.value = "\"$name\" çalma listesi (${tracks.size} şarkı) oluşturuldu"
            }.onFailure { err ->
                _statusMessage.value = "Liste oluşturulamadı: ${err.message}"
            }
        }
    }

    fun refreshStorageBreakdown() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val dbFile = File(app.filesDir, "plasmawave_data/plasmawave.db")
            val dbWal = File(app.filesDir, "plasmawave_data/plasmawave.db-wal")
            val dbBytes = (if (dbFile.exists()) dbFile.length() else 0L) +
                (if (dbWal.exists()) dbWal.length() else 0L)
            val mediaCacheBytes = dirSizeBytes(File(app.cacheDir, "media_cache"))
            val imageCacheBytes = dirSizeBytes(File(app.cacheDir, "image_cache"))
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "PlasmaWave"
            )
            val downloadsBytes = dirSizeBytes(downloadsDir)
            _storageBreakdown.value = StorageBreakdown(
                dbBytes = dbBytes,
                mediaCacheBytes = mediaCacheBytes,
                imageCacheBytes = imageCacheBytes,
                downloadsBytes = downloadsBytes
            )
        }
    }

    fun clearAppCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            runCatching { File(app.cacheDir, "image_cache").deleteRecursively() }
            runCatching { File(app.cacheDir, "media_cache").deleteRecursively() }
            refreshStorageBreakdown()
            _statusMessage.value = "Medya ve kapak önbellekleri temizlendi"
        }
    }

    private fun dirSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        runCatching {
            dir.walkTopDown().forEach { f -> if (f.isFile) sum += f.length() }
        }
        return sum
    }

    fun formatDurationTr(ms: Long): String =
        core.formatDurationTr(ms.coerceAtLeast(0L).toULong())

    fun buildUserDataExportJson(): String =
        runCatching { core.buildUserDataExportJson(System.currentTimeMillis().toULong()) }
            .getOrElse { "{}" }

    fun exportUserDataToDownloads(): String? {
        return try {
            val json = buildUserDataExportJson()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "projekt_b-d_verilerim_$stamp.json"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                downloadsDir
            } else {
                getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: getApplication<Application>().filesDir
            }
            val file = File(targetDir, fileName)
            file.writeText(json)
            _statusMessage.value = "Verileriniz indirildi: ${file.name}"
            file.absolutePath
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to export user data", e)
            _statusMessage.value = "Veriler dışa aktarılamadı: ${e.message}"
            null
        }
    }

    fun clearAllCollectedUserData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { core.clearAllCollectedUserData() }
            historyPrefs.edit().clear().apply()
            privacyPrefs.edit()
                .remove("track_stats")
                .putLong("total_listening_time_ms", 0L)
                .apply()
            resumePrefs.edit().clear().apply()
            _recentTracks.value = emptyList()
            _mostPlayedTracks.value = emptyList()
            _trackListeningStats.value = emptyList()
            _totalListeningTimeMs.value = 0L
            _resumePlaybackState.value = null
            quickArtistsCache.clear()
            _statusMessage.value = "Toplanan tüm dinleme istatistikleri ve geçmiş verileri silindi"
        }
    }

    private val quickArtistsCache = ConcurrentHashMap<Int, List<FfiQuickArtistItem>>()

    fun getCachedQuickArtists(limit: Int = 12): List<FfiQuickArtistItem> {
        val safeLimit = limit.coerceAtLeast(1)
        quickArtistsCache[safeLimit]?.let { return it }
        return quickArtistsCache.values.firstOrNull()?.take(safeLimit) ?: emptyList()
    }

    suspend fun loadQuickArtists(limit: Int = 12, fallbackTracks: List<FfiTrack> = emptyList()): List<FfiQuickArtistItem> =
        withContext(Dispatchers.IO) {
            val safeLimit = limit.coerceAtLeast(1)
            runCatching { core.getQuickArtists(safeLimit.toUInt(), fallbackTracks) }
                .onSuccess { quickArtistsCache[safeLimit] = it }
                .getOrElse { getCachedQuickArtists(safeLimit) }
        }

    fun getQuickArtists(limit: Int = 12, fallbackTracks: List<FfiTrack> = emptyList()): List<FfiQuickArtistItem> =
        runCatching { core.getQuickArtists(limit.coerceAtLeast(1).toUInt(), fallbackTracks) }
            .getOrDefault(emptyList())

    fun generateMoodMix(mood: String, limit: Int = 25, candidateTracks: List<FfiTrack> = emptyList()): List<FfiTrack> =
        runCatching { core.generateMoodMix(mood, limit.coerceAtLeast(1).toUInt(), candidateTracks) }
            .getOrDefault(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _externalSearchTrigger = MutableStateFlow<Triple<String, SearchCategory, Long>?>(null)
    val externalSearchTrigger = _externalSearchTrigger.asStateFlow()
    fun navigateToSearch(query: String, category: SearchCategory) {
        _searchQuery.value = query
        _searchCategory.value = category
        _externalSearchTrigger.value = Triple(query, category, System.currentTimeMillis())
        search(query, category, immediate = true)
    }
    fun clearExternalSearchTrigger() {
        _externalSearchTrigger.value = null
    }
    val playbackManager = PlaybackManager.getInstance(application)
    val jamManager = JamManager(application, playbackManager)
    val jamState = jamManager.state

    suspend fun searchTracksForJam(query: String): List<FfiTrack> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        runCatching { core.searchAll(q) }.getOrDefault(emptyList())
    }
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
    fun cancelDownloadGroup(ids: List<String>) { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.cancelGroup(ids) } }
    fun cancelAllDownloads() { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.cancelAll() } }
    fun forgetFullDownload(id: String) { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.forget(id) } }
    fun forgetDownloadGroup(ids: List<String>) { viewModelScope.launch(Dispatchers.IO) { fullDownloadManager.forgetGroup(ids) } }
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

    private val _deezerUser = MutableStateFlow<FfiDeezerUser?>(deezerSession.cachedUser())
    val deezerUser: StateFlow<FfiDeezerUser?> = _deezerUser.asStateFlow()

    private val _deezerPlaylists = MutableStateFlow<List<LibraryPlaylistEntry>>(emptyList())
    val deezerPlaylists: StateFlow<List<LibraryPlaylistEntry>> = _deezerPlaylists.asStateFlow()
    private val _deezerLibraryLoading = MutableStateFlow(deezerSession.hasSavedSession)
    val deezerLibraryLoading: StateFlow<Boolean> = _deezerLibraryLoading.asStateFlow()

    private val _deezerFavorites = MutableStateFlow<List<FfiTrack>>(emptyList())
    val deezerFavorites: StateFlow<List<FfiTrack>> = _deezerFavorites.asStateFlow()
    @Volatile private var deezerFavoriteEntries: List<PlaylistTrackEntry> = emptyList()
    @Volatile private var needsDeezerRefresh = false
    @Volatile private var lastDeezerRefreshAttemptMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

    val downloadStatus: StateFlow<DownloadStatusSnapshot> = combine(
        fullDownloads,
        downloadedTracks
    ) { saved, onDisk ->
        val completedIds = HashSet<String>(saved.size + onDisk.size)
        val activeProgress = HashMap<String, Int?>()
        val failedErrors = HashMap<String, String>()
        val unavailableIds = HashSet<String>()
        val playlistCompletedIds = HashSet<String>()

        onDisk.forEach { track ->
            val norm = DownloadStorage.normalizeTrackId(track.id)
            if (norm.isNotBlank()) completedIds.add(norm)
        }
        saved.forEach { task ->
            val norm = DownloadStorage.normalizeTrackId(task.track.id)
            when (task.state) {
                "completed" -> {
                    if (norm.isNotBlank()) {
                        completedIds.add(norm)
                        failedErrors.remove(norm)
                        unavailableIds.remove(norm)
                    }
                    if (!task.playlistId.isNullOrBlank()) playlistCompletedIds.add(task.playlistId)
                }
                "running" -> if (norm.isNotBlank()) {
                    activeProgress[norm] = task.progress
                    failedErrors.remove(norm)
                    unavailableIds.remove(norm)
                }
                "queued" -> if (norm.isNotBlank()) {
                    activeProgress.putIfAbsent(norm, null)
                    failedErrors.remove(norm)
                    unavailableIds.remove(norm)
                }
                "failed" -> if (norm.isNotBlank() && norm !in completedIds && norm !in activeProgress) {
                    val err = task.error ?: "İndirilemedi"
                    if (err.contains("MP3_128", ignoreCase = true) ||
                        err.contains("kullanılamıyor", ignoreCase = true) ||
                        err.contains("kaldırıl", ignoreCase = true)
                    ) {
                        unavailableIds.add(norm)
                    } else {
                        failedErrors[norm] = err
                    }
                }
            }
        }
        DownloadStatusSnapshot(
            downloadedTrackIds = completedIds,
            activeTrackProgress = activeProgress,
            failedTrackErrors = failedErrors,
            unavailableTrackIds = unavailableIds,
            downloadedPlaylistIds = playlistCompletedIds
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadStatusSnapshot())

    val allKnownTracks: StateFlow<List<FfiTrack>> = combine(
        _recentTracks,
        _deezerFavorites,
        _favorites,
        _downloadedTracks
    ) { recent, deezerFavs, favs, downloaded ->
        (recent + deezerFavs + favs + downloaded).distinctBy { it.id }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Contextual Long-press state
    private val _selectedTrackForMenu = MutableStateFlow<FfiTrack?>(null)
    val selectedTrackForMenu: StateFlow<FfiTrack?> = _selectedTrackForMenu.asStateFlow()
    private val _trackMenuContext = MutableStateFlow<TrackMenuContext?>(null)
    val trackMenuContext: StateFlow<TrackMenuContext?> = _trackMenuContext.asStateFlow()
    private val _playlistRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val playlistRevisions = _playlistRevisions.asStateFlow()
    private fun invalidatePlaylist(id: String) {
        synchronized(playlistCacheLock) {
            playlistCacheVersions[id] = (playlistCacheVersions[id] ?: 0L) + 1L
            localCoverCache.remove(id)
            playlistTracksMemCache.remove(id)
            playlistCachePrefs.edit().remove("pl:$id").apply()
            runCatching { core.deleteCachedPlaylistEntries(id) }
        }
        _playlistRevisions.update { it + (id to ((it[id] ?: 0L) + 1L)) }
    }

    private fun cachePlaylistEntriesIfFresh(
        playlistId: String,
        expectedVersion: Long,
        expectedGeneration: Long?,
        entries: List<PlaylistTrackEntry>,
        persistToDisk: Boolean = true,
        updateDeezerFavorites: Boolean = false
    ): Boolean {
        synchronized(playlistCacheLock) {
            if (playlistCacheVersion(playlistId) != expectedVersion) return false
            if (expectedGeneration != null && deezerGeneration.get() != expectedGeneration) return false
            if (updateDeezerFavorites) {
                deezerFavoriteEntries = entries
                _deezerFavorites.value = entries.map { it.track }
            }
            playlistTracksMemCache[playlistId] = entries
            if (persistToDisk) {
                persistCachedPlaylistEntries(playlistId, entries)
            }
            return true
        }
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
            val tzOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            runCatching { core.setLocalTzOffsetMs(tzOffset) }
            refreshPrivateSessionStatus()
        }
        registerNetworkRecoveryCallback()
        var trackRecordJob: Job? = null
        viewModelScope.launch {
            playbackManager.currentTrackId.collect { id ->
                trackRecordJob?.cancel()
                if (id != null) {
                    trackRecordJob = viewModelScope.launch {
                        delay(1_500L)
                        val ui = playbackManager.uiState.value
                        val track = ui.currentEntry?.track ?: return@launch
                        if (track.id != id) return@launch
                        recordTrackPlay(track)
                        val snap = ui.queueSnapshot
                        val qTracks = snap?.entries?.map { it.track }.orEmpty().ifEmpty { listOf(track) }
                        val qIdx = snap?.currentIndex?.toInt() ?: 0
                        saveResumePlaybackSnapshot(
                            track = track,
                            positionMs = playbackManager.progress.value.positionMs,
                            durationMs = playbackManager.progress.value.durationMs,
                            queueTracks = qTracks,
                            queueIndex = qIdx
                        )
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            var tickCounter = 0
            var pendingTrack: FfiTrack? = null
            var pendingMs = 0L
            var lastTrackPosMs = 0L
            var lastTrackDurMs = 0L
            while (isActive) {
                delay(1000L)
                val ui = playbackManager.uiState.value
                val prog = playbackManager.progress.value
                val curTrack = ui.currentEntry?.track
                if (ui.isPlaying && curTrack != null) {
                    if (pendingTrack != null && pendingTrack.id != curTrack.id) {
                        if (pendingMs > 0L) {
                            recordActiveListeningTick(pendingTrack, pendingMs)
                            pendingMs = 0L
                        }
                        if (lastTrackDurMs > 0L && lastTrackPosMs >= 5_000L) {
                            val completed = lastTrackPosMs >= (lastTrackDurMs * 3L) / 4L ||
                                (lastTrackDurMs - lastTrackPosMs) <= 15_000L
                            runCatching {
                                core.recordTrackOutcome(
                                    completed = completed,
                                    collectStats = _collectListeningStatsEnabled.value,
                                    timestampMs = System.currentTimeMillis().toULong()
                                )
                            }
                        }
                    }
                    pendingTrack = curTrack
                    lastTrackPosMs = prog.positionMs
                    lastTrackDurMs = prog.durationMs.takeIf { it > 0L } ?: curTrack.durationMs.toLong()
                    pendingMs += 1000L
                    tickCounter++
                    if (pendingMs >= 10_000L) {
                        recordActiveListeningTick(curTrack, pendingMs)
                        pendingMs = 0L
                    }
                    if (tickCounter % 5 == 0) {
                        val snap = ui.queueSnapshot
                        val qTracks = snap?.entries?.map { it.track }.orEmpty().ifEmpty { listOf(curTrack) }
                        val qIdx = snap?.currentIndex?.toInt() ?: 0
                        val prog = playbackManager.progress.value
                        saveResumePlaybackSnapshot(
                            track = curTrack,
                            positionMs = prog.positionMs,
                            durationMs = prog.durationMs,
                            queueTracks = qTracks,
                            queueIndex = qIdx
                        )
                    }
                } else if (pendingTrack != null && pendingMs > 0L) {
                    recordActiveListeningTick(pendingTrack, pendingMs)
                    pendingMs = 0L
                    pendingTrack = null
                    val pausedTrack = curTrack ?: ui.currentEntry?.track
                    if (pausedTrack != null) {
                        val snap = ui.queueSnapshot
                        val qTracks = snap?.entries?.map { it.track }.orEmpty().ifEmpty { listOf(pausedTrack) }
                        val qIdx = snap?.currentIndex?.toInt() ?: 0
                        val prog = playbackManager.progress.value
                        saveResumePlaybackSnapshot(
                            track = pausedTrack,
                            positionMs = prog.positionMs,
                            durationMs = prog.durationMs,
                            queueTracks = qTracks,
                            queueIndex = qIdx
                        )
                    }
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
                if (startupHydrated && _resumePlaybackState.value == null && it.isNotEmpty()) {
                    val first = it.first()
                    val dur = first.durationMs.toLong().takeIf { d -> d > 0L } ?: 210_000L
                    saveResumePlaybackSnapshot(first, (dur * 0.25).toLong(), dur, it.take(20), 0)
                }
            }
        }
        loadData()
    }

    private fun hydrateStartupLocalState() {
        try {
            val migrated = migrateLegacyPrefsToRustOnce()
            if (migrated) {
                runCatching { core.getSettings() }.getOrNull()?.let { s ->
                    _settings.value = s
                    _highRefreshRate.value = s.highRefreshRate
                    _dynamicColorEnabled.value = s.dynamicColorEnabled
                    runCatching { SeekbarStyle.valueOf(s.seekbarStyle) }.getOrNull()?.let { _seekbarStyle.value = it }
                    runCatching { AlbumCoverFrame.valueOf(s.albumFrame) }.getOrNull()?.let { _albumFrame.value = it }
                    _swipeToSkipEnabled.value = s.swipeToSkip
                    _flowHeroCardEnabled.value = s.flowHeroCardEnabled
                    _flowQuickAccessPinned.value = s.flowQuickAccessPinned
                    _flowLibraryPinned.value = s.flowLibraryPinned
                    _downloadsQuickAccessPinned.value = s.downloadsQuickAccessPinned
                    _downloadsLibraryPinned.value = s.downloadsLibraryPinned
                    runCatching { HeaderLogoStyle.valueOf(s.headerLogoStyle) }.getOrNull()?.let { _headerLogoStyle.value = it }
                    _homeFeedTypes.value = parseHomeFeedTypes(s.homeFeedType)
                    _resumePlaybackEnabled.value = s.resumePlaybackEnabled
                    _collectListeningStatsEnabled.value = s.collectListeningStats
                    _collectListeningHistoryEnabled.value = s.collectListeningHistory
                    _saveResumePositionEnabled.value = s.saveResumePosition
                    _homeEditHoldSeconds.value = s.homeEditHoldSeconds.toInt().coerceIn(3, 10)
                }
                val layout = runCatching { core.getHomeLayout() }.getOrDefault(emptyList())
                _homeLayout.value = layout
                _enabledHomeWidgets.value = computeEnabledWidgets(layout, _settings.value.enabledHomeWidgets)
                runCatching { core.getCurrentTheme() }.getOrNull()?.let { _activeTheme.value = it }
            }

            val selectedIcon = LauncherIconManager.getSelectedIcon(getApplication())
            _launcherIcon.value = selectedIcon
            LauncherIconManager.syncLauncherIcon(getApplication())

            _userCustomThemes.value = runCatching { core.listUserCustomThemes().map { it.toUi() } }.getOrDefault(emptyList())
            _homeWidgetCatalog.value = runCatching { core.getHomeWidgetCatalog() }.getOrDefault(emptyList())

            val recent = runCatching { core.getRecentTracks(100u) }.getOrDefault(emptyList())
            _recentTracks.value = recent
            _mostPlayedTracks.value = runCatching { core.getMostPlayedTracks(100u) }.getOrDefault(emptyList())
            _trackListeningStats.value = runCatching { core.getListeningStats(300u).map { it.toUi() } }.getOrDefault(emptyList())
            _totalListeningTimeMs.value = runCatching { core.getTotalListeningTimeMs().toLong() }.getOrDefault(0L)
            _resumePlaybackState.value = runCatching { core.getResumePlaybackState()?.toUi() }.getOrNull() ?: run {
                val first = recent.firstOrNull()
                if (first != null) {
                    val dur = first.durationMs.toLong().takeIf { it > 0L } ?: 210_000L
                    ResumePlaybackState(
                        track = first,
                        positionMs = (dur * 0.35).toLong(),
                        durationMs = dur,
                        updatedAtMs = System.currentTimeMillis(),
                        queueTracks = recent.take(20),
                        queueIndex = 0
                    )
                } else null
            }

            val cachedPlaylists = loadCachedDeezerPlaylists()
            if (cachedPlaylists.isNotEmpty() && _deezerPlaylists.value.isEmpty()) {
                _deezerPlaylists.value = cachedPlaylists
            }
        } finally {
            startupHydrated = true
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            hydrateStartupLocalState()
            warmupPlaylistCacheFromDisk()
            try { core.clearDeezerUser() } catch (_: Exception) { }
            try { fullDownloadManager.recover() } catch (_: Exception) { }
            refreshThemes()
            refreshMods()
            refreshPlaylists()
            refreshFavorites()
            refreshLocalTracks()
            refreshDownloads()
            loadQuickArtists(12, emptyList())
            if (!deezerSession.hasSavedSession) {
                try {
                    DeezerWebCookies.readArl()?.let { connectDeezerArl(it) }
                } catch (_: Exception) { }
            }
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
        val customList = runCatching { core.listUserCustomThemes().map { it.toUi() } }.getOrDefault(emptyList())
        _userCustomThemes.value = customList
    }

    fun saveUserCustomTheme(spec: CustomThemeSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val applied = core.saveUserCustomTheme(spec.toFfi())
                _userCustomThemes.value = core.listUserCustomThemes().map { it.toUi() }
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
                val updatedTheme = core.deleteUserCustomTheme(themeId)
                _userCustomThemes.value = core.listUserCustomThemes().map { it.toUi() }
                _activeTheme.value = updatedTheme
                _settings.value = core.getSettings()
                _availableThemes.value = core.listThemes()
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
                    try {
                        val updated = deezerSession.playlists()
                        _deezerPlaylists.value = updated
                        persistDeezerPlaylistsCache(updated)
                    } catch (_: Exception) { }
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
                    try {
                        val updated = deezerSession.playlists()
                        _deezerPlaylists.value = updated
                        persistDeezerPlaylistsCache(updated)
                    } catch (_: Exception) { }
                } else { core.deletePlaylist(playlist.id); refreshPlaylists() }
                pinnedItems.remove("playlist:${playlist.id}")
                invalidatePlaylist(playlist.id)
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
        needsDeezerRefresh = false
        playbackManager.stopPlayback()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        _deezerUser.value = null
        _deezerLibraryLoading.value = false
        _deezerPlaylists.value = emptyList()
        _deezerFavorites.value = emptyList()
        deezerFavoriteEntries = emptyList()
        _deezerFlow.value = emptyList()
        playlistTracksMemCache.remove(DEEZER_FAVORITES_ID)
        playlistCachePrefs.edit().remove("deezer:playlists").apply()
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
        lastDeezerRefreshAttemptMs = SystemClock.elapsedRealtime()
        _deezerLibraryLoading.value = deezerSession.hasSavedSession
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = deezerSession.restore()
                if (generation != deezerGeneration.get()) return@launch
                if (user != null) {
                    _deezerUser.value = user
                    loadDeezerLibrary(generation)
                } else {
                    _deezerUser.value = null
                    needsDeezerRefresh = false
                }
            } catch (e: Exception) {
                if (generation == deezerGeneration.get()) {
                    val msg = (e as? DeezerException)?.message.orEmpty()
                    val isInvalidSession = msg.contains("geçersiz veya süresi dolmuş", ignoreCase = true) ||
                        msg.contains("yeniden giriş yap", ignoreCase = true)
                    if (isInvalidSession) {
                        _deezerUser.value = null
                        needsDeezerRefresh = false
                        _statusMessage.value = "Deezer oturumu açılamadı: $msg"
                    } else {
                        needsDeezerRefresh = true
                        if (_deezerPlaylists.value.isEmpty() && _deezerFavorites.value.isEmpty()) {
                            _statusMessage.value = "Deezer oturumu açılamadı: ${msg.ifBlank { "Bağlantıyı kontrol et" }}"
                        }
                    }
                }
            } finally {
                if (generation == deezerGeneration.get()) _deezerLibraryLoading.value = false
            }
        }
    }

    private fun loadDeezerLibrary(generation: Long) {
        var loadedPlaylists: List<LibraryPlaylistEntry> = emptyList()
        var playlistsOk = false
        var favoritesOk = false
        try {
            val playlists = deezerSession.playlists()
            if (generation == deezerGeneration.get()) {
                _deezerPlaylists.value = playlists
                persistDeezerPlaylistsCache(playlists)
                loadedPlaylists = playlists
                playlistsOk = true
            }
        } catch (_: Exception) {
            if (generation == deezerGeneration.get() && _deezerPlaylists.value.isEmpty()) {
                _statusMessage.value = "Deezer çalma listeleri yüklenemedi"
            }
        }
        if (generation != deezerGeneration.get()) return
        val favStartVersion = playlistCacheVersion(DEEZER_FAVORITES_ID)
        try {
            val entries = deezerSession.favoriteTrackEntries()
            if (generation == deezerGeneration.get()) {
                cachePlaylistEntriesIfFresh(
                    playlistId = DEEZER_FAVORITES_ID,
                    expectedVersion = favStartVersion,
                    expectedGeneration = generation,
                    entries = entries,
                    updateDeezerFavorites = true
                )
                favoritesOk = true
            }
        } catch (_: Exception) {
            if (generation == deezerGeneration.get() && _deezerFavorites.value.isEmpty()) {
                _statusMessage.value = "Deezer favorileri yüklenemedi"
            }
        }
        if (generation == deezerGeneration.get()) {
            needsDeezerRefresh = !(playlistsOk && favoritesOk)
        }
        // Background bounded prefetch of playlists so opening any playlist is instant without starving playback
        if (loadedPlaylists.isNotEmpty() && generation == deezerGeneration.get()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val gate = Semaphore(2)
                    coroutineScope {
                        loadedPlaylists.take(12).map { entry ->
                            async {
                                val pid = entry.playlist.id
                                val needsPrefetch = synchronized(playlistCacheLock) { !playlistTracksMemCache.containsKey(pid) }
                                if (needsPrefetch) {
                                    gate.withPermit {
                                        val startVersion = playlistCacheVersion(pid)
                                        val stillMissing = synchronized(playlistCacheLock) { !playlistTracksMemCache.containsKey(pid) }
                                        if (stillMissing && generation == deezerGeneration.get()) {
                                            runCatching {
                                                val fetched = deezerSession.playlistTrackEntries(pid)
                                                if (fetched.isNotEmpty()) {
                                                    cachePlaylistEntriesIfFresh(pid, startVersion, generation, fetched)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun registerNetworkRecoveryCallback() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!deezerSession.hasSavedSession) return
                val missingData = _deezerUser.value == null || _deezerPlaylists.value.isEmpty() || _deezerFavorites.value.isEmpty()
                if (!needsDeezerRefresh && !missingData) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastDeezerRefreshAttemptMs < 5_000L) return
                refreshDeezerData()
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    fun setCategory(category: SearchCategory) {
        _searchCategory.value = category
    }

    fun search(query: String, category: SearchCategory = _searchCategory.value, immediate: Boolean = false) {
        _searchQuery.value = query
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

    fun downloadFullTracks(tracks: List<FfiTrack>, playlistId: String? = null, playlistName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = fullDownloadManager.enqueueAll(
                    tracks = tracks,
                    quality = downloadQuality.value,
                    playlistId = playlistId,
                    playlistName = playlistName
                )
                val prefix = if (!playlistName.isNullOrBlank()) "\"$playlistName\": " else ""
                _statusMessage.value = if (result.added > 0) "${prefix}${result.added} şarkı indirme sırasına eklendi" +
                    if (result.skipped > 0) " • ${result.skipped} zaten cihazda mevcut" else ""
                    else if (result.skipped > 0) "${prefix}Seçilen şarkıların tümü (${result.skipped}) zaten indirildi veya cihazda mevcut"
                    else "İndirilecek şarkı bulunamadı"
            } catch (_: Exception) { _statusMessage.value = "İndirme başlatılamadı" }
        }
    }

    fun playFullDownload(task: SavedDownload) {
        val uri = task.uri ?: return
        val track = task.track.copy(id = "local:download-${task.id}", provider = "local", playbackUri = uri, isPreview = false)
        playbackManager.playTrackList(listOf(track))
    }

    fun playDownloadedPlaylist(tasks: List<SavedDownload>, startIndex: Int = 0) {
        val tracks = tasks.mapNotNull { task ->
            val uri = task.uri ?: return@mapNotNull null
            task.track.copy(id = "local:download-${task.id}", provider = "local", playbackUri = uri, isPreview = false)
        }
        if (tracks.isEmpty()) return
        playbackManager.playTrackList(tracks, startIndex.coerceIn(0, tracks.lastIndex))
    }

    fun retryFullDownload(task: SavedDownload) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (fullDownloadManager.enqueueAll(
                        tracks = listOf(task.track),
                        quality = task.quality,
                        playlistId = task.playlistId,
                        playlistName = task.playlistName
                    ).added > 0
                ) {
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

    private fun loadCachedDeezerPlaylists(): List<LibraryPlaylistEntry> {
        if (!deezerSession.hasSavedSession) return emptyList()
        val raw = playlistCachePrefs.getString("deezer:playlists", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val pl = FfiPlaylist(
                    id = id,
                    name = name,
                    description = obj.optString("description").takeIf { it.isNotBlank() && it != "null" },
                    isLocal = obj.optBoolean("isLocal", false),
                    trackCount = obj.optLong("trackCount", 0L).coerceAtLeast(0L).toUInt(),
                    artworkUrl = obj.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
                    origin = obj.optString("origin", "real")
                )
                LibraryPlaylistEntry(
                    playlist = pl,
                    addedAt = if (obj.has("addedAt") && !obj.isNull("addedAt")) obj.optLong("addedAt") else null,
                    modifiedAt = if (obj.has("modifiedAt") && !obj.isNull("modifiedAt")) obj.optLong("modifiedAt") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persistDeezerPlaylistsCache(entries: List<LibraryPlaylistEntry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { entry ->
                val p = entry.playlist
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("description", p.description)
                        .put("isLocal", p.isLocal)
                        .put("trackCount", p.trackCount.toLong())
                        .put("artworkUrl", p.artworkUrl)
                        .put("origin", p.origin)
                        .put("addedAt", entry.addedAt)
                        .put("modifiedAt", entry.modifiedAt)
                )
            }
            playlistCachePrefs.edit().putString("deezer:playlists", arr.toString()).apply()
        }
    }

    private fun warmupPlaylistCacheFromDisk() {
        try {
            val cachedFromRust = core.getAllCachedPlaylistEntries()
            synchronized(playlistCacheLock) {
                for ((playlistId, entries) in cachedFromRust) {
                    if (entries.isNotEmpty() && playlistCacheVersion(playlistId) == 0L) {
                        playlistTracksMemCache.putIfAbsent(playlistId, entries.map { it.toUi() })
                    }
                }
                // Also migrate any legacy SharedPreferences playlist cache into Rust SQLite once
                val migratedLegacyKeys = mutableListOf<String>()
                for ((key, raw) in playlistCachePrefs.all) {
                    if (!key.startsWith("pl:") || raw !is String) continue
                    val playlistId = key.removePrefix("pl:")
                    if (playlistTracksMemCache.containsKey(playlistId) || playlistCacheVersion(playlistId) != 0L) {
                        migratedLegacyKeys.add(key)
                        continue
                    }
                    val arr = JSONArray(raw)
                    val entries = (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val obj = arr.getJSONObject(i)
                            PlaylistTrackEntry(
                                track = jsonTrack(obj.getJSONObject("track")),
                                addedAt = if (obj.has("addedAt") && !obj.isNull("addedAt")) obj.getLong("addedAt") else null,
                                originalIndex = obj.optInt("originalIndex", i)
                            )
                        }.getOrNull()
                    }
                    if (entries.isNotEmpty()) {
                        playlistTracksMemCache[playlistId] = entries
                        val persisted = runCatching { core.setCachedPlaylistEntries(playlistId, entries.map { it.toFfi() }) }
                        if (persisted.isSuccess) {
                            migratedLegacyKeys.add(key)
                        }
                    } else {
                        migratedLegacyKeys.add(key)
                    }
                }
                if (migratedLegacyKeys.isNotEmpty()) {
                    playlistCachePrefs.edit().apply {
                        migratedLegacyKeys.forEach { remove(it) }
                    }.apply()
                }
                if (deezerSession.hasSavedSession) {
                    if (_deezerUser.value == null) {
                        _deezerUser.value = deezerSession.cachedUser()
                    }
                    if (_deezerPlaylists.value.isEmpty()) {
                        val cachedPls = loadCachedDeezerPlaylists()
                        if (cachedPls.isNotEmpty()) _deezerPlaylists.value = cachedPls
                    }
                    val cachedFavs = playlistTracksMemCache[DEEZER_FAVORITES_ID]
                    if (!cachedFavs.isNullOrEmpty() && _deezerFavorites.value.isEmpty() && playlistCacheVersion(DEEZER_FAVORITES_ID) == 0L) {
                        deezerFavoriteEntries = cachedFavs
                        _deezerFavorites.value = cachedFavs.map { it.track }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistCachedPlaylistEntries(playlistId: String, entries: List<PlaylistTrackEntry>) {
        try {
            core.setCachedPlaylistEntries(playlistId, entries.take(300).map { it.toFfi() })
        } catch (_: Exception) {}
    }

    fun getCachedPlaylistTrackEntries(playlistId: String): List<PlaylistTrackEntry>? {
        return when (playlistId) {
            DEEZER_FAVORITES_ID -> deezerFavoriteEntries.takeIf { it.isNotEmpty() } ?: playlistTracksMemCache[playlistId]
            LOCAL_DOWNLOADS_ID -> _downloadedTracks.value.takeIf { it.isNotEmpty() }
                ?.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
            LOCAL_FAVORITES_ID -> _favorites.value.takeIf { it.isNotEmpty() }
                ?.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
            else -> playlistTracksMemCache[playlistId]
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<FfiTrack> =
        getPlaylistTrackEntries(playlistId).map { it.track }

    suspend fun getPlaylistTrackEntries(playlistId: String): List<PlaylistTrackEntry> = withContext(Dispatchers.IO) {
        if (playlistId == DEEZER_FAVORITES_ID) {
            val cached = synchronized(playlistCacheLock) {
                deezerFavoriteEntries.takeIf { it.isNotEmpty() } ?: playlistTracksMemCache[playlistId]
            }
            if (cached != null) {
                val startVersion = playlistCacheVersion(playlistId)
                val generation = deezerGeneration.get()
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val fresh = deezerSession.favoriteTrackEntries()
                        if (fresh.isNotEmpty()) {
                            val changed = fresh.map { it.track.id } != cached.map { it.track.id }
                            val needsPersist = synchronized(playlistCacheLock) { !playlistTracksMemCache.containsKey(playlistId) }
                            if (changed || needsPersist) {
                                if (cachePlaylistEntriesIfFresh(
                                        playlistId = playlistId,
                                        expectedVersion = startVersion,
                                        expectedGeneration = generation,
                                        entries = fresh,
                                        updateDeezerFavorites = true
                                    ) && changed
                                ) {
                                    _playlistRevisions.update { it + (playlistId to ((it[playlistId] ?: 0L) + 1L)) }
                                }
                            }
                        }
                    }
                }
                return@withContext cached
            }
            val startVersion = playlistCacheVersion(playlistId)
            val generation = deezerGeneration.get()
            val fetched = deezerSession.favoriteTrackEntries()
            if (cachePlaylistEntriesIfFresh(
                    playlistId = playlistId,
                    expectedVersion = startVersion,
                    expectedGeneration = generation,
                    entries = fetched,
                    updateDeezerFavorites = true
                )
            ) {
                fetched
            } else {
                currentCoroutineContext().ensureActive()
                synchronized(playlistCacheLock) {
                    deezerFavoriteEntries.takeIf { it.isNotEmpty() } ?: playlistTracksMemCache[playlistId]
                } ?: run {
                    val latestVersion = playlistCacheVersion(playlistId)
                    val latestGeneration = deezerGeneration.get()
                    deezerSession.favoriteTrackEntries().also { latest ->
                        cachePlaylistEntriesIfFresh(
                            playlistId = playlistId,
                            expectedVersion = latestVersion,
                            expectedGeneration = latestGeneration,
                            entries = latest,
                            updateDeezerFavorites = true
                        )
                    }
                }
            }
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
            val cached = playlistTracksMemCache[playlistId]
            if (cached != null) {
                val startVersion = playlistCacheVersion(playlistId)
                val generation = deezerGeneration.get()
                // Refresh quietly in the background without blocking UI
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val fresh = deezerSession.playlistTrackEntries(playlistId)
                        if (fresh.isNotEmpty() && fresh.map { it.track.id } != cached.map { it.track.id }) {
                            if (cachePlaylistEntriesIfFresh(playlistId, startVersion, generation, fresh)) {
                                _playlistRevisions.update { it + (playlistId to ((it[playlistId] ?: 0L) + 1L)) }
                            }
                        }
                    }
                }
                return@withContext cached
            }
            val startVersion = playlistCacheVersion(playlistId)
            val generation = deezerGeneration.get()
            val fetched = deezerSession.playlistTrackEntries(playlistId)
            if (cachePlaylistEntriesIfFresh(playlistId, startVersion, generation, fetched)) {
                fetched
            } else {
                currentCoroutineContext().ensureActive()
                playlistTracksMemCache[playlistId] ?: run {
                    val latestVersion = playlistCacheVersion(playlistId)
                    val latestGeneration = deezerGeneration.get()
                    deezerSession.playlistTrackEntries(playlistId).also { latest ->
                        cachePlaylistEntriesIfFresh(playlistId, latestVersion, latestGeneration, latest)
                    }
                }
            }
        } else {
            val startVersion = playlistCacheVersion(playlistId)
            val tracks = if (playlistId == LOCAL_FAVORITES_ID) core.getFavorites() else core.getPlaylistTracks(playlistId)
            val entries = tracks.mapIndexed { index, track -> PlaylistTrackEntry(track, addedAt = null, originalIndex = index) }
            cachePlaylistEntriesIfFresh(
                playlistId = playlistId,
                expectedVersion = startVersion,
                expectedGeneration = null,
                entries = entries,
                persistToDisk = false
            )
            entries
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
                        synchronized(playlistCacheLock) {
                            val entries = if (isFav) listOf(PlaylistTrackEntry(track, Instant.now().epochSecond, 0)) + deezerFavoriteEntries
                                else deezerFavoriteEntries.filterNot { it.track.id == track.id }
                            deezerFavoriteEntries = entries.mapIndexed { index, entry -> entry.copy(originalIndex = index) }
                            _deezerFavorites.value = deezerFavoriteEntries.map { it.track }
                            invalidatePlaylist(DEEZER_FAVORITES_ID)
                        }
                    } else {
                        isFav = core.toggleFavorite(track)
                        refreshFavorites()
                        invalidatePlaylist(LOCAL_FAVORITES_ID)
                    }
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
                if (playlistId.startsWith("deezer:")) try {
                    val updated = deezerSession.playlists()
                    _deezerPlaylists.value = updated
                    persistDeezerPlaylistsCache(updated)
                } catch (_: Exception) { }
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
                if (menu.playlist.id.startsWith("deezer:")) try {
                    val updated = deezerSession.playlists()
                    _deezerPlaylists.value = updated
                    persistDeezerPlaylistsCache(updated)
                } catch (_: Exception) { }
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
                                artworkUri = org.kde.plasmawave.ui.components.AudioArtFetcher.uriFor(contentUri),
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

    override fun onCleared() {
        networkCallback?.let { cb ->
            runCatching {
                getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        jamManager.release()
        super.onCleared()
    }
}
