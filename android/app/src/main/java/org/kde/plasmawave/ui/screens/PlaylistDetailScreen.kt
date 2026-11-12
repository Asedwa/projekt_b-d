package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.TrackListItem
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: FfiPlaylist,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tracks by remember { mutableStateOf<List<FfiTrack>>(emptyList()) }
    val playerState by viewModel.playbackManager.uiState.collectAsState()
    val customColors = LocalPlasmaColors.current
    val core = PlasmaWaveApp.core

    LaunchedEffect(playlist.id) {
        try {
            tracks = viewModel.getPlaylistTracks(playlist.id)
        } catch (_: Exception) {}
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(playlist.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (playlist.isLocal) {
                        IconButton(onClick = {
                            core.deletePlaylist(playlist.id)
                            viewModel.refreshPlaylists()
                            onBack()
                        }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Playlist", tint = customColors.negative)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(
                        url = playlist.artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
                        modifier = Modifier.size(72.dp),
                        fallback = Icons.Default.Folder,
                        shape = RoundedCornerShape(12.dp),
                        background = customColors.card
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${tracks.size} tracks • ${if (playlist.isLocal) "Local Playlist" else "Remote Playlist"}",
                            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                        )
                    }

                    if (tracks.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.playbackManager.playTrackList(tracks) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play All")
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "This playlist is empty.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                        )
                    }
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        isPlaying = playerState.currentEntry?.track?.id == track.id,
                        onClick = { viewModel.playbackManager.playTrackList(tracks, tracks.indexOf(track)) },
                        onLongClick = { viewModel.openTrackMenu(track) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
}
