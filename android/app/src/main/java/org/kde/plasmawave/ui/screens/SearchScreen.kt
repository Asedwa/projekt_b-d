package org.kde.plasmawave.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.SearchCategory
import org.kde.plasmawave.ui.components.TrackListItem
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiAlbum
import uniffi.plasmawave_core.FfiArtist
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val externalTrigger by viewModel.externalSearchTrigger.collectAsState()
    val savedQuery by viewModel.searchQuery.collectAsState()
    var query by remember { mutableStateOf(externalTrigger?.first ?: savedQuery) }
    val category by viewModel.searchCategory.collectAsState()
    val trackResults by viewModel.searchResults.collectAsState()
    val albumResults by viewModel.searchAlbums.collectAsState()
    val artistResults by viewModel.searchArtists.collectAsState()
    val playlistResults by viewModel.searchPlaylists.collectAsState()
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val customColors = LocalPlasmaColors.current

    val scope = rememberCoroutineScope()
    var selectedAlbum by remember { mutableStateOf<FfiAlbum?>(null) }
    var albumTracks by remember { mutableStateOf<List<FfiTrack>>(emptyList()) }
    var selectedArtist by remember { mutableStateOf<FfiArtist?>(null) }
    var artistTopTracks by remember { mutableStateOf<List<FfiTrack>>(emptyList()) }
    var autoOpenArtistForQuery by remember { mutableStateOf<String?>(null) }

    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(externalTrigger) {
        externalTrigger?.let { (newQuery, newCat, _) ->
            query = newQuery
            if (newCat == SearchCategory.ARTISTS) {
                autoOpenArtistForQuery = newQuery
            }
            viewModel.search(newQuery, newCat, immediate = true)
            viewModel.clearExternalSearchTrigger()
        }
    }

    LaunchedEffect(artistResults, autoOpenArtistForQuery) {
        val targetQuery = autoOpenArtistForQuery ?: return@LaunchedEffect
        if (artistResults.isNotEmpty()) {
            val match = artistResults.firstOrNull { it.name.equals(targetQuery, ignoreCase = true) }
                ?: artistResults.firstOrNull { it.name.contains(targetQuery, ignoreCase = true) }
                ?: artistResults.first()
            autoOpenArtistForQuery = null
            selectedArtist = match
            artistTopTracks = emptyList()
            scope.launch {
                artistTopTracks = viewModel.getArtistTopTracks(match.id)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.search(it, category)
            },
            placeholder = {
                Text(
                    when (category) {
                        SearchCategory.TRACKS -> "Search songs & previews..."
                        SearchCategory.ALBUMS -> "Search Deezer albums..."
                        SearchCategory.ARTISTS -> "Search Deezer artists..."
                        SearchCategory.PLAYLISTS -> "Search Deezer playlists..."
                    }
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (customColors.legacyUiMode) MaterialTheme.colorScheme.primary else customColors.textSecondary
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        viewModel.search("", category)
                    }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = customColors.card,
                unfocusedContainerColor = customColors.card,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = customColors.border
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Refreezer-Inspired Category Tabs
        LazyRow(modifier = Modifier.fillMaxWidth()) {
            val categories = listOf(
                SearchCategory.TRACKS to "Tracks",
                SearchCategory.ALBUMS to "Albums",
                SearchCategory.ARTISTS to "Artists",
                SearchCategory.PLAYLISTS to "Playlists"
            )
            items(categories) { (cat, label) ->
                FilterChip(
                    selected = category == cat,
                    onClick = {
                        viewModel.setCategory(cat)
                        if (query.isNotBlank()) {
                            viewModel.search(query, cat, immediate = true)
                        }
                    },
                    label = { Text(label) },
                    leadingIcon = {
                        val icon = when (cat) {
                            SearchCategory.TRACKS -> Icons.Default.MusicNote
                            SearchCategory.ALBUMS -> Icons.Default.Album
                            SearchCategory.ARTISTS -> Icons.Default.Person
                            SearchCategory.PLAYLISTS -> Icons.Default.Folder
                        }
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = customColors.card,
                        labelColor = customColors.textSecondary
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (query.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Search across Deezer catalog & local library",
                    style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                )
            }
        } else {
            when (category) {
                SearchCategory.TRACKS -> {
                    if (trackResults.isEmpty()) {
                        EmptyResultBox(query, customColors)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(trackResults, key = { _, it -> it.id }) { index, track ->
                                TrackListItem(
                                    track = track,
                                    isPlaying = currentTrackId == track.id,
                                    onClick = { viewModel.playbackManager.playTrackList(trackResults, index) },
                                    onLongClick = { viewModel.openTrackMenu(track) }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(100.dp)) }
                        }
                    }
                }
                SearchCategory.ALBUMS -> {
                    if (albumResults.isEmpty()) {
                        EmptyResultBox(query, customColors)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(albumResults, key = { it.id }) { album ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedAlbum = album
                                            scope.launch {
                                                albumTracks = viewModel.getAlbumTracks(album.id).map { track ->
                                                    track.copy(
                                                        album = track.album.ifBlank { album.title },
                                                        artworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() } ?: album.coverUrl
                                                    )
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = customColors.card)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkImage(
                                            url = album.coverUrl,
                                            modifier = Modifier.size(48.dp),
                                            fallback = Icons.Default.Album,
                                            background = customColors.border
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = album.title,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${album.artistName} • ${album.nbTracks} tracks",
                                                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                            )
                                        }
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(100.dp)) }
                        }
                    }
                }
                SearchCategory.ARTISTS -> {
                    if (artistResults.isEmpty()) {
                        EmptyResultBox(query, customColors)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(artistResults, key = { it.id }) { artist ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedArtist = artist
                                            scope.launch {
                                                artistTopTracks = viewModel.getArtistTopTracks(artist.id)
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = customColors.card)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkImage(
                                            url = artist.pictureUrl,
                                            modifier = Modifier.size(48.dp),
                                            fallback = Icons.Default.Person,
                                            shape = RoundedCornerShape(24.dp),
                                            background = MaterialTheme.colorScheme.primary,
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "Deezer Artist",
                                                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                            )
                                        }
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(100.dp)) }
                        }
                    }
                }
                SearchCategory.PLAYLISTS -> {
                    if (playlistResults.isEmpty()) {
                        EmptyResultBox(query, customColors)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(playlistResults, key = { it.id }) { pl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onPlaylistClick(pl) },
                                    colors = CardDefaults.cardColors(containerColor = customColors.card)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkImage(
                                            url = pl.artworkUrl,
                                            modifier = Modifier.size(48.dp),
                                            fallback = Icons.Default.CloudQueue,
                                            background = customColors.border,
                                            tint = customColors.positive
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pl.name,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${pl.trackCount} tracks • Deezer Playlist",
                                                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                            )
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
    }

    // Album Detail Sheet
    if (selectedAlbum != null) {
        val album = selectedAlbum!!
        ModalBottomSheet(
            onDismissRequest = { selectedAlbum = null },
            sheetState = bottomSheetState,
            containerColor = customColors.card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(url = album.coverUrl, modifier = Modifier.size(56.dp), fallback = Icons.Default.Album, background = customColors.border)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = album.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(text = album.artistName, style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary))
                    }
                    if (albumTracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.playbackManager.playTrackList(albumTracks, 0)
                                selectedAlbum = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play All")
                        }
                    }
                }

                HorizontalDivider(color = customColors.divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                if (albumTracks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(text = "Loading album tracks...", color = customColors.textSecondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(albumTracks, key = { _, it -> it.id }) { index, track ->
                            TrackListItem(
                                track = track,
                                isPlaying = currentTrackId == track.id,
                                onClick = { viewModel.playbackManager.playTrackList(albumTracks, index) },
                                onLongClick = { viewModel.openTrackMenu(track) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Artist Detail Sheet
    if (selectedArtist != null) {
        val artist = selectedArtist!!
        ModalBottomSheet(
            onDismissRequest = { selectedArtist = null },
            sheetState = bottomSheetState,
            containerColor = customColors.card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(url = artist.pictureUrl, modifier = Modifier.size(56.dp), fallback = Icons.Default.Person, shape = RoundedCornerShape(28.dp), background = customColors.border)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = artist.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(text = "Top Tracks", style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary))
                    }
                    if (artistTopTracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.playbackManager.playTrackList(artistTopTracks, 0)
                                selectedArtist = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play All")
                        }
                    }
                }

                HorizontalDivider(color = customColors.divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                if (artistTopTracks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(text = "Loading top tracks...", color = customColors.textSecondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(artistTopTracks, key = { _, it -> it.id }) { index, track ->
                            TrackListItem(
                                track = track,
                                isPlaying = currentTrackId == track.id,
                                onClick = { viewModel.playbackManager.playTrackList(artistTopTracks, index) },
                                onLongClick = { viewModel.openTrackMenu(track) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResultBox(query: String, customColors: org.kde.plasmawave.theme.PlasmaCustomColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No results found for '$query'",
            style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
        )
    }
}
