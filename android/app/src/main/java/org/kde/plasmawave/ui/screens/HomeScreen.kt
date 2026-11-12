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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.PinnedItem
import org.kde.plasmawave.ui.DEEZER_FAVORITES_ID
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val localPlaylists by viewModel.playlists.collectAsState()
    val playerState by viewModel.playbackManager.uiState.collectAsState()
    val customColors = LocalPlasmaColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            // KDE Plasma Breeze Hero Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = customColors.card)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PlasmaWave",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "KDE Breeze Dark • Rust Engine",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = customColors.textSecondary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (pins.isNotEmpty()) {
            item {
                Text("Sabitlediklerin", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(pins, key = { "pin:${it.id}" }) { pin ->
                when (pin) {
                    is PinnedItem.Track -> TrackListItem(
                        track = pin.track,
                        isPlaying = playerState.currentEntry?.track?.id == pin.track.id,
                        onClick = { viewModel.playbackManager.playTrackList(listOf(pin.track)) },
                        onLongClick = { viewModel.openTrackMenu(pin.track) }
                    )
                    is PinnedItem.Playlist -> {
                        val playlist = (deezerPlaylists.map { it.playlist } + localPlaylists)
                            .firstOrNull { it.id == pin.playlist.id } ?: pin.playlist
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { onPlaylistClick(playlist) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(url = playlist.artworkUrl, modifier = Modifier.size(56.dp),
                                fallback = Icons.AutoMirrored.Filled.PlaylistPlay, shape = RoundedCornerShape(6.dp),
                                background = customColors.card, tint = customColors.textSecondary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text("Çalma listesi • ${playlist.trackCount} şarkı", color = customColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.togglePinnedPlaylist(playlist) }) {
                                Icon(Icons.Default.PushPin, contentDescription = "Sabitlemeyi kaldır")
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        // Favorites quick play section
        if (favorites.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.playbackManager.playTrackList(favorites) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(favorites.take(5), key = { it.id }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = playerState.currentEntry?.track?.id == track.id,
                    onClick = { viewModel.playbackManager.playTrackList(favorites, favorites.indexOf(track)) },
                    onLongClick = { viewModel.openTrackMenu(track) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Deezer Loved Tracks (Sevdiğim Şarkılar) Quick Play
        if (deezerFavorites.isNotEmpty()) {
            item {
                val likedPlaylist = FfiPlaylist(
                    id = DEEZER_FAVORITES_ID, name = "Deezer • Sevdiğim Şarkılar", description = null,
                    isLocal = false, trackCount = deezerFavorites.size.toUInt(),
                    artworkUrl = deezerFavorites.firstNotNullOfOrNull { it.artworkUrl }, origin = "real"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Deezer • Sevdiğim Şarkılar (${deezerFavorites.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.playbackManager.playTrackList(deezerFavorites) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tümünü Çal")
                    }
                }
                TextButton(onClick = { onPlaylistClick(likedPlaylist) }) { Text("Tüm şarkıları gör ve sırala") }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(deezerFavorites.take(6), key = { "home_dz_${it.id}" }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = playerState.currentEntry?.track?.id == track.id,
                    onClick = { viewModel.playbackManager.playTrackList(deezerFavorites, deezerFavorites.indexOf(track)) },
                    onLongClick = { viewModel.openTrackMenu(track) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
