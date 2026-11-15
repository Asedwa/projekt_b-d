package org.kde.plasmawave

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kde.plasmawave.player.summaryLabel
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.PlasmaTheme
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.SearchCategory
import org.kde.plasmawave.jam.JamMode
import org.kde.plasmawave.ui.components.FullPlayerSheet
import org.kde.plasmawave.ui.components.JamSheet
import org.kde.plasmawave.ui.components.LocalDeezerConnected
import org.kde.plasmawave.ui.components.MiniPlayer
import org.kde.plasmawave.ui.components.PlaylistPicker
import org.kde.plasmawave.ui.components.SleepTimerDialog
import org.kde.plasmawave.ui.components.SongLongPressBottomSheet
import org.kde.plasmawave.ui.screens.*
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

sealed class Screen(val title: String, val icon: ImageVector) {
    data object Home : Screen("Ana sayfa", Icons.Default.Home)
    data object Library : Screen("Kitaplık", Icons.Default.LibraryMusic)
    data object Search : Screen("Ara", Icons.Default.Search)
    data object Queue : Screen("Sıra", Icons.AutoMirrored.Filled.QueueMusic)
    data object Settings : Screen("Ayarlar", Icons.Default.Settings)
}
sealed class SubScreen {
    data object None : SubScreen()
    class PlaylistDetail(val playlist: FfiPlaylist) : SubScreen()
    data object Themes : SubScreen()
    data object Customization : SubScreen()
    data object ModManager : SubScreen()
    data object Downloads : SubScreen()
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private fun handleJamIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "plasmawave" && uri.host == "jam") {
            val code = uri.lastPathSegment?.trim()?.uppercase().orEmpty()
            if (code.length >= 4) {
                viewModel.jamManager.joinJamByCode(code)
                Toast.makeText(this, "Jam #$code oturumuna bağlanılıyor...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJamIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleJamIntent(intent)
        setContent {
            val activeTheme by viewModel.activeTheme.collectAsState()
            val hasCurrentEntry by remember {
                viewModel.playbackManager.overview
                    .map { it.currentEntry != null }
                    .distinctUntilChanged()
            }.collectAsState(initial = false)
            val deezerUser by viewModel.deezerUser.collectAsState()
            val statusMessage by viewModel.statusMessage.collectAsState()
            val highRefresh by viewModel.highRefreshRate.collectAsState()

            var trackForPlaylist by remember { mutableStateOf<FfiTrack?>(null) }
            var showSleepTimerDialog by remember { mutableStateOf(false) }
            var requestedDownloads by remember { mutableStateOf<List<FfiTrack>?>(null) }
            val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val request = requestedDownloads
                if (granted && request != null) viewModel.downloadFullTracks(request)
                else if (!granted) Toast.makeText(this@MainActivity, "Şarkıları kaydetmek için depolama izni gerekli", Toast.LENGTH_LONG).show()
                requestedDownloads = null
            }
            val requestDownload: (List<FfiTrack>) -> Unit = { tracks ->
                if (deezerUser == null) Toast.makeText(this@MainActivity, "Şarkıları indirmek için Deezer'a giriş yap", Toast.LENGTH_LONG).show()
                else if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestedDownloads = tracks
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else viewModel.downloadFullTracks(tracks)
            }

            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
            var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
            val externalSearchTrigger by viewModel.externalSearchTrigger.collectAsState()
            val tabStateHolder = rememberSaveableStateHolder()
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            val fullPlayerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val menuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()

            LaunchedEffect(externalSearchTrigger) {
                if (externalSearchTrigger != null) {
                    isFullPlayerVisible = false
                    currentSubScreen = SubScreen.None
                    currentScreen = Screen.Search
                }
            }
            LaunchedEffect(highRefresh) { applyRefreshRate(highRefresh) }
            BackHandler(enabled = currentSubScreen != SubScreen.None && !isFullPlayerVisible) {
                currentSubScreen = SubScreen.None
            }
            LaunchedEffect(statusMessage) {
                statusMessage?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); viewModel.clearStatusMessage() }
            }

            PlasmaTheme(activeTheme = activeTheme) {
                CompositionLocalProvider(LocalDeezerConnected provides (deezerUser != null)) {
                    val colors = LocalPlasmaColors.current
                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                    val contentArea: @Composable () -> Unit = {
                        when (val sub = currentSubScreen) {
                            is SubScreen.PlaylistDetail -> PlaylistDetailScreen(
                                sub.playlist, viewModel,
                                onBack = { currentSubScreen = SubScreen.None },
                                onDownloadPlaylist = requestDownload
                            )
                            SubScreen.Themes -> ThemesScreen(viewModel, onBack = { currentSubScreen = SubScreen.None })
                            SubScreen.Customization -> CustomizationScreen(viewModel, onBack = { currentSubScreen = SubScreen.None })
                            SubScreen.ModManager -> ModManagerScreen(viewModel, onBack = { currentSubScreen = SubScreen.None })
                            SubScreen.Downloads -> DownloadsScreen(viewModel, onBack = { currentSubScreen = SubScreen.None })
                            SubScreen.None -> tabStateHolder.SaveableStateProvider(currentScreen.title) {
                                when (currentScreen) {
                                    Screen.Home -> HomeScreen(
                                        viewModel,
                                        onPlaylistClick = { currentSubScreen = SubScreen.PlaylistDetail(it) },
                                        onDownloadsClick = { currentSubScreen = SubScreen.Downloads }
                                    )
                                    Screen.Library -> LibraryScreen(viewModel, onPlaylistClick = { currentSubScreen = SubScreen.PlaylistDetail(it) })
                                    Screen.Search -> SearchScreen(viewModel, onPlaylistClick = { currentSubScreen = SubScreen.PlaylistDetail(it) })
                                    Screen.Queue -> QueueScreen(playbackManager = viewModel.playbackManager, onTrackMenu = { viewModel.openTrackMenu(it) })
                                    Screen.Settings -> SettingsScreen(
                                        viewModel,
                                        onNavigateThemes = { currentSubScreen = SubScreen.Themes },
                                        onNavigateMods = { currentSubScreen = SubScreen.ModManager },
                                        onNavigateDownloads = { currentSubScreen = SubScreen.Downloads },
                                        onNavigateCustomization = { currentSubScreen = SubScreen.Customization }
                                    )
                                }
                            }
                        }
                    }

                    if (isLandscape) {
                        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavigationRail(
                                containerColor = colors.card,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                Spacer(Modifier.height(8.dp))
                                listOf(Screen.Home, Screen.Library, Screen.Search, Screen.Queue, Screen.Settings).forEach { screen ->
                                    NavigationRailItem(
                                        selected = currentScreen == screen && currentSubScreen == SubScreen.None,
                                        onClick = { currentScreen = screen; currentSubScreen = SubScreen.None },
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title, maxLines = 1) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = colors.textSecondary,
                                            unselectedTextColor = colors.textSecondary,
                                            indicatorColor = colors.border
                                        )
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    contentArea()
                                }
                                AnimatedVisibility(
                                    visible = hasCurrentEntry,
                                    enter = slideInVertically(initialOffsetY = { it }),
                                    exit = slideOutVertically(targetOffsetY = { it })
                                ) {
                                    LiveMiniPlayer(viewModel, onClick = { isFullPlayerVisible = true })
                                }
                            }
                        }
                    } else {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                Column {
                                    AnimatedVisibility(
                                        visible = hasCurrentEntry,
                                        enter = slideInVertically(initialOffsetY = { it }),
                                        exit = slideOutVertically(targetOffsetY = { it })
                                    ) {
                                        LiveMiniPlayer(viewModel, onClick = { isFullPlayerVisible = true })
                                    }
                                    NavigationBar(containerColor = colors.card, tonalElevation = 0.dp) {
                                        listOf(Screen.Home, Screen.Library, Screen.Search, Screen.Queue, Screen.Settings).forEach { screen ->
                                            NavigationBarItem(
                                                selected = currentScreen == screen && currentSubScreen == SubScreen.None,
                                                onClick = { currentScreen = screen; currentSubScreen = SubScreen.None },
                                                icon = { Icon(screen.icon, contentDescription = screen.title) },
                                                label = { Text(screen.title, maxLines = 1) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    unselectedIconColor = colors.textSecondary,
                                                    unselectedTextColor = colors.textSecondary,
                                                    indicatorColor = colors.border
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        ) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                contentArea()
                            }
                        }
                    }

                    if (isFullPlayerVisible) {
                        FullPlayerHost(
                            viewModel = viewModel,
                            sheetState = fullPlayerSheetState,
                            onDismiss = {
                                scope.launch {
                                    fullPlayerSheetState.hide()
                                    isFullPlayerVisible = false
                                }
                            },
                            onAddToPlaylist = { trackForPlaylist = it },
                            onOpenMenu = { track ->
                                isFullPlayerVisible = false
                                viewModel.openTrackMenu(track)
                            },
                            onShowSleepTimer = { showSleepTimerDialog = true },
                            onArtistClick = { artistName ->
                                scope.launch {
                                    fullPlayerSheetState.hide()
                                    isFullPlayerVisible = false
                                    currentScreen = Screen.Search
                                    currentSubScreen = SubScreen.None
                                    viewModel.navigateToSearch(artistName, SearchCategory.ARTISTS)
                                }
                            },
                            onAlbumClick = { albumName, _ ->
                                scope.launch {
                                    fullPlayerSheetState.hide()
                                    isFullPlayerVisible = false
                                    currentScreen = Screen.Search
                                    currentSubScreen = SubScreen.None
                                    viewModel.navigateToSearch(albumName, SearchCategory.ALBUMS)
                                }
                            }
                        )
                    }

                    SongMenuHost(
                        viewModel = viewModel,
                        sheetState = menuSheetState,
                        onAddToPlaylist = { trackForPlaylist = it },
                        onRequestDownload = { requestDownload(listOf(it)) },
                        onShowSleepTimer = { showSleepTimerDialog = true }
                    )

                    if (showSleepTimerDialog) {
                        SleepTimerDialogHost(
                            viewModel = viewModel,
                            onDismiss = { showSleepTimerDialog = false }
                        )
                    }

                    trackForPlaylist?.let { track ->
                        PlaylistPickerHost(
                            viewModel = viewModel,
                            track = track,
                            onDismiss = { trackForPlaylist = null }
                        )
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRefreshRate(enabled: Boolean) {
        val screen: Display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val current = screen.mode
        val mode = screen.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= 120.5f }
            .maxByOrNull { it.refreshRate }
        val targetModeId = if (enabled) mode?.modeId ?: 0 else 0
        val targetRefreshRate = if (enabled) mode?.refreshRate ?: 0f else 0f
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != targetModeId || attrs.preferredRefreshRate != targetRefreshRate) {
            attrs.preferredDisplayModeId = targetModeId
            attrs.preferredRefreshRate = targetRefreshRate
            window.attributes = attrs
        }
    }
}

@Composable
private fun JamControlBlockedDialog(
    onLeaveJam: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jam Kontrolü Kapalı") },
        text = {
            Text("Ev sahibi misafirlerin müziği kontrol etmesini kapattı. Müziği kendin değiştirmek için Jam'den çıkmak ister misin?")
        },
        confirmButton = {
            TextButton(onClick = onLeaveJam) {
                Text("Jam'den Çık", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Jam'de Kal")
            }
        }
    )
}

@Composable
private fun LiveMiniPlayer(viewModel: MainViewModel, onClick: () -> Unit) {
    val state by viewModel.playbackManager.overview.collectAsState()
    val jamState by viewModel.jamState.collectAsState()
    var showBlockedDialog by remember { mutableStateOf(false) }

    if (showBlockedDialog) {
        JamControlBlockedDialog(
            onLeaveJam = {
                viewModel.jamManager.stopJam()
                showBlockedDialog = false
            },
            onDismiss = { showBlockedDialog = false }
        )
    }

    MiniPlayer(
        state,
        viewModel.playbackManager.progress,
        onTogglePlayPause = {
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("toggle")
                else showBlockedDialog = true
            } else {
                viewModel.playbackManager.togglePlayPause()
            }
        },
        onSkipNext = {
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("next")
                else showBlockedDialog = true
            } else {
                viewModel.playbackManager.skipNext()
            }
        },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayerHost(
    viewModel: MainViewModel,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onAddToPlaylist: (FfiTrack) -> Unit,
    onOpenMenu: (FfiTrack) -> Unit,
    onShowSleepTimer: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String, String) -> Unit
) {
    val state by viewModel.playbackManager.overview.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsState()
    val seekbarStyle by viewModel.seekbarStyle.collectAsState()
    val albumFrame by viewModel.albumFrame.collectAsState()
    val swipeToSkipEnabled by viewModel.swipeToSkipEnabled.collectAsState()
    val jamState by viewModel.jamState.collectAsState()
    var showJamSheet by remember { mutableStateOf(false) }
    var showJamBlockedDialog by remember { mutableStateOf(false) }

    if (showJamBlockedDialog) {
        JamControlBlockedDialog(
            onLeaveJam = {
                viewModel.jamManager.stopJam()
                showJamBlockedDialog = false
            },
            onDismiss = { showJamBlockedDialog = false }
        )
    }

    var lastTrack by remember { mutableStateOf<FfiTrack?>(null) }
    val nowPlaying = state.currentEntry?.track ?: lastTrack ?: return
    LaunchedEffect(state.currentEntry?.track) {
        state.currentEntry?.track?.let { lastTrack = it }
    }

    val isFav = if (nowPlaying.provider == "deezer" && deezerUser != null) {
        deezerFavorites.any { it.id == nowPlaying.id }
    } else {
        favorites.any { it.id == nowPlaying.id }
    }

    FullPlayerSheet(
        playerState = state,
        progressFlow = viewModel.playbackManager.progress,
        sheetState = sheetState,
        dynamicColorEnabled = dynamicColorEnabled,
        seekbarStyle = seekbarStyle,
        albumFrame = albumFrame,
        swipeToSkipEnabled = swipeToSkipEnabled,
        onDismiss = onDismiss,
        onTogglePlayPause = {
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("toggle")
                else showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.togglePlayPause()
            }
        },
        onSkipNext = {
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("next")
                else showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.skipNext()
            }
        },
        onSkipPrevious = {
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("prev")
                else showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.skipPrevious()
            }
        },
        onSeekTo = { pos ->
            if (jamState.mode == JamMode.GUEST) {
                if (jamState.allowGuestControls) viewModel.jamManager.sendGuestControl("seek", pos)
                else showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.seekTo(pos)
            }
        },
        onToggleShuffle = {
            if (jamState.mode == JamMode.GUEST && !jamState.allowGuestControls) {
                showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.toggleShuffle()
            }
        },
        onToggleRepeat = {
            if (jamState.mode == JamMode.GUEST && !jamState.allowGuestControls) {
                showJamBlockedDialog = true
            } else {
                viewModel.playbackManager.toggleRepeat()
            }
        },
        isFavorite = isFav,
        onToggleFavorite = { viewModel.toggleFavorite(nowPlaying) },
        onAddToPlaylist = { onAddToPlaylist(nowPlaying) },
        onOpenMenu = { onOpenMenu(nowPlaying) },
        onStartMix = { viewModel.startTrackMix(nowPlaying) },
        canStartMix = nowPlaying.provider == "deezer" && deezerUser != null,
        onShowSleepTimer = onShowSleepTimer,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        isJamActive = jamState.mode != JamMode.NONE,
        onOpenJam = { showJamSheet = true }
    )

    if (showJamSheet) {
        JamSheet(
            jamState = jamState,
            localQueueTracks = state.queueSnapshot?.entries?.map { it.track }.orEmpty(),
            onDismiss = { showJamSheet = false },
            onStartHost = { viewModel.jamManager.startHostJam(it) },
            onJoinByCode = { code, name -> viewModel.jamManager.joinJamByCode(code, name) },
            onJoinJam = { addr, code, name -> viewModel.jamManager.joinJam(addr, code, name) },
            onStopJam = { viewModel.jamManager.stopJam() },
            onToggleGuestControls = { viewModel.jamManager.setAllowGuestControls(it) },
            onStartDiscovery = { viewModel.jamManager.startDiscovery() },
            onStopDiscovery = { viewModel.jamManager.stopDiscovery() },
            onAddTrack = { viewModel.jamManager.addTrackToJam(it) },
            onSearchTracks = { viewModel.searchTracksForJam(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongMenuHost(
    viewModel: MainViewModel,
    sheetState: SheetState,
    onAddToPlaylist: (FfiTrack) -> Unit,
    onRequestDownload: (FfiTrack) -> Unit,
    onShowSleepTimer: () -> Unit
) {
    val selectedTrack by viewModel.selectedTrackForMenu.collectAsState()
    val track = selectedTrack ?: return

    val menuContext by viewModel.trackMenuContext.collectAsState()
    val customActions by viewModel.customTrackActions.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val sleepTimerState by viewModel.playbackManager.sleepTimerState.collectAsState()
    val scope = rememberCoroutineScope()

    val isFav = if (track.provider == "deezer" && deezerUser != null) {
        deezerFavorites.any { it.id == track.id }
    } else {
        favorites.any { it.id == track.id }
    }
    val isPinned = pins.any { it.id == "track:${track.id}" }

    SongLongPressBottomSheet(
        track = track,
        sheetState = sheetState,
        onDismiss = {
            scope.launch {
                sheetState.hide()
                viewModel.closeTrackMenu()
            }
        },
        onPlayNext = { viewModel.playbackManager.playNext(listOf(it)) },
        onAddToQueue = { viewModel.playbackManager.addToQueue(listOf(it)) },
        onToggleFavorite = viewModel::toggleFavorite,
        onAddToPlaylist = onAddToPlaylist,
        onDownload = onRequestDownload,
        isFavorite = isFav,
        isPinned = isPinned,
        onTogglePin = viewModel::togglePinnedTrack,
        canDownloadDeezer = deezerUser != null,
        onMix = viewModel::startTrackMix,
        canRemove = menuContext != null,
        onRemove = { t -> menuContext?.let { ctx -> viewModel.removeTrackFromPlaylist(t, ctx) } },
        sleepTimerSummary = sleepTimerState.summaryLabel(),
        onSleepTimer = onShowSleepTimer,
        customModActions = customActions,
        onExecuteModAction = { mod, t -> viewModel.executeModAction(mod.id, t) }
    )
}

@Composable
private fun SleepTimerDialogHost(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val sleepTimerState by viewModel.playbackManager.sleepTimerState.collectAsState()
    SleepTimerDialog(
        sleepTimerState = sleepTimerState,
        onSelectMinutes = viewModel.playbackManager::startSleepTimerMinutes,
        onSelectEndOfTrack = viewModel.playbackManager::startSleepTimerEndOfTrack,
        onCancelTimer = { viewModel.playbackManager.cancelSleepTimer() },
        onDismiss = onDismiss
    )
}

@Composable
private fun PlaylistPickerHost(
    viewModel: MainViewModel,
    track: FfiTrack,
    onDismiss: () -> Unit
) {
    val localPlaylists by viewModel.playlists.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val allPlaylists = remember(deezerPlaylists, localPlaylists) {
        (deezerPlaylists.map { it.playlist } + localPlaylists).distinctBy { it.id }
    }

    PlaylistPicker(
        track = track,
        playlists = allPlaylists,
        canCreateDeezer = deezerUser != null,
        onDismiss = onDismiss,
        onSelect = { playlist ->
            viewModel.addTrackToPlaylist(playlist.id, track)
            onDismiss()
        },
        onCreate = viewModel::createPlaylist
    )
}
