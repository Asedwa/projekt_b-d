package org.kde.plasmawave.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.*
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiPlaylist

private enum class PlaylistFilter(val label: String) { ALL("Tümü"), DEEZER("Deezer"), LOCAL("Bu cihaz"), PINNED("Sabitlenenler") }

@Composable
fun LibraryScreen(viewModel: MainViewModel, onPlaylistClick: (FfiPlaylist) -> Unit, modifier: Modifier = Modifier) {
    val localPlaylists by viewModel.playlists.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val deezerBusy by viewModel.deezerBusy.collectAsState()
    val deezerLoginError by viewModel.deezerLoginError.collectAsState()
    val loading by viewModel.deezerLibraryLoading.collectAsState()
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
    val deezerLiked = if (deezerFavorites.isNotEmpty()) FfiPlaylist(DEEZER_FAVORITES_ID, "Deezer • Sevdiğim Şarkılar", null, false,
        deezerFavorites.size.toUInt(), null, "real") else null
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

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(deezerUser?.name?.take(1)?.uppercase() ?: "P", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
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
        if (visible.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading && entries.isEmpty()) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Spacer(Modifier.height(12.dp)); Text("Kitaplığın yükleniyor…", color = colors.textSecondary) }
                else {
                    Text(if (entries.isEmpty()) "Henüz çalma listesi yok." else "Eşleşen çalma listesi yok.", color = colors.textSecondary)
                    if (deezerUser == null && entries.isEmpty()) TextButton(onClick = { showLogin = true }) { Text("Deezer hesabını bağla") }
                }
            }
        } else if (grid) {
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp)) {
                items(visible, key = { it.playlist.id }) { entry ->
                    PlaylistCell(entry.playlist, "playlist:${entry.playlist.id}" in pinnedIds, viewModel, onPlaylistClick, true)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(visible, key = { it.playlist.id }) { entry ->
                    PlaylistCell(entry.playlist, "playlist:${entry.playlist.id}" in pinnedIds, viewModel, onPlaylistClick, false)
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
private fun PlaylistCell(playlist: FfiPlaylist, pinned: Boolean, viewModel: MainViewModel, onClick: (FfiPlaylist) -> Unit, grid: Boolean) {
    val colors = LocalPlasmaColors.current
    var showMenu by remember { mutableStateOf(false) }
    var artwork by remember(playlist.id, playlist.artworkUrl, playlist.trackCount) { mutableStateOf(playlist.artworkUrl) }
    LaunchedEffect(playlist.id, playlist.artworkUrl, playlist.trackCount) {
        if (artwork.isNullOrBlank() && playlist.isLocal && playlist.trackCount > 0u && playlist.id != LOCAL_FAVORITES_ID) {
            artwork = viewModel.getCachedLocalPlaylistArtwork(playlist.id)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val favoritesBrush = remember(primary) {
        Brush.linearGradient(listOf(primary, Color(0xFF5968DF), Color(0xFFA8D7D0)))
    }
    @Composable fun Cover(modifier: Modifier) {
        if (playlist.id == LOCAL_FAVORITES_ID || playlist.id == DEEZER_FAVORITES_ID) {
            Box(modifier.clip(RoundedCornerShape(6.dp)).background(favoritesBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.fillMaxSize(0.4f), tint = Color.White)
            }
        } else ArtworkImage(url = artwork, modifier = modifier, fallback = Icons.AutoMirrored.Filled.PlaylistPlay, shape = RoundedCornerShape(6.dp))
    }
    @Composable fun Menu() {
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "Liste seçenekleri", modifier = Modifier.size(20.dp), tint = colors.textSecondary) }
            if (showMenu) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(if (pinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }, onClick = { viewModel.togglePinnedPlaylist(playlist); showMenu = false })
                }
            }
        }
    }
    if (grid) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onClick(playlist) }) {
            Cover(Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(7.dp))
            Text(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) Icon(Icons.Default.PushPin, contentDescription = "Sabitlendi", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(if (playlist.isLocal) "Liste • Bu cihaz" else "Liste • Deezer", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Menu()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onClick(playlist) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(Modifier.size(60.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.trackCount} şarkı • " + if (playlist.isLocal) "Bu cihaz" else "Deezer", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Menu()
        }
    }
}
