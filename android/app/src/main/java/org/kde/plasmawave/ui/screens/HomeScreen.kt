package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kde.plasmawave.R
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.DEEZER_FAVORITES_ID
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.PinnedItem
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.HeaderLogoStyle
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist

@Composable
fun HomeScreen(viewModel: MainViewModel, onPlaylistClick: (FfiPlaylist) -> Unit, onDownloadsClick: () -> Unit,
               modifier: Modifier = Modifier) {
    val favorites by viewModel.favorites.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val flowLoading by viewModel.flowLoading.collectAsState()
    val flowHeroCardEnabled by viewModel.flowHeroCardEnabled.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val headerLogoStyle by viewModel.headerLogoStyle.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val localPlaylists by viewModel.playlists.collectAsState()
    val downloads by viewModel.fullDownloads.collectAsState()
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val colors = LocalPlasmaColors.current
    val likedPlaylist = if (deezerFavorites.isNotEmpty()) FfiPlaylist(DEEZER_FAVORITES_ID, "Sevdiğim Şarkılar", null, false,
        deezerFavorites.size.toUInt(), null, "real") else null
    val quick = remember(pins, likedPlaylist, deezerPlaylists, localPlaylists, flowQuickAccessPinned, deezerUser) {
        val currentPlaylists = (deezerPlaylists.map { it.playlist } + localPlaylists).associateBy { it.id }
        val refreshed = pins.map { pin ->
            if (pin is PinnedItem.Playlist) PinnedItem.Playlist(currentPlaylists[pin.playlist.id] ?: pin.playlist) else pin
        }
        val flowItem = if (flowQuickAccessPinned && deezerUser != null) listOf(PinnedItem.Flow) else emptyList()
        val likedItem = listOfNotNull(likedPlaylist?.let { PinnedItem.Playlist(it) })
        (flowItem + likedItem + refreshed).distinctBy { it.id }
    }
    val active = downloads.count { it.state == "queued" || it.state == "running" }
    val completed = downloads.count { it.state == "completed" }
    val pinnedIds = remember(pins, flowQuickAccessPinned) {
        val set = pins.mapTo(HashSet(pins.size.coerceAtLeast(1) + 1)) { it.id }
        if (flowQuickAccessPinned) set.add(PinnedItem.Flow.id)
        set
    }
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val bgBrush = remember(primary, bg) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.16f), bg, bg))
    }
    LazyColumn(modifier = modifier.fillMaxSize().background(bgBrush), contentPadding = PaddingValues(bottom = 80.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (headerLogoStyle) {
                    HeaderLogoStyle.TWILIGHT -> {
                        Image(
                            painter = painterResource(R.drawable.ic_header_twilight),
                            contentDescription = "Twilight Sparkle",
                            modifier = Modifier
                                .height(54.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    HeaderLogoStyle.PINKIE -> {
                        Image(
                            painter = painterResource(R.drawable.ic_header_pinkie),
                            contentDescription = "Pinkie Pie",
                            modifier = Modifier
                                .height(54.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    HeaderLogoStyle.SYM_S -> {
                        Image(
                            painter = painterResource(R.drawable.ic_header_sym_s),
                            contentDescription = "S Logo",
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    HeaderLogoStyle.ORIGINAL -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "projekt_b-d",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDownloadsClick) {
                    if (active > 0) {
                        BadgedBox(badge = { Badge { Text("$active") } }) {
                            Icon(Icons.Default.Download, contentDescription = "İndirmeler")
                        }
                    } else {
                        Icon(Icons.Default.Download, contentDescription = "İndirmeler")
                    }
                }
            }
        }
        if (deezerUser != null && flowHeroCardEnabled) {
            item {
                DeezerFlowHeroCard(
                    isLoading = flowLoading,
                    onStartFlow = { viewModel.startFlow() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Hızlı erişim", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Sevdiğin şarkılar ve sabitlediklerin", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(10.dp))
        }
        if (quick.isNotEmpty()) item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val isLandscape = maxWidth > 600.dp
                val cardSize = if (isLandscape) 124.dp else (maxWidth - 48.dp) / 3
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quick.chunked(9), key = { page -> page.first().id }) { page ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            page.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { pin -> QuickCard(pin, pin.id in pinnedIds, viewModel, onPlaylistClick, cardSize) }
                                }
                            }
                        }
                    }
                }
            }
        } else item {
            Text("Şarkı veya listelerin menüsünden ana sayfaya sabitleyebilirsin.",
                color = colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
        }
        if (deezerFavorites.isNotEmpty()) {
            item {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Senin için", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Deezer • Sevdiğin şarkılar", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { likedPlaylist?.let(onPlaylistClick) }) { Text("Tümünü gör") }
                }
            }
            itemsIndexed(deezerFavorites.take(6), key = { _, it -> "loved:${it.id}" }) { index, track ->
                TrackListItem(track, isPlaying = currentTrackId == track.id,
                    onClick = { viewModel.playbackManager.playTrackList(deezerFavorites, index) },
                    onLongClick = { viewModel.openTrackMenu(track) })
            }
        }
        if (favorites.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)); Text("Bu cihazda beğendiklerin", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            itemsIndexed(favorites.take(5), key = { _, it -> "localfav:${it.id}" }) { index, track ->
                TrackListItem(track, isPlaying = currentTrackId == track.id,
                    onClick = { viewModel.playbackManager.playTrackList(favorites, index) },
                    onLongClick = { viewModel.openTrackMenu(track) })
            }
        }
    }
}

