package org.kde.plasmawave.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.*
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.LocalDownloadStatus
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

private enum class PlaylistFilter(val label: String) { ALL("Tümü"), DEEZER("Deezer"), LOCAL("Bu cihaz"), PINNED("Sabitlenenler") }

@Composable
fun LibraryScreen(viewModel: MainViewModel, onPlaylistClick: (FfiPlaylist) -> Unit, modifier: Modifier = Modifier) {
    val localPlaylists by viewModel.playlists.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val downloadsLibraryPinned by viewModel.downloadsLibraryPinned.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val flowLibraryPinned by viewModel.flowLibraryPinned.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val flowLoading by viewModel.flowLoading.collectAsState()
    val deezerBusy by viewModel.deezerBusy.collectAsState()
    val deezerLoginError by viewModel.deezerLoginError.collectAsState()
    val loading by viewModel.deezerLibraryLoading.collectAsState()
    val librarySyncWithQuick by viewModel.librarySyncWithQuick.collectAsState()
    val quickCardStyle by viewModel.quickCardStyle.collectAsState()
    val quickCardScale by viewModel.quickCardScale.collectAsState()
    val quickIconScale by viewModel.quickIconScale.collectAsState()
    val quickCardShape by viewModel.quickCardShape.collectAsState()
    val quickCardLayout by viewModel.quickCardLayout.collectAsState()
    val quickCardContentMode by viewModel.quickCardContentMode.collectAsState()
    val libraryCardStyle by viewModel.libraryCardStyle.collectAsState()
    val libraryCardScale by viewModel.libraryCardScale.collectAsState()
    val libraryIconScale by viewModel.libraryIconScale.collectAsState()
    val libraryCardShape by viewModel.libraryCardShape.collectAsState()
    val libraryCardLayout by viewModel.libraryCardLayout.collectAsState()
    val libraryCardContentMode by viewModel.libraryCardContentMode.collectAsState()
    val effectiveStyle = if (librarySyncWithQuick) quickCardStyle else libraryCardStyle
    val effectiveCardScale = if (librarySyncWithQuick) quickCardScale else libraryCardScale
    val effectiveIconScale = if (librarySyncWithQuick) quickIconScale else libraryIconScale
    val effectiveShape = if (librarySyncWithQuick) quickCardShape else libraryCardShape
    val effectiveLayout = if (librarySyncWithQuick) quickCardLayout else libraryCardLayout
    val effectiveContentMode = if (librarySyncWithQuick) quickCardContentMode else libraryCardContentMode
    val prefs = LocalContext.current.getSharedPreferences("playlist_sort", Context.MODE_PRIVATE)
    val colors = LocalPlasmaColors.current
    var filter by rememberSaveable { mutableStateOf(PlaylistFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(runCatching { LibraryPlaylistSort.valueOf(prefs.getString("library", null) ?: "DEFAULT") }.getOrDefault(LibraryPlaylistSort.DEFAULT)) }
    var grid by rememberSaveable { mutableStateOf(prefs.getBoolean("library_grid", true)) }
    var showSort by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var createOnDeezer by remember(deezerUser) { mutableStateOf(deezerUser != null) }
    var showLogin by remember { mutableStateOf(false) }
    if (showLogin) {
        LaunchedEffect(deezerUser) { if (deezerUser != null) showLogin = false }
        DeezerLoginDialog(busy = deezerBusy, error = deezerLoginError, onDismiss = { showLogin = false }, onLogin = viewModel::connectDeezerArl)
    }
    val liked = if (favorites.isNotEmpty()) FfiPlaylist(LOCAL_FAVORITES_ID, "Beğenilen Şarkılar", null, true,
        favorites.size.toUInt(), null, "real") else null
    val deezerLiked = if (deezerUser != null || deezerFavorites.isNotEmpty()) FfiPlaylist(DEEZER_FAVORITES_ID, "Deezer • Sevdiğim Şarkılar", null, false,
        deezerFavorites.size.toUInt(), null, "real") else null
    val downloadedPlaylist = remember(downloadedTracks) {
        FfiPlaylist(
            id = LOCAL_DOWNLOADS_ID,
            name = "İndirilen Müzikler",
            description = "Cihaza indirilen çevrimdışı şarkılar",
            isLocal = true,
            trackCount = downloadedTracks.size.toUInt(),
            artworkUrl = downloadedTracks.firstOrNull()?.artworkUrl,
            origin = "real"
        )
    }
    val entries = remember(localPlaylists, deezerPlaylists, liked, deezerLiked) {
        listOfNotNull(deezerLiked, liked).map { LibraryPlaylistEntry(it, addedAt = null) } + deezerPlaylists +
            localPlaylists.filter { it.origin != "development_fixture" }.map { LibraryPlaylistEntry(it, it.id.removePrefix("local:").toLongOrNull()) }
    }.distinctBy { it.playlist.id }
    val pinnedIds = remember(pins) { pins.map { it.id }.toSet() }
    val visible = remember(entries, sort, filter, query, pinnedIds) {
        sortLibraryPlaylists(entries.filter { entry ->
            entry.playlist.name.contains(query.trim(), ignoreCase = true) && when (filter) {
                PlaylistFilter.ALL -> true
                PlaylistFilter.DEEZER -> !entry.playlist.isLocal
                PlaylistFilter.LOCAL -> entry.playlist.isLocal
                PlaylistFilter.PINNED -> "playlist:${entry.playlist.id}" in pinnedIds
            }
        }, sort)
    }

    val showFlow = remember(flowLibraryPinned, deezerUser, filter, query) {
        flowLibraryPinned && deezerUser != null &&
            (filter == PlaylistFilter.ALL || filter == PlaylistFilter.DEEZER || filter == PlaylistFilter.PINNED) &&
            (query.isBlank() || "deezer flow".contains(query.trim(), ignoreCase = true) || "flow".contains(query.trim(), ignoreCase = true))
    }

    val showDownloadsCard = remember(downloadsLibraryPinned, filter, query, downloadedTracks) {
        val matchesQuery = query.isBlank() ||
            "indirilen müzikler".contains(query.trim(), ignoreCase = true) ||
            "indirilenler".contains(query.trim(), ignoreCase = true)
        matchesQuery && when (filter) {
            PlaylistFilter.LOCAL -> true
            PlaylistFilter.ALL, PlaylistFilter.PINNED -> downloadsLibraryPinned
            PlaylistFilter.DEEZER -> false
        }
    }

    val filteredDownloadedTracks = remember(downloadedTracks, query) {
        val q = query.trim()
        if (q.isBlank()) downloadedTracks else downloadedTracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.cardPressed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    deezerUser?.name?.take(1)?.uppercase() ?: "P",
                    color = if (colors.legacyUiMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Kitaplığın", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) { Icon(Icons.Default.Search, contentDescription = "Kitaplıkta ara") }
            IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "Yeni çalma listesi") }
        }
        if (showSearch) OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, placeholder = { Text("Kitaplığında ara") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(PlaylistFilter.entries) { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { showSort = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${if (sort == LibraryPlaylistSort.DEFAULT) "Liste sırası" else sort.label}", maxLines = 1)
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    LibraryPlaylistSort.entries.forEach { option ->
                        val needsDate = option == LibraryPlaylistSort.ADDED_NEWEST || option == LibraryPlaylistSort.ADDED_OLDEST
                        DropdownMenuItem(text = { Text(option.label) }, enabled = !needsDate || entries.any { it.addedAt != null },
                            leadingIcon = { if (sort == option) Icon(Icons.Default.Check, contentDescription = null) },
                            onClick = { sort = option; prefs.edit().putString("library", option.name).apply(); showSort = false })
                    }
                }
            }
            IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("library_grid", grid).apply() }) {
                Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = if (grid) "Liste görünümü" else "Izgara görünümü")
            }
        }
        if (visible.isEmpty() && !showFlow && !showDownloadsCard && (filter != PlaylistFilter.LOCAL || filteredDownloadedTracks.isEmpty())) {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading && entries.isEmpty()) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Spacer(Modifier.height(12.dp)); Text("Kitaplığın yükleniyor…", color = colors.textSecondary) }
                else {
                    Text(if (entries.isEmpty()) "Henüz çalma listesi yok." else "Eşleşen çalma listesi yok.", color = colors.textSecondary)
                    if (deezerUser == null && entries.isEmpty()) TextButton(onClick = { showLogin = true }) { Text("Deezer hesabını bağla") }
                }
            }
        } else if (grid) {
            val isCompactHorizontal = effectiveLayout == QuickCardLayout.COMPACT_HORIZONTAL &&
                effectiveContentMode != QuickCardContentMode.IMAGE_ONLY
            val gridColumns = if (isCompactHorizontal) {
                when {
                    effectiveCardScale <= 0.78f -> 3
                    effectiveCardScale >= 1.18f -> 1
                    else -> 2
                }
            } else {
                when {
                    effectiveCardScale <= 0.65f -> 5
                    effectiveCardScale <= 0.82f -> 4
                    effectiveCardScale >= 1.18f -> 2
                    else -> 3
                }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(gridColumns), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (isCompactHorizontal) 8.dp else 14.dp),
                contentPadding = PaddingValues(bottom = 80.dp)) {
                if (showFlow) {
                    item(key = "flow:deezer") {
                        FlowGridCell(
                            viewModel = viewModel,
                            isQuickAccessPinned = flowQuickAccessPinned,
                            isLoading = flowLoading,
                            cardStyle = effectiveStyle,
                            cardShape = effectiveShape,
                            contentMode = effectiveContentMode,
                            cardScale = effectiveCardScale,
                            iconScale = effectiveIconScale,
                            compactHorizontal = isCompactHorizontal
                        )
                    }
                }
                if (showDownloadsCard) {
                    item(key = "downloads:local") {
                        DownloadsGridCell(
                            playlist = downloadedPlaylist,
                            downloadedTracks = downloadedTracks,
                            isLibraryPinned = downloadsLibraryPinned,
                            isQuickAccessPinned = downloadsQuickAccessPinned,
                            viewModel = viewModel,
                            onClick = { onPlaylistClick(downloadedPlaylist) },
                            cardStyle = effectiveStyle,
                            cardShape = effectiveShape,
                            contentMode = effectiveContentMode,
                            cardScale = effectiveCardScale,
                            iconScale = effectiveIconScale,
                            compactHorizontal = isCompactHorizontal
                        )
                    }
                }
                items(visible, key = { it.playlist.id }) { entry ->
                    PlaylistCell(
                        playlist = entry.playlist,
                        pinned = "playlist:${entry.playlist.id}" in pinnedIds,
                        viewModel = viewModel,
                        onClick = onPlaylistClick,
                        grid = true,
                        cardStyle = effectiveStyle,
                        cardShape = effectiveShape,
                        contentMode = effectiveContentMode,
                        cardScale = effectiveCardScale,
                        iconScale = effectiveIconScale,
                        compactHorizontal = isCompactHorizontal
                    )
                }
                if (filter == PlaylistFilter.LOCAL && filteredDownloadedTracks.isNotEmpty()) {
                    item(key = "local_downloaded_tracks_header", span = { GridItemSpan(maxLineSpan) }) {
                        DownloadedTracksSectionHeader(
                            count = filteredDownloadedTracks.size,
                            onPlayAll = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks, 0) },
                            onShuffleAll = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks.shuffled(), 0) }
                        )
                    }
                    itemsIndexed(
                        filteredDownloadedTracks,
                        key = { _, track -> "local_dl_grid:${track.id}" },
                        span = { _, _ -> GridItemSpan(maxLineSpan) }
                    ) { index, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = currentTrackId == track.id,
                            onClick = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks, index) },
                            onLongClick = { viewModel.openTrackMenu(track, downloadedPlaylist, index) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                if (showFlow) {
                    item(key = "flow:deezer") {
                        FlowListCell(
                            viewModel = viewModel,
                            isQuickAccessPinned = flowQuickAccessPinned,
                            isLoading = flowLoading,
                            cardStyle = effectiveStyle,
                            cardShape = effectiveShape,
                            contentMode = effectiveContentMode,
                            cardScale = effectiveCardScale,
                            iconScale = effectiveIconScale
                        )
                    }
                }
                if (showDownloadsCard) {
                    item(key = "downloads:local") {
                        DownloadsListCell(
                            playlist = downloadedPlaylist,
                            downloadedTracks = downloadedTracks,
                            isLibraryPinned = downloadsLibraryPinned,
                            isQuickAccessPinned = downloadsQuickAccessPinned,
                            viewModel = viewModel,
                            onClick = { onPlaylistClick(downloadedPlaylist) },
                            cardStyle = effectiveStyle,
                            cardShape = effectiveShape,
                            contentMode = effectiveContentMode,
                            cardScale = effectiveCardScale,
                            iconScale = effectiveIconScale
                        )
                    }
                }
                items(visible, key = { it.playlist.id }) { entry ->
                    PlaylistCell(
                        playlist = entry.playlist,
                        pinned = "playlist:${entry.playlist.id}" in pinnedIds,
                        viewModel = viewModel,
                        onClick = onPlaylistClick,
                        grid = false,
                        cardStyle = effectiveStyle,
                        cardShape = effectiveShape,
                        contentMode = effectiveContentMode,
                        cardScale = effectiveCardScale,
                        iconScale = effectiveIconScale
                    )
                }
                if (filter == PlaylistFilter.LOCAL && filteredDownloadedTracks.isNotEmpty()) {
                    item(key = "local_downloaded_tracks_header") {
                        DownloadedTracksSectionHeader(
                            count = filteredDownloadedTracks.size,
                            onPlayAll = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks, 0) },
                            onShuffleAll = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks.shuffled(), 0) }
                        )
                    }
                    itemsIndexed(
                        filteredDownloadedTracks,
                        key = { _, track -> "local_dl_list:${track.id}" }
                    ) { index, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = currentTrackId == track.id,
                            onClick = { viewModel.playbackManager.playTrackList(filteredDownloadedTracks, index) },
                            onLongClick = { viewModel.openTrackMenu(track, downloadedPlaylist, index) }
                        )
                    }
                }
            }
        }
    }
    if (showCreate) AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("Yeni çalma listesi") },
        text = { Column {
            OutlinedTextField(newName, { newName = it }, label = { Text("Liste adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !createOnDeezer, onClick = { createOnDeezer = false }, label = { Text("Bu cihaz") })
                if (deezerUser != null) FilterChip(selected = createOnDeezer, onClick = { createOnDeezer = true }, label = { Text("Deezer") })
            }
        } },
        confirmButton = { Button(onClick = { viewModel.createPlaylist(newName.trim(), createOnDeezer); newName = ""; showCreate = false }, enabled = newName.isNotBlank()) { Text("Oluştur") } },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Vazgeç") } })
}

@Composable
private fun LibrarySpecialCover(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    gradient: Brush,
    cardStyle: QuickCardStyle,
    cardShape: QuickCardShape,
    iconScale: Float,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val shape = cardShape.toComposeShape()
    val useThemeAdaptive = cardStyle == QuickCardStyle.THEME_ADAPTIVE
    val iconFraction = (0.44f * iconScale).coerceIn(0.28f, 0.70f)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (!legacyUi) {
                    Modifier.background(colors.card)
                } else if (useThemeAdaptive) {
                    Modifier
                        .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), colors.card)))
                        .border(1.dp, colors.border.copy(alpha = 0.65f), shape)
                } else {
                    Modifier.background(gradient)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = if (!legacyUi) MaterialTheme.colorScheme.onSurface else if (useThemeAdaptive) primary else Color.White,
                strokeWidth = 2.5.dp
            )
        } else if (!legacyUi) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize((0.38f * iconScale).coerceIn(0.24f, 0.58f)),
                tint = MaterialTheme.colorScheme.onSurface
            )
        } else if (useThemeAdaptive) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.58f)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize((0.60f * iconScale).coerceIn(0.35f, 0.85f)),
                    tint = primary
                )
            }
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(iconFraction),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun PlaylistCell(
    playlist: FfiPlaylist,
    pinned: Boolean,
    viewModel: MainViewModel,
    onClick: (FfiPlaylist) -> Unit,
    grid: Boolean,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    cardScale: Float = 1.0f,
    iconScale: Float = 1.0f,
    compactHorizontal: Boolean = false
) {
    val colors = LocalPlasmaColors.current
    val downloadStatus = LocalDownloadStatus.current
    val isPlaylistDownloaded = playlist.id in downloadStatus.downloadedPlaylistIds
    var showMenu by remember { mutableStateOf(false) }
    var artwork by remember(playlist.id, playlist.artworkUrl, playlist.trackCount) { mutableStateOf(playlist.artworkUrl) }
    LaunchedEffect(playlist.id, playlist.artworkUrl, playlist.trackCount) {
        if (artwork.isNullOrBlank() && playlist.isLocal && playlist.trackCount > 0u && playlist.id != LOCAL_FAVORITES_ID) {
            artwork = viewModel.getCachedLocalPlaylistArtwork(playlist.id)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val shape = cardShape.toComposeShape()
    val favoritesBrush = remember(primary) {
        Brush.linearGradient(listOf(primary, Color(0xFF5968DF), Color(0xFFA8D7D0)))
    }
    @Composable fun Cover(modifier: Modifier) {
        if (playlist.id == LOCAL_FAVORITES_ID || playlist.id == DEEZER_FAVORITES_ID) {
            LibrarySpecialCover(
                icon = Icons.Default.Favorite,
                contentDescription = playlist.name,
                gradient = favoritesBrush,
                cardStyle = cardStyle,
                cardShape = cardShape,
                iconScale = iconScale,
                modifier = modifier
            )
        } else ArtworkImage(url = artwork, modifier = modifier, fallback = Icons.AutoMirrored.Filled.PlaylistPlay, shape = shape)
    }
    @Composable fun Menu() {
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(if (compactHorizontal) 28.dp else 32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Liste seçenekleri", modifier = Modifier.size(if (compactHorizontal) 16.dp else 20.dp), tint = colors.textSecondary)
            }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(if (pinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }, onClick = { viewModel.togglePinnedPlaylist(playlist); showMenu = false })
                }
            }
        }
    }
    val legacyUi = colors.legacyUiMode
    if (grid) {
        if (compactHorizontal) {
            val cardHeight = (58.dp * cardScale).coerceIn(44.dp, 76.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(shape)
                    .background(colors.card)
                    .then(if (legacyUi) Modifier.border(1.dp, colors.border.copy(alpha = 0.6f), shape) else Modifier)
                    .clickable { onClick(playlist) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (contentMode != QuickCardContentMode.TITLE_ONLY) {
                    Cover(Modifier.size(cardHeight))
                }
                Text(
                    playlist.name,
                    modifier = Modifier.weight(1f).padding(start = if (contentMode == QuickCardContentMode.TITLE_ONLY) 12.dp else 10.dp, end = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Menu()
            }
        } else {
            Column(Modifier.fillMaxWidth().clip(shape).clickable { onClick(playlist) }) {
                if (contentMode == QuickCardContentMode.TITLE_ONLY) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(shape)
                            .then(
                                if (legacyUi) {
                                    Modifier
                                        .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), colors.card)))
                                        .border(1.dp, colors.border.copy(alpha = 0.65f), shape)
                                } else {
                                    Modifier.background(colors.card)
                                }
                            )
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Cover(Modifier.fillMaxWidth().aspectRatio(1f))
                }
                if (contentMode == QuickCardContentMode.BOTH) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlaylistDownloaded) {
                            Icon(
                                Icons.Default.DownloadDone,
                                contentDescription = "İndirildi",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            playlist.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (legacyUi) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!legacyUi) Menu()
                    }
                    if (legacyUi) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pinned) Icon(Icons.Default.PushPin, contentDescription = "Sabitlendi", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(if (playlist.isLocal) "Liste • Bu cihaz" else "Liste • Deezer", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Menu()
                        }
                    }
                }
            }
        }
    } else {
        val thumbSize = (60.dp * cardScale).coerceIn(42.dp, 78.dp)
        Row(Modifier.fillMaxWidth().clip(shape).clickable { onClick(playlist) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (contentMode != QuickCardContentMode.TITLE_ONLY) {
                Cover(Modifier.size(thumbSize))
                Spacer(Modifier.width(12.dp))
            }
            if (contentMode != QuickCardContentMode.IMAGE_ONLY) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlaylistDownloaded) {
                            Icon(
                                Icons.Default.DownloadDone,
                                contentDescription = "İndirildi",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("${playlist.trackCount} şarkı • " + if (playlist.isLocal) "Bu cihaz" else "Deezer", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Menu()
        }
    }
}

@Composable
private fun FlowGridCell(
    viewModel: MainViewModel,
    isQuickAccessPinned: Boolean,
    isLoading: Boolean,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    cardScale: Float = 1.0f,
    iconScale: Float = 1.0f,
    compactHorizontal: Boolean = false
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    val shape = cardShape.toComposeShape()
    val flowGradient = remember {
        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316)))
    }

    @Composable
    fun FlowMenu(iconSize: androidx.compose.ui.unit.Dp, btnSize: androidx.compose.ui.unit.Dp) {
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(btnSize)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Flow seçenekleri",
                    modifier = Modifier.size(iconSize),
                    tint = colors.textSecondary
                )
            }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Flow başlat") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            viewModel.startFlow()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Kitaplıktan kaldır") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setFlowLibraryPinned(false)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isQuickAccessPinned) "Hızlı erişimden kaldır" else "Hızlı erişime sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setFlowQuickAccessPinned(!isQuickAccessPinned)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }

    val legacyUi = colors.legacyUiMode
    if (compactHorizontal) {
        val cardHeight = (58.dp * cardScale).coerceIn(44.dp, 76.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(shape)
                .background(colors.card)
                .then(if (legacyUi) Modifier.border(1.dp, colors.border.copy(alpha = 0.6f), shape) else Modifier)
                .clickable { viewModel.startFlow() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (contentMode != QuickCardContentMode.TITLE_ONLY) {
                LibrarySpecialCover(
                    icon = Icons.Default.AllInclusive,
                    contentDescription = "Deezer Flow",
                    gradient = flowGradient,
                    cardStyle = cardStyle,
                    cardShape = cardShape,
                    iconScale = iconScale,
                    isLoading = isLoading,
                    modifier = Modifier.size(cardHeight)
                )
            }
            Text(
                "Deezer Flow",
                modifier = Modifier.weight(1f).padding(start = if (contentMode == QuickCardContentMode.TITLE_ONLY) 12.dp else 10.dp, end = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            FlowMenu(iconSize = 16.dp, btnSize = 28.dp)
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable { viewModel.startFlow() }
        ) {
            if (contentMode == QuickCardContentMode.TITLE_ONLY) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(shape)
                        .then(
                            if (legacyUi) {
                                Modifier
                                    .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), colors.card)))
                                    .border(1.dp, colors.border.copy(alpha = 0.65f), shape)
                            } else {
                                Modifier.background(colors.card)
                            }
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Deezer Flow", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            } else {
                LibrarySpecialCover(
                    icon = Icons.Default.AllInclusive,
                    contentDescription = "Deezer Flow",
                    gradient = flowGradient,
                    cardStyle = cardStyle,
                    cardShape = cardShape,
                    iconScale = iconScale,
                    isLoading = isLoading,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
            if (contentMode == QuickCardContentMode.BOTH) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Deezer Flow",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (legacyUi) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!legacyUi) FlowMenu(iconSize = 20.dp, btnSize = 32.dp)
                }
                if (legacyUi) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Sabitlendi",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "Flow • Deezer",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowMenu(iconSize = 20.dp, btnSize = 32.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowListCell(
    viewModel: MainViewModel,
    isQuickAccessPinned: Boolean,
    isLoading: Boolean,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    cardScale: Float = 1.0f,
    iconScale: Float = 1.0f
) {
    val colors = LocalPlasmaColors.current
    var showMenu by remember { mutableStateOf(false) }
    val shape = cardShape.toComposeShape()
    val thumbSize = (60.dp * cardScale).coerceIn(42.dp, 78.dp)
    val flowGradient = remember {
        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316)))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { viewModel.startFlow() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (contentMode != QuickCardContentMode.TITLE_ONLY) {
            LibrarySpecialCover(
                icon = Icons.Default.AllInclusive,
                contentDescription = "Deezer Flow",
                gradient = flowGradient,
                cardStyle = cardStyle,
                cardShape = cardShape,
                iconScale = iconScale,
                isLoading = isLoading,
                modifier = Modifier.size(thumbSize)
            )
            Spacer(Modifier.width(12.dp))
        }
        if (contentMode != QuickCardContentMode.IMAGE_ONLY) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Deezer Flow",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (colors.legacyUiMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Flow • Deezer (Kişisel Akış)",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        "Kişisel akış",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(
            onClick = { viewModel.startFlow() },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Flow Başlat",
                tint = if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary
            )
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Flow seçenekleri",
                    modifier = Modifier.size(20.dp),
                    tint = colors.textSecondary
                )
            }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Kitaplıktan kaldır") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setFlowLibraryPinned(false)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isQuickAccessPinned) "Hızlı erişimden kaldır" else "Hızlı erişime sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setFlowQuickAccessPinned(!isQuickAccessPinned)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadedTracksSectionHeader(
    count: Int,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        HorizontalDivider(color = colors.divider)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "İndirilen Şarkılar ($count)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Bu cihazdaki çevrimdışı müziklerin",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            OutlinedButton(
                onClick = onShuffleAll,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Karıştır", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPlayAll,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Çal", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DownloadsGridCell(
    playlist: FfiPlaylist,
    downloadedTracks: List<FfiTrack>,
    isLibraryPinned: Boolean,
    isQuickAccessPinned: Boolean,
    viewModel: MainViewModel,
    onClick: () -> Unit,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    cardScale: Float = 1.0f,
    iconScale: Float = 1.0f,
    compactHorizontal: Boolean = false
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    val shape = cardShape.toComposeShape()
    val downloadsGradient = remember(primary) {
        Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF10B981), primary))
    }

    @Composable
    fun DownloadsMenu(iconSize: androidx.compose.ui.unit.Dp, btnSize: androidx.compose.ui.unit.Dp) {
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(btnSize)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "İndirilenler seçenekleri",
                    modifier = Modifier.size(iconSize),
                    tint = colors.textSecondary
                )
            }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (downloadedTracks.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Tümünü çal") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = {
                                viewModel.playbackManager.playTrackList(downloadedTracks, 0)
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Karıştır") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                            onClick = {
                                viewModel.playbackManager.playTrackList(downloadedTracks.shuffled(), 0)
                                showMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isLibraryPinned) "Kitaplıktan kaldır" else "Kitaplığa sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setDownloadsLibraryPinned(!isLibraryPinned)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isQuickAccessPinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setDownloadsQuickAccessPinned(!isQuickAccessPinned)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }

    val legacyUi = colors.legacyUiMode
    if (compactHorizontal) {
        val cardHeight = (58.dp * cardScale).coerceIn(44.dp, 76.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(shape)
                .background(colors.card)
                .then(if (legacyUi) Modifier.border(1.dp, colors.border.copy(alpha = 0.6f), shape) else Modifier)
                .clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (contentMode != QuickCardContentMode.TITLE_ONLY) {
                LibrarySpecialCover(
                    icon = Icons.Default.DownloadDone,
                    contentDescription = "İndirilen Müzikler",
                    gradient = downloadsGradient,
                    cardStyle = cardStyle,
                    cardShape = cardShape,
                    iconScale = iconScale,
                    modifier = Modifier.size(cardHeight)
                )
            }
            Text(
                playlist.name,
                modifier = Modifier.weight(1f).padding(start = if (contentMode == QuickCardContentMode.TITLE_ONLY) 12.dp else 10.dp, end = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            DownloadsMenu(iconSize = 16.dp, btnSize = 28.dp)
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable { onClick() }
        ) {
            if (contentMode == QuickCardContentMode.TITLE_ONLY) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(shape)
                        .then(
                            if (legacyUi) {
                                Modifier
                                    .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), colors.card)))
                                    .border(1.dp, colors.border.copy(alpha = 0.65f), shape)
                            } else {
                                Modifier.background(colors.card)
                            }
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(playlist.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            } else {
                LibrarySpecialCover(
                    icon = Icons.Default.DownloadDone,
                    contentDescription = "İndirilen Müzikler",
                    gradient = downloadsGradient,
                    cardStyle = cardStyle,
                    cardShape = cardShape,
                    iconScale = iconScale,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
            if (contentMode == QuickCardContentMode.BOTH) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playlist.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (legacyUi) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!legacyUi) DownloadsMenu(iconSize = 20.dp, btnSize = 32.dp)
                }
                if (legacyUi) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLibraryPinned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Sabitlendi",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            "${downloadedTracks.size} şarkı • Bu cihaz",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        DownloadsMenu(iconSize = 20.dp, btnSize = 32.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsListCell(
    playlist: FfiPlaylist,
    downloadedTracks: List<FfiTrack>,
    isLibraryPinned: Boolean,
    isQuickAccessPinned: Boolean,
    viewModel: MainViewModel,
    onClick: () -> Unit,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    cardScale: Float = 1.0f,
    iconScale: Float = 1.0f
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    val shape = cardShape.toComposeShape()
    val thumbSize = (60.dp * cardScale).coerceIn(42.dp, 78.dp)
    val downloadsGradient = remember(primary) {
        Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF10B981), primary))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (contentMode != QuickCardContentMode.TITLE_ONLY) {
            LibrarySpecialCover(
                icon = Icons.Default.DownloadDone,
                contentDescription = "İndirilen Müzikler",
                gradient = downloadsGradient,
                cardStyle = cardStyle,
                cardShape = cardShape,
                iconScale = iconScale,
                modifier = Modifier.size(thumbSize)
            )
            Spacer(Modifier.width(12.dp))
        }
        if (contentMode != QuickCardContentMode.IMAGE_ONLY) {
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (colors.legacyUiMode && isLibraryPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${downloadedTracks.size} şarkı",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (downloadedTracks.isNotEmpty()) {
            IconButton(
                onClick = { viewModel.playbackManager.playTrackList(downloadedTracks, 0) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "İndirilenleri Çal",
                    tint = if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary
                )
            }
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "İndirilenler seçenekleri",
                    modifier = Modifier.size(20.dp),
                    tint = colors.textSecondary
                )
            }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isLibraryPinned) "Kitaplıktan kaldır" else "Kitaplığa sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setDownloadsLibraryPinned(!isLibraryPinned)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isQuickAccessPinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.setDownloadsQuickAccessPinned(!isQuickAccessPinned)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}
