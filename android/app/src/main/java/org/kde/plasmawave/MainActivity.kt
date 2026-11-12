package org.kde.plasmawave

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.PlasmaTheme
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.download.DownloadQuality
import org.kde.plasmawave.ui.components.FullPlayerSheet
import org.kde.plasmawave.ui.components.MiniPlayer
import org.kde.plasmawave.ui.components.SongLongPressBottomSheet
import org.kde.plasmawave.ui.screens.DownloadsScreen
import org.kde.plasmawave.ui.screens.HomeScreen
import org.kde.plasmawave.ui.screens.LibraryScreen
import org.kde.plasmawave.ui.screens.ModManagerScreen
import org.kde.plasmawave.ui.screens.PlaylistDetailScreen
import org.kde.plasmawave.ui.screens.QueueScreen
import org.kde.plasmawave.ui.screens.SearchScreen
import org.kde.plasmawave.ui.screens.SettingsScreen
import org.kde.plasmawave.ui.screens.ThemesScreen
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

sealed class Screen(val title: String, val icon: ImageVector) {
    object Home : Screen("Home", Icons.Default.Home)
    object Library : Screen("Library", Icons.Default.LibraryMusic)
    object Search : Screen("Search", Icons.Default.Search)
    object Queue : Screen("Queue", Icons.AutoMirrored.Filled.QueueMusic)
    object Settings : Screen("Settings", Icons.Default.Settings)
}

