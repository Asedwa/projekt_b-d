package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.LOCAL_FAVORITES_ID
import org.kde.plasmawave.ui.LibraryPlaylistEntry
import org.kde.plasmawave.ui.LibraryPlaylistSort
import org.kde.plasmawave.ui.sortLibraryPlaylists
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiPlaylist

private enum class PlaylistFilter(val label: String) {
    ALL("Tümü"), DEEZER("Deezer"), LOCAL("Bu cihaz")
}

@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    val localPlaylists by viewModel.playlists.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val deezerBusy by viewModel.deezerBusy.collectAsState()
    val deezerLoginError by viewModel.deezerLoginError.collectAsState()
    val deezerLibraryLoading by viewModel.deezerLibraryLoading.collectAsState()
    val sortPrefs = LocalContext.current.getSharedPreferences("playlist_sort", Context.MODE_PRIVATE)
    val colors = LocalPlasmaColors.current
    var filter by rememberSaveable { mutableStateOf(PlaylistFilter.ALL) }
    var sort by rememberSaveable {
        mutableStateOf(runCatching { LibraryPlaylistSort.valueOf(sortPrefs.getString("library", null) ?: "DEFAULT") }.getOrDefault(LibraryPlaylistSort.DEFAULT))
    }
    var showSort by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }

    if (showLogin) {
        LaunchedEffect(deezerUser) { if (deezerUser != null) showLogin = false }
        DeezerLoginDialog(
            busy = deezerBusy,
            error = deezerLoginError,
            onDismiss = { showLogin = false },
            onLogin = viewModel::connectDeezerArl
        )
    }

    val likedPlaylist = if (favorites.isNotEmpty()) FfiPlaylist(
        id = LOCAL_FAVORITES_ID,
        name = "Beğenilen Şarkılar",
        description = null,
        isLocal = true,
        trackCount = favorites.size.toUInt(),
        artworkUrl = favorites.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) },
        origin = "real"
    ) else null
    val allPlaylists = remember(localPlaylists, deezerPlaylists, likedPlaylist) {
        val liked = listOfNotNull(likedPlaylist).map { LibraryPlaylistEntry(it, addedAt = null) }
        val local = localPlaylists.filter { it.origin != "development_fixture" }.map { playlist ->
            LibraryPlaylistEntry(playlist, playlist.id.removePrefix("local:").toLongOrNull())
        }
        (liked + deezerPlaylists + local).distinctBy { it.playlist.id }
    }
    val visible = remember(allPlaylists, filter, query, sort) {
        val filtered = allPlaylists.filter { entry ->
            val playlist = entry.playlist
            val matchesFilter = when (filter) {
                PlaylistFilter.ALL -> true
                PlaylistFilter.DEEZER -> playlist.id.startsWith("deezer:")
                PlaylistFilter.LOCAL -> !playlist.id.startsWith("deezer:")
            }
            matchesFilter && playlist.name.contains(query.trim(), ignoreCase = true)
        }
        sortLibraryPlaylists(filtered, sort)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Kitaplığın",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Yeni çalma listesi", modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistFilter.entries.forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Kitaplığında ara") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Çalma listelerin",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
            )
            Box {
                TextButton(onClick = { showSort = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Sırala")
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    LibraryPlaylistSort.entries.forEach { option ->
                        val needsDate = option == LibraryPlaylistSort.ADDED_NEWEST || option == LibraryPlaylistSort.ADDED_OLDEST
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            enabled = !needsDate || allPlaylists.any { it.addedAt != null },
                            leadingIcon = { if (sort == option) Icon(Icons.Default.Check, contentDescription = null) },
                            onClick = { sort = option; sortPrefs.edit().putString("library", option.name).apply(); showSort = false }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (deezerLibraryLoading && allPlaylists.isEmpty() && query.isBlank()) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Çalma listelerin yükleniyor…", color = colors.textSecondary)
                } else {
                    Text(
                        if (allPlaylists.isNotEmpty() && (query.isNotBlank() || filter != PlaylistFilter.ALL))
                            "Eşleşen çalma listesi yok."
                        else "Henüz çalma listesi bulunamadı.",
                        color = colors.textSecondary
                    )
                }
                if (!deezerLibraryLoading && deezerUser == null && allPlaylists.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showLogin = true }, enabled = !deezerBusy) {
                        Text(if (deezerBusy) "Giriş yapılıyor…" else "Deezer hesabını bağla")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = { it.playlist.id }) { entry ->
                    PlaylistRow(entry.playlist, viewModel, onPlaylistClick)
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Yeni çalma listesi") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Liste adı") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.createPlaylist(newName.trim())
                        newName = ""
                        showCreate = false
                    }
                }, enabled = newName.isNotBlank()) { Text("Oluştur") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: FfiPlaylist,
    viewModel: MainViewModel,
    onClick: (FfiPlaylist) -> Unit
) {
    val colors = LocalPlasmaColors.current
    var artwork by remember(playlist.id, playlist.artworkUrl, playlist.trackCount) { mutableStateOf(playlist.artworkUrl) }
    LaunchedEffect(playlist.id, playlist.artworkUrl, playlist.trackCount) {
        if (artwork.isNullOrBlank() && playlist.isLocal && playlist.trackCount > 0u) {
            try {
                artwork = viewModel.getPlaylistTracks(playlist.id).firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable { onClick(playlist) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            url = artwork,
            modifier = Modifier.size(64.dp),
            fallback = if (playlist.id == LOCAL_FAVORITES_ID) Icons.Default.Favorite else Icons.AutoMirrored.Filled.PlaylistPlay,
            shape = RoundedCornerShape(6.dp),
            background = if (playlist.id == LOCAL_FAVORITES_ID) MaterialTheme.colorScheme.primary else colors.card,
            tint = if (playlist.id == LOCAL_FAVORITES_ID) MaterialTheme.colorScheme.onPrimary else colors.textSecondary
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Çalma listesi • ${playlist.trackCount} şarkı" + if (playlist.isLocal) " • Bu cihaz" else " • Deezer",
                style = MaterialTheme.typography.bodyMedium.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
