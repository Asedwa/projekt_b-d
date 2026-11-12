package org.kde.plasmawave.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.ui.components.TrackListItem
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiPlaylist

@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tracks", "Playlists", "Favorites", "Deezer")

    val localTracks by viewModel.localTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val deezerFlow by viewModel.deezerFlow.collectAsState()
    val playerState by viewModel.playbackManager.uiState.collectAsState()
    val customColors = LocalPlasmaColors.current

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showDeezerLogin by remember { mutableStateOf(false) }
    val deezerBusy by viewModel.deezerBusy.collectAsState()
    val deezerLoginError by viewModel.deezerLoginError.collectAsState()
    if (showDeezerLogin) {
        LaunchedEffect(deezerUser) { if (deezerUser != null) showDeezerLogin = false }
        DeezerLoginDialog(
            busy = deezerBusy,
            error = deezerLoginError,
            onDismiss = { showDeezerLogin = false },
            onLogin = { arl -> viewModel.connectDeezerArl(arl) }
        )
    }
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showCreatePlaylistDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Create Playlist")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // KDE Breeze Tab Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = customColors.card,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    // Local Tracks Tab
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.scanDeviceMusic() },
                                colors = ButtonDefaults.buttonColors(containerColor = customColors.card),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Scan Device for Audio Files",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (localTracks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No local tracks indexed. Tap scan above to find audio on this device.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                                    )
                                }
                            }
                        } else {
                            items(localTracks, key = { it.id }) { track ->
                                TrackListItem(
                                    track = track,
                                    isPlaying = playerState.currentEntry?.track?.id == track.id,
                                    onClick = { viewModel.playbackManager.playTrackList(localTracks, localTracks.indexOf(track)) },
                                    onLongClick = { viewModel.openTrackMenu(track) }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(100.dp)) }
                    }
                }
                1 -> {
                    // Playlists Tab (Local + Deezer Playlists)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(12.dp)) }

                        if (playlists.isEmpty() && deezerPlaylists.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No playlists found. Create one with '+' or connect Deezer in Settings.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                                    )
                                }
                            }
                        } else {
                            if (playlists.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "LOCAL PLAYLISTS",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = customColors.textSecondary),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistRowItem(pl, customColors, viewModel, onPlaylistClick)
                                }
                            }

                            if (deezerPlaylists.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "DEEZER PLAYLISTS (${deezerUser?.name ?: "Account"})",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                items(deezerPlaylists, key = { it.id }) { pl ->
                                    PlaylistRowItem(pl, customColors, viewModel, onPlaylistClick)
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(100.dp)) }
                    }
                }
                2 -> {
                    // Favorites Tab (Local + Deezer Favorites)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(12.dp)) }

                        if (favorites.isEmpty() && deezerFavorites.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No favorite tracks saved yet.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                                    )
                                }
                            }
                        } else {
                            if (favorites.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "SAVED FAVORITES",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = customColors.textSecondary),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                items(favorites, key = { it.id }) { track ->
                                    TrackListItem(
                                        track = track,
                                        isPlaying = playerState.currentEntry?.track?.id == track.id,
                                        onClick = { viewModel.playbackManager.playTrackList(favorites, favorites.indexOf(track)) },
                                        onLongClick = { viewModel.openTrackMenu(track) }
                                    )
                                }
                            }

                            if (deezerFavorites.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "DEEZER FAVORITES (${deezerFavorites.size} tracks)",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                items(deezerFavorites, key = { it.id }) { track ->
                                    TrackListItem(
                                        track = track,
                                        isPlaying = playerState.currentEntry?.track?.id == track.id,
                                        onClick = { viewModel.playbackManager.playTrackList(deezerFavorites, deezerFavorites.indexOf(track)) },
                                        onLongClick = { viewModel.openTrackMenu(track) }
                                    )
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(100.dp)) }
                    }
                }
                3 -> {
                    // Deezer Hub Tab
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(12.dp)) }

                        if (deezerUser != null) {
                            val user = deezerUser!!
                            // 1. User Profile Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = customColors.card),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(54.dp)
                                                    .clip(RoundedCornerShape(27.dp))
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = user.name,
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(if (user.isPremium) MaterialTheme.colorScheme.primary else customColors.border)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            if (user.isPremium) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Star,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                                    modifier = Modifier.size(12.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(3.dp))
                                                            }
                                                            Text(
                                                                text = user.statusLabel,
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    color = if (user.isPremium) MaterialTheme.colorScheme.onPrimary else customColors.textSecondary,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = if (user.email != null) "${user.email} • ID: ${user.id}" else "ID: ${user.id} • ${user.nbPlaylists} Çalma Listesi",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Button(
                                                onClick = { viewModel.refreshDeezerData() },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Yenile")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedButton(
                                                onClick = { viewModel.disconnectDeezer() },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(imageVector = Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Çıkış Yap")
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // 2. Hero: Sevdiğim Şarkılar (Loved Tracks)
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            if (deezerFavorites.isNotEmpty()) {
                                                viewModel.playbackManager.playTrackList(deezerFavorites, 0)
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = customColors.card)
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Favorite,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(30.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Sevdiğim Şarkılar",
                                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "${deezerFavorites.size} Favori Parça • Deezer Library",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                                                )
                                            }
                                        }

                                        if (deezerFavorites.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                Button(
                                                    onClick = { viewModel.playbackManager.playTrackList(deezerFavorites, 0) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Tümünü Çal")
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        viewModel.playbackManager.playTrackList(deezerFavorites.shuffled(), 0)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = customColors.border),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Karıştır", color = MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // 3. Deezer Flow Card (if available)
                            if (deezerFlow.isNotEmpty()) {
                                item {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp)),
                                        colors = CardDefaults.cardColors(containerColor = customColors.card)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(customColors.border),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.GraphicEq,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Deezer Flow",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "${deezerFlow.size} Parça • Zevkinize Özel Akış",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                                )
                                            }
                                            Button(
                                                onClick = { viewModel.playbackManager.playTrackList(deezerFlow, 0) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Başlat")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }

                            // 4. Playlists Section
                            if (deezerPlaylists.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "DEEZER ÇALMA LİSTELERİNİZ",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = customColors.textSecondary),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                                items(deezerPlaylists, key = { it.id }) { pl ->
                                    PlaylistRowItem(pl, customColors, viewModel, onPlaylistClick)
                                }
                            }

                            // 5. Sevdiğim Şarkılar Listesi (Favorites track list)
                            if (deezerFavorites.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "SEVDİĞİM ŞARKILAR LİSTESİ (${deezerFavorites.size})",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = customColors.textSecondary),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                                items(deezerFavorites, key = { it.id }) { track ->
                                    TrackListItem(
                                        track = track,
                                        isPlaying = playerState.currentEntry?.track?.id == track.id,
                                        onClick = { viewModel.playbackManager.playTrackList(deezerFavorites, deezerFavorites.indexOf(track)) },
                                        onLongClick = { viewModel.openTrackMenu(track) }
                                    )
                                }
                            }
                        } else {
                            item {
                                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = customColors.card), shape = RoundedCornerShape(14.dp)) {
                                    Column(modifier = Modifier.padding(20.dp)) {
                                        Text("Deezer Hesabına Giriş Yap", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Tam şarkılar, sevdiğin şarkılar ve çalma listelerin için giriş yap. ARL ile giriş seçeneğini Ayarlar'da bulabilirsin.", style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary))
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(onClick = { showDeezerLogin = true }, enabled = !deezerBusy, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (deezerBusy) "Giriş yapılıyor…" else "Deezer ile Giriş Yap")
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(100.dp)) }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlaylistRowItem(
    pl: FfiPlaylist,
    customColors: org.kde.plasmawave.theme.PlasmaCustomColors,
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit
) {
    var artworkUrl by remember(pl.id, pl.artworkUrl, pl.trackCount) { mutableStateOf(pl.artworkUrl) }
    LaunchedEffect(pl.id, pl.artworkUrl, pl.trackCount) {
        if (artworkUrl.isNullOrBlank() && pl.isLocal && pl.trackCount > 0u) {
            artworkUrl = viewModel.getPlaylistTracks(pl.id).firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onPlaylistClick(pl) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            url = artworkUrl,
            modifier = Modifier.size(48.dp),
            fallback = if (pl.isLocal) Icons.Default.Folder else Icons.Default.CloudQueue,
            shape = RoundedCornerShape(8.dp),
            background = customColors.card,
            tint = if (pl.isLocal) MaterialTheme.colorScheme.primary else customColors.positive
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pl.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = if (pl.isLocal) "${pl.trackCount} tracks • Local" else "${pl.trackCount} tracks • Deezer Remote",
                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
            )
        }
    }
}