private val CardBottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))

@Composable
private fun QuickCard(pin: PinnedItem, pinned: Boolean, viewModel: MainViewModel, onPlaylistClick: (FfiPlaylist) -> Unit, size: androidx.compose.ui.unit.Dp) {
    var menu by remember(pin.id) { mutableStateOf(false) }
    val title = when (pin) {
        is PinnedItem.Track -> pin.track.title
        is PinnedItem.Playlist -> pin.playlist.name
        is PinnedItem.Flow -> "Deezer Flow"
    }
    val art = when (pin) {
        is PinnedItem.Track -> pin.track.artworkUrl
        is PinnedItem.Playlist -> pin.playlist.artworkUrl
        is PinnedItem.Flow -> null
    }
    val primary = MaterialTheme.colorScheme.primary
    val deezerFavBrush = remember(primary) {
        Brush.linearGradient(listOf(primary, Color(0xFF7B63DC), Color(0xFFE993BA)))
    }
    val flowGradient = remember {
        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316)))
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).clickable {
        when (pin) {
            is PinnedItem.Track -> viewModel.playbackManager.playTrackList(listOf(pin.track))
            is PinnedItem.Playlist -> onPlaylistClick(pin.playlist)
            is PinnedItem.Flow -> viewModel.startFlow()
        }
    }) {
        if (pin is PinnedItem.Flow) {
            Box(Modifier.fillMaxSize().background(flowGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AllInclusive, contentDescription = "Deezer Flow", tint = Color.White, modifier = Modifier.size(38.dp))
            }
        } else if (pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID) {
            Box(Modifier.fillMaxSize().background(deezerFavBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        } else {
            ArtworkImage(art, modifier = Modifier.fillMaxSize(), fallback = if (pin is PinnedItem.Playlist) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.MusicNote)
        }
        Box(Modifier.fillMaxWidth().height(52.dp).align(Alignment.BottomCenter).background(CardBottomScrim))
        Text(title, modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, bottom = 6.dp), color = Color.White,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { if (pin is PinnedItem.Track) viewModel.openTrackMenu(pin.track) else menu = true }, modifier = Modifier.size(34.dp)) {
                Icon(if (pinned) Icons.Default.PushPin else Icons.Default.MoreVert, contentDescription = "Hızlı erişim seçenekleri", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (menu) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (pin is PinnedItem.Flow) {
                        DropdownMenuItem(
                            text = { Text("Hızlı erişimden kaldır") },
                            leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                            onClick = {
                                viewModel.setFlowQuickAccessPinned(false)
                                menu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Flow başlat") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = {
                                viewModel.startFlow()
                                menu = false
                            }
                        )
                    } else {
                        DropdownMenuItem(text = { Text(if (pinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") }, onClick = {
                            if (pin is PinnedItem.Playlist) viewModel.togglePinnedPlaylist(pin.playlist); menu = false
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeezerFlowHeroCard(
    isLoading: Boolean,
    onStartFlow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val flowGradient = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF8B5CF6),
                Color(0xFFEC4899),
                Color(0xFFF97316)
            )
        )
    }
    Surface(
        onClick = onStartFlow,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1E1B2E),
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF8B5CF6).copy(alpha = 0.28f),
                            Color(0xFFEC4899).copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(flowGradient, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AllInclusive,
                            contentDescription = "Flow",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Deezer Flow",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFEC4899).copy(alpha = 0.25f)
                            ) {
                                Text(
                                    "AI AKIŞ",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                    color = Color(0xFFF472B6),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Zevkine göre sonsuz müzik akışı",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.70f)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    onClick = onStartFlow,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1E1B2E)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF8B5CF6),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Flow Başlat", modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}
