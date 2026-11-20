package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.download.DownloadStorage
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.LOCAL_FAVORITES_ID
import org.kde.plasmawave.ui.DEEZER_FAVORITES_ID
import org.kde.plasmawave.ui.LOCAL_DOWNLOADS_ID
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.PlaylistTrackEntry
import org.kde.plasmawave.ui.PlaylistTrackSort
import org.kde.plasmawave.ui.sortPlaylistTracks
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.LocalDownloadStatus
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

private sealed interface PlaylistLoadState {
    data object Loading : PlaylistLoadState
    data class Loaded(val entries: List<PlaylistTrackEntry>) : PlaylistLoadState
    data object Failed : PlaylistLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: FfiPlaylist,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDownloadPlaylist: (List<FfiTrack>, String?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var state by remember(playlist.id) {
        val cached = viewModel.getCachedPlaylistTrackEntries(playlist.id)
        mutableStateOf<PlaylistLoadState>(
            if (cached != null) PlaylistLoadState.Loaded(cached) else PlaylistLoadState.Loading
        )
    }
    var retry by remember(playlist.id) { mutableIntStateOf(0) }
    val sortPrefs = LocalContext.current.getSharedPreferences("playlist_sort", Context.MODE_PRIVATE)
    val sortKey = "tracks:${playlist.id}"
    var sort by rememberSaveable(playlist.id) {
        mutableStateOf(runCatching { PlaylistTrackSort.valueOf(sortPrefs.getString(sortKey, null) ?: "ORIGINAL") }.getOrDefault(PlaylistTrackSort.ORIGINAL))
    }
    var showSort by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var query by rememberSaveable(playlist.id) { mutableStateOf("") }
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val playlistRevisions by viewModel.playlistRevisions.collectAsState()
    val playlistRevision = playlistRevisions[playlist.id] ?: 0L
    val colors = LocalPlasmaColors.current
    val entries = (state as? PlaylistLoadState.Loaded)?.entries.orEmpty()
    val sortedEntries = remember(entries, sort) { sortPlaylistTracks(entries, sort) }
    val filteredEntries = remember(sortedEntries, query) {
        val text = query.trim()
        if (text.isBlank()) sortedEntries else sortedEntries.filter {
            it.track.title.contains(text, ignoreCase = true) || it.track.artist.contains(text, ignoreCase = true) ||
                it.track.album.contains(text, ignoreCase = true)
        }
    }
    val tracks = remember(filteredEntries) { filteredEntries.map { it.track } }
    val hasAddedDates = remember(entries) { entries.any { it.addedAt != null } }
    val downloadStatus = LocalDownloadStatus.current
    val totalCount = entries.size
    val downloadedCount = remember(entries, downloadStatus.downloadedTrackIds) {
        entries.count { entry ->
            val t = entry.track
            t.provider == "local" || DownloadStorage.normalizeTrackId(t.id) in downloadStatus.downloadedTrackIds
        }
    }
    val activeDownloadCount = remember(entries, downloadStatus.activeTrackProgress, downloadStatus.downloadedTrackIds) {
        entries.count { entry ->
            val norm = DownloadStorage.normalizeTrackId(entry.track.id)
            norm.isNotBlank() && norm !in downloadStatus.downloadedTrackIds && norm in downloadStatus.activeTrackProgress
        }
    }
    val remainingDeezerCount = remember(entries, downloadStatus.downloadedTrackIds, downloadStatus.activeTrackProgress) {
        entries.count { entry ->
            val t = entry.track
            if (t.provider != "deezer") false
            else {
                val norm = DownloadStorage.normalizeTrackId(t.id)
                norm.isNotBlank() && norm !in downloadStatus.downloadedTrackIds && norm !in downloadStatus.activeTrackProgress
            }
        }
    }
    val isFullyDownloaded = totalCount > 0 && downloadedCount == totalCount && activeDownloadCount == 0

    LaunchedEffect(playlist.id, retry, playlistRevision) {
        if (state !is PlaylistLoadState.Loaded) {
            state = PlaylistLoadState.Loading
        }
        try {
            val loaded = viewModel.getPlaylistTrackEntries(playlist.id)
            state = PlaylistLoadState.Loaded(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (state !is PlaylistLoadState.Loaded) {
                state = PlaylistLoadState.Failed
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") } },
                actions = {
                    if (playlist.id != LOCAL_FAVORITES_ID && playlist.id != DEEZER_FAVORITES_ID && playlist.id != LOCAL_DOWNLOADS_ID) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Listeyi sil", tint = colors.negative)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                val count = if (state is PlaylistLoadState.Loaded) entries.size else playlist.trackCount.toInt()
                val fallbackIcon = when (playlist.id) {
                    LOCAL_FAVORITES_ID -> Icons.Default.Favorite
                    LOCAL_DOWNLOADS_ID -> Icons.Default.DownloadDone
                    else -> Icons.AutoMirrored.Filled.PlaylistPlay
                }
                if (isLandscape) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(
                                url = playlist.artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
                                modifier = Modifier.size(96.dp),
                                fallback = fallbackIcon,
                                shape = RoundedCornerShape(10.dp),
                                background = colors.card
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                                Text(
                                    "Çalma listesi • $count şarkı" + if (playlist.isLocal) " • Bu cihaz" else " • Deezer",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                                if (totalCount > 0 && (downloadedCount > 0 || activeDownloadCount > 0)) {
                                    Spacer(Modifier.height(6.dp))
                                    PlaylistDownloadStatusPill(
                                        downloadedCount = downloadedCount,
                                        totalCount = totalCount,
                                        activeCount = activeDownloadCount,
                                        isFullyDownloaded = isFullyDownloaded
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (tracks.isNotEmpty()) {
                                        Button(onClick = { viewModel.playbackManager.playTrackList(tracks) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Çal")
                                        }
                                        OutlinedButton(onClick = { viewModel.playbackManager.playTrackList(tracks.shuffled()) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Karıştır")
                                        }
                                    }
                                    if (entries.any { it.track.provider == "deezer" }) {
                                        if (isFullyDownloaded) {
                                            FilledTonalButton(
                                                onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = Color(0xFF10B981).copy(alpha = 0.16f),
                                                    contentColor = Color(0xFF10B981)
                                                )
                                            ) {
                                                Icon(Icons.Default.DownloadDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("İndirildi")
                                            }
                                        } else if (activeDownloadCount > 0) {
                                            OutlinedButton(
                                                onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(6.dp))
                                                Text("İndiriliyor ($downloadedCount/$totalCount)")
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(if (downloadedCount > 0 && remainingDeezerCount > 0) "Eksikleri indir ($remainingDeezerCount)" else "İndir")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (entries.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                placeholder = { Text("Listede şarkı veya sanatçı ara") }, singleLine = true
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ArtworkImage(
                            url = playlist.artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
                            modifier = Modifier.size(200.dp),
                            fallback = fallbackIcon,
                            shape = RoundedCornerShape(10.dp),
                            background = colors.card
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(playlist.name, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Çalma listesi • $count şarkı" + if (playlist.isLocal) " • Bu cihaz" else " • Deezer",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium.copy(color = colors.textSecondary)
                        )
                        if (totalCount > 0 && (downloadedCount > 0 || activeDownloadCount > 0)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                PlaylistDownloadStatusPill(
                                    downloadedCount = downloadedCount,
                                    totalCount = totalCount,
                                    activeCount = activeDownloadCount,
                                    isFullyDownloaded = isFullyDownloaded
                                )
                            }
                        }
                        if (entries.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                placeholder = { Text("Listede şarkı veya sanatçı ara") }, singleLine = true
                            )
                        }
                        if (tracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { viewModel.playbackManager.playTrackList(tracks) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text("Tümünü çal")
                                }
                                OutlinedButton(onClick = { viewModel.playbackManager.playTrackList(tracks.shuffled()) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null)
                                    Text("Karıştır")
                                }
                            }
                        }
                        if (entries.any { it.track.provider == "deezer" }) {
                            if (isFullyDownloaded) {
                                FilledTonalButton(
                                    onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF10B981).copy(alpha = 0.16f),
                                        contentColor = Color(0xFF10B981)
                                    )
                                ) {
                                    Icon(Icons.Default.DownloadDone, contentDescription = null)
                                    Text("  Liste indirildi • Çevrimdışı hazır")
                                }
                            } else if (activeDownloadCount > 0) {
                                OutlinedButton(
                                    onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("  İndiriliyor ($downloadedCount / $totalCount)")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onDownloadPlaylist(entries.map { it.track }, playlist.id, playlist.name) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Text(
                                        if (downloadedCount > 0 && remainingDeezerCount > 0)
                                            "  Eksikleri indir ($remainingDeezerCount şarkı)"
                                        else
                                            "  Listeyi indir"
                                    )
                                }
                            }
                        }
                    }
                }
                    if (entries.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Şarkılar", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Box {
                                TextButton(onClick = { showSort = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(" Sırala")
                                }
                                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                    PlaylistTrackSort.entries.forEach { option ->
                                        val needsDate = option == PlaylistTrackSort.DATE_NEWEST || option == PlaylistTrackSort.DATE_OLDEST
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            enabled = !needsDate || hasAddedDates,
                                            leadingIcon = { if (sort == option) Icon(Icons.Default.Check, contentDescription = null) },
                                            onClick = { sort = option; sortPrefs.edit().putString(sortKey, option.name).apply(); showSort = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            when (state) {
                PlaylistLoadState.Loading -> item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Şarkılar yükleniyor…", color = colors.textSecondary)
                        }
                    }
                }
                PlaylistLoadState.Failed -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Çalma listesi yüklenemedi. Bağlantını kontrol edip tekrar dene.", color = colors.textSecondary, textAlign = TextAlign.Center)
                        TextButton(onClick = { retry++ }) { Text("Tekrar dene") }
                    }
                }
                is PlaylistLoadState.Loaded -> {
                    if (tracks.isEmpty()) item {
                        Text(if (query.isBlank()) "Bu çalma listesi boş." else "Aramayla eşleşen şarkı yok.", modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), color = colors.textSecondary, textAlign = TextAlign.Center)
                    }
                    else itemsIndexed(filteredEntries, key = { _, entry -> "${entry.originalIndex}:${entry.track.id}" }) { index, entry ->
                        val track = entry.track
                        TrackListItem(
                            track = track,
                            isPlaying = currentTrackId == track.id,
                            onClick = { viewModel.playbackManager.playTrackList(tracks, index) },
                            onLongClick = { viewModel.openTrackMenu(track, playlist, entry.originalIndex) }
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
    if (showDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("Listeyi sil?") },
        text = { Text("${playlist.name} kalıcı olarak silinir. Bu işlem geri alınamaz.") },
        confirmButton = { TextButton(onClick = { showDelete = false; viewModel.deletePlaylist(playlist, onBack) }) { Text("Sil", color = colors.negative) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Vazgeç") } }
    )
}

@Composable
private fun PlaylistDownloadStatusPill(
    downloadedCount: Int,
    totalCount: Int,
    activeCount: Int,
    isFullyDownloaded: Boolean
) {
    val green = Color(0xFF10B981)
    val containerColor = when {
        isFullyDownloaded -> green.copy(alpha = 0.14f)
        activeCount > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val contentColor = when {
        isFullyDownloaded -> green
        activeCount > 0 -> MaterialTheme.colorScheme.primary
        else -> LocalPlasmaColors.current.textSecondary
    }
    val label = when {
        isFullyDownloaded -> "Çevrimdışı hazır • $downloadedCount/$totalCount"
        activeCount > 0 -> "İndiriliyor • $downloadedCount/$totalCount"
        else -> "$downloadedCount/$totalCount şarkı indirildi"
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activeCount > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = Icons.Default.DownloadDone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
        }
    }
}