sealed class SubScreen {
    object None : SubScreen()
    class PlaylistDetail(val playlist: FfiPlaylist) : SubScreen()
    object Themes : SubScreen()
    object ModManager : SubScreen()
    object Downloads : SubScreen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val activeTheme by viewModel.activeTheme.collectAsState()
            val playerState by viewModel.playbackManager.uiState.collectAsState()
            val selectedTrackForMenu by viewModel.selectedTrackForMenu.collectAsState()
            val customModActions by viewModel.customTrackActions.collectAsState()
            val statusMessage by viewModel.statusMessage.collectAsState()
            val pins by viewModel.pins.collectAsState()
            val deezerUser by viewModel.deezerUser.collectAsState()
            val defaultDownloadQuality by viewModel.downloadQuality.collectAsState()
            var trackToDownload by remember { mutableStateOf<FfiTrack?>(null) }
            var requestedDownload by remember { mutableStateOf<Pair<FfiTrack, DownloadQuality>?>(null) }
            val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val request = requestedDownload
                if (granted && request != null) viewModel.downloadFullTrack(request.first, request.second)
                else if (!granted) Toast.makeText(this@MainActivity, "Şarkıyı kaydetmek için depolama izni gerekli", Toast.LENGTH_LONG).show()
                requestedDownload = null
            }

            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
            var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }

            var isFullPlayerVisible by remember { mutableStateOf(false) }
            val fullPlayerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val menuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()

            BackHandler(enabled = currentSubScreen != SubScreen.None && !isFullPlayerVisible && selectedTrackForMenu == null) {
                currentSubScreen = SubScreen.None
            }

            LaunchedEffect(statusMessage) {
                statusMessage?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                    viewModel.clearStatusMessage()
                }
            }

            PlasmaTheme(activeTheme = activeTheme) {
                val customColors = LocalPlasmaColors.current

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        Column {
                            // Persistent Mini Player above bottom bar
                            AnimatedVisibility(
                                visible = playerState.currentEntry != null,
                                enter = slideInVertically(initialOffsetY = { it }),
                                exit = slideOutVertically(targetOffsetY = { it })
                            ) {
                                MiniPlayer(
                                    playerState = playerState,
                                    onTogglePlayPause = { viewModel.playbackManager.togglePlayPause() },
                                    onSkipNext = { viewModel.playbackManager.skipNext() },
                                    onClick = { isFullPlayerVisible = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // KDE Breeze Bottom Navigation Bar
                            NavigationBar(
                                containerColor = customColors.card,
                                tonalElevation = 0.dp
                            ) {
                                val navItems = listOf(
                                    Screen.Home,
                                    Screen.Library,
                                    Screen.Search,
                                    Screen.Queue,
                                    Screen.Settings
                                )
                                navItems.forEach { screen ->
                                    NavigationBarItem(
                                        selected = currentScreen == screen && currentSubScreen == SubScreen.None,
                                        onClick = {
                                            currentScreen = screen
                                            currentSubScreen = SubScreen.None
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = screen.icon,
                                                contentDescription = screen.title
                                            )
                                        },
                                        label = { Text(screen.title) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = customColors.textSecondary,
                                            unselectedTextColor = customColors.textSecondary,
                                            indicatorColor = customColors.border
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (val sub = currentSubScreen) {
                            is SubScreen.PlaylistDetail -> {
                                PlaylistDetailScreen(
                                    playlist = sub.playlist,
                                    viewModel = viewModel,
                                    onBack = { currentSubScreen = SubScreen.None }
                                )
                            }
                            is SubScreen.Themes -> {
                                ThemesScreen(
                                    viewModel = viewModel,
                                    onBack = { currentSubScreen = SubScreen.None }
                                )
                            }
                            is SubScreen.ModManager -> {
                                ModManagerScreen(
                                    viewModel = viewModel,
                                    onBack = { currentSubScreen = SubScreen.None }
                                )
                            }
                            is SubScreen.Downloads -> {
                                DownloadsScreen(
                                    viewModel = viewModel
                                )
                            }
                            is SubScreen.None -> {
                                when (currentScreen) {
                                    is Screen.Home -> HomeScreen(
                                        viewModel = viewModel,
                                        onPlaylistClick = { currentSubScreen = SubScreen.PlaylistDetail(it) }
                                    )
                                    is Screen.Library -> LibraryScreen(
                                        viewModel = viewModel,
                                        onPlaylistClick = { pl ->
                                            currentSubScreen = SubScreen.PlaylistDetail(pl)
                                        }
                                    )
                                    is Screen.Search -> SearchScreen(
                                        viewModel = viewModel,
                                        onPlaylistClick = { pl ->
                                            currentSubScreen = SubScreen.PlaylistDetail(pl)
                                        }
                                    )
                                    is Screen.Queue -> QueueScreen(playbackManager = viewModel.playbackManager)
                                    is Screen.Settings -> SettingsScreen(
                                        viewModel = viewModel,
                                        onNavigateThemes = { currentSubScreen = SubScreen.Themes },
                                        onNavigateMods = { currentSubScreen = SubScreen.ModManager },
                                        onNavigateDownloads = { currentSubScreen = SubScreen.Downloads }
                                    )
                                }
                            }
                        }
                    }
                }

                // Full Screen Player Sheet
                if (isFullPlayerVisible && playerState.currentEntry != null) {
                    FullPlayerSheet(
                        playerState = playerState,
                        sheetState = fullPlayerSheetState,
                        onDismiss = {
                            scope.launch {
                                fullPlayerSheetState.hide()
                                isFullPlayerVisible = false
                            }
                        },
                        onTogglePlayPause = { viewModel.playbackManager.togglePlayPause() },
                        onSkipNext = { viewModel.playbackManager.skipNext() },
                        onSkipPrevious = { viewModel.playbackManager.skipPrevious() },
                        onSeekTo = { viewModel.playbackManager.seekTo(it) },
                        onToggleShuffle = { viewModel.playbackManager.toggleShuffle() },
                        onToggleRepeat = { viewModel.playbackManager.toggleRepeat() }
                    )
                }

                // Long Press Song Contextual Menu Sheet
                if (selectedTrackForMenu != null) {
                    SongLongPressBottomSheet(
                        track = selectedTrackForMenu,
                        sheetState = menuSheetState,
                        onDismiss = {
                            scope.launch {
                                menuSheetState.hide()
                                viewModel.closeTrackMenu()
                            }
                        },
                        onPlayNext = { track ->
                            viewModel.playbackManager.playNext(listOf(track))
                        },
                        onAddToQueue = { track ->
                            viewModel.playbackManager.addToQueue(listOf(track))
                        },
                        onToggleFavorite = { track ->
                            viewModel.toggleFavorite(track)
                        },
                        onAddToPlaylist = { track ->
                            // Add to first available playlist or show notice
                            val pls = viewModel.playlists.value
                            if (pls.isNotEmpty()) {
                                viewModel.addTrackToPlaylist(pls.first().id, track)
                            } else {
                                Toast.makeText(this@MainActivity, "Create a playlist first in Library tab", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDownload = { track ->
                            if (track.provider == "deezer") trackToDownload = track else viewModel.downloadTrack(track)
                        },
                        isPinned = pins.any { it.id == "track:${selectedTrackForMenu?.id}" },
                        onTogglePin = viewModel::togglePinnedTrack,
                        canDownloadDeezer = deezerUser != null,
                        customModActions = customModActions,
                        onExecuteModAction = { mod, track ->
                            viewModel.executeModAction(mod.id, track)
                        }
                    )
                }

                trackToDownload?.let { track ->
                    var quality by remember(track.id) { mutableStateOf(defaultDownloadQuality) }
                    AlertDialog(
                        onDismissRequest = { trackToDownload = null },
                        title = { Text("İndirme biçimi") },
                        text = {
                            Column {
                                Text("${track.artist} • ${track.title}")
                                DownloadQuality.entries.forEach { choice ->
                                    Row(modifier = Modifier.fillMaxWidth().clickable { quality = choice }) {
                                        RadioButton(selected = quality == choice, onClick = { quality = choice })
                                        Text(choice.label, modifier = Modifier.padding(top = 14.dp))
                                    }
                                }
                                Text("Müzik/PlasmaWave klasörüne kaydedilir. Kalite hesabının erişimine bağlıdır.")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.setDownloadQuality(quality)
                                trackToDownload = null
                                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                    requestedDownload = track to quality
                                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else viewModel.downloadFullTrack(track, quality)
                            }) { Text("İndir") }
                        },
                        dismissButton = { TextButton(onClick = { trackToDownload = null }) { Text("Vazgeç") } }
                    )
                }
            }
        }
    }
}
