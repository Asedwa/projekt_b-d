package org.kde.plasmawave.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import org.kde.plasmawave.R
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.DEEZER_FAVORITES_ID
import org.kde.plasmawave.ui.LOCAL_DOWNLOADS_ID
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.PinnedItem
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.HeaderLogoStyle
import org.kde.plasmawave.ui.components.HomeFeedType
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    onDownloadsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()
    val deezerFavorites by viewModel.deezerFavorites.collectAsState()
    val deezerUser by viewModel.deezerUser.collectAsState()
    val flowLoading by viewModel.flowLoading.collectAsState()
    val flowHeroCardEnabled by viewModel.flowHeroCardEnabled.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val headerLogoStyle by viewModel.headerLogoStyle.collectAsState()
    val customLogoTimestamp by viewModel.customHeaderLogoTimestamp.collectAsState()
    val homeFeedType by viewModel.homeFeedType.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val mostPlayedTracks by viewModel.mostPlayedTracks.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val localPlaylists by viewModel.playlists.collectAsState()
    val downloads by viewModel.fullDownloads.collectAsState()
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val colors = LocalPlasmaColors.current
    val context = LocalContext.current

    val customLogoBitmap = remember(customLogoTimestamp, headerLogoStyle) {
        if (headerLogoStyle == HeaderLogoStyle.CUSTOM) {
            val file = File(context.filesDir, "custom_header_logo.png")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } else null
    }

    val likedPlaylist = if (deezerFavorites.isNotEmpty()) FfiPlaylist(
        DEEZER_FAVORITES_ID, "Sevdiğim Şarkılar", null, false,
        deezerFavorites.size.toUInt(), null, "real"
    ) else null

    val downloadedPlaylist = remember(downloadedTracks) {
        FfiPlaylist(
            id = LOCAL_DOWNLOADS_ID,
            name = "İndirilen Müzikler",
            description = "Cihaza indirilen çevrimdışı şarkılar",
            isLocal = true,
            trackCount = downloadedTracks.size.toUInt(),
            artworkUrl = null,
            origin = "real"
        )
    }

    val quick = remember(pins, likedPlaylist, downloadedPlaylist, downloadsQuickAccessPinned, deezerPlaylists, localPlaylists, flowQuickAccessPinned, deezerUser) {
        val currentPlaylists = (deezerPlaylists.map { it.playlist } + localPlaylists).associateBy { it.id }
        val refreshed = pins.map { pin ->
            if (pin is PinnedItem.Playlist) PinnedItem.Playlist(currentPlaylists[pin.playlist.id] ?: pin.playlist) else pin
        }
        val flowItem = if (flowQuickAccessPinned && deezerUser != null) listOf(PinnedItem.Flow) else emptyList()
        val downloadsItem = if (downloadsQuickAccessPinned) listOf(PinnedItem.Playlist(downloadedPlaylist)) else emptyList()
        val likedItem = listOfNotNull(likedPlaylist?.let { PinnedItem.Playlist(it) })
        (flowItem + downloadsItem + likedItem + refreshed).distinctBy { it.id }
    }
    val active = downloads.count { it.state == "queued" || it.state == "running" }
    val pinnedIds = remember(pins, flowQuickAccessPinned, downloadsQuickAccessPinned) {
        val set = pins.mapTo(HashSet(pins.size.coerceAtLeast(1) + 2)) { it.id }
        if (flowQuickAccessPinned) set.add(PinnedItem.Flow.id)
        if (downloadsQuickAccessPinned) set.add("playlist:$LOCAL_DOWNLOADS_ID")
        set
    }

    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val bgBrush = remember(primary, bg) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.16f), bg, bg))
    }

    val expandedHeaderHeight = 156.dp
    val collapsedHeaderHeight = 60.dp
    val collapseRangeDp = expandedHeaderHeight - collapsedHeaderHeight // 96.dp

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val maxCollapseDistancePx = with(density) { collapseRangeDp.toPx() }

    val rawCollapseFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / maxCollapseDistancePx).coerceIn(0f, 1f)
        }
    }

    val collapseFraction by animateFloatAsState(
        targetValue = rawCollapseFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "headerCollapseFraction"
    )

    // Smoothstep easing for balanced horizontal & size transition
    val smoothFraction = collapseFraction * collapseFraction * (3f - 2f * collapseFraction)
    val headerHeight = lerp(expandedHeaderHeight, collapsedHeaderHeight, collapseFraction)
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBarTop + collapsedHeaderHeight, bottom = 80.dp)
        ) {
            // Item 0: Dedicated collapse range spacer so firstVisibleItemIndex stays 0 throughout 0..collapseRangeDp
            item(key = "header_collapse_spacer") {
                Spacer(Modifier.fillMaxWidth().height(collapseRangeDp))
            }

            if (deezerUser != null && flowHeroCardEnabled) {
                item(key = "flow_hero_card") {
                    DeezerFlowHeroCard(
                        isLoading = flowLoading,
                        onStartFlow = { viewModel.startFlow() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            item(key = "quick_access_header") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hızlı erişim",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Sevdiğin şarkılar ve sabitlediklerin",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(10.dp))
            }
            if (quick.isNotEmpty()) item(key = "quick_access_grid") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val isLandscape = maxWidth > 600.dp
                    val cardSize = if (isLandscape) 124.dp else (maxWidth - 48.dp) / 3
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(quick.chunked(9), key = { page -> page.first().id }) { page ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                page.chunked(3).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { pin ->
                                            QuickCard(pin, pin.id in pinnedIds, viewModel, onPlaylistClick, cardSize)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else item(key = "quick_access_empty") {
                Text(
                    "Şarkı veya listelerin menüsünden ana sayfaya sabitleyebilirsin.",
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }

            // Customizable Feed (Senin İçin / Son Çalınanlar / En Çok Dinlenenler)
            val feedTracks = when (homeFeedType) {
                HomeFeedType.FOR_YOU -> if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites
                HomeFeedType.RECENTLY_PLAYED -> if (recentTracks.isNotEmpty()) recentTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
                HomeFeedType.MOST_PLAYED -> if (mostPlayedTracks.isNotEmpty()) mostPlayedTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
            }

            if (feedTracks.isNotEmpty()) {
                item(key = "feed_header_${homeFeedType.name}") {
                    Spacer(Modifier.height(28.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                homeFeedType.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                homeFeedType.subtitle,
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (likedPlaylist != null && homeFeedType == HomeFeedType.FOR_YOU) {
                            TextButton(onClick = { onPlaylistClick(likedPlaylist) }) { Text("Tümünü gör") }
                        }
                    }
                }
                itemsIndexed(feedTracks.take(6), key = { _, it -> "feed:${homeFeedType.name}:${it.id}" }) { index, track ->
                    TrackListItem(
                        track,
                        isPlaying = currentTrackId == track.id,
                        onClick = { viewModel.playbackManager.playTrackList(feedTracks, index) },
                        onLongClick = { viewModel.openTrackMenu(track) }
                    )
                }
            }

            if (favorites.isNotEmpty() && homeFeedType == HomeFeedType.FOR_YOU && deezerFavorites.isNotEmpty()) {
                item(key = "local_fav_header") {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Bu cihazda beğendiklerin",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                itemsIndexed(favorites.take(5), key = { _, it -> "localfav:${it.id}" }) { index, track ->
                    TrackListItem(
                        track,
                        isPlaying = currentTrackId == track.id,
                        onClick = { viewModel.playbackManager.playTrackList(favorites, index) },
                        onLongClick = { viewModel.openTrackMenu(track) }
                    )
                }
            }
        }

        // Pinned Collapsing Header with balanced, jump-free coordinate interpolation
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = if (collapseFraction > 0.02f) {
                bg.copy(alpha = (collapseFraction * 0.98f).coerceIn(0f, 0.98f))
            } else Color.Transparent
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(headerHeight)
            ) {
                val maxWidthDp = maxWidth
                val logoSize = lerp(80.dp, 38.dp, smoothFraction)
                val expandedLogoX = (maxWidthDp - logoSize) / 2
                val collapsedLogoX = 16.dp
                val logoXDp = lerp(expandedLogoX, collapsedLogoX, smoothFraction)

                val expandedLogoY = 12.dp
                val collapsedLogoY = (collapsedHeaderHeight - logoSize) / 2
                val logoYDp = lerp(expandedLogoY, collapsedLogoY, smoothFraction)

                val logoXPx = with(density) { logoXDp.toPx().roundToInt() }
                val logoYPx = with(density) { logoYDp.toPx().roundToInt() }

                // Underneath centered title ("projekt_b-d") when expanded
                val bottomTitleAlpha = (1f - collapseFraction * 2.2f).coerceIn(0f, 1f)
                val bottomTitleScale = 0.85f + 0.15f * bottomTitleAlpha
                val bottomTitleWidthDp = 140.dp
                val bottomTitleXDp = logoXDp + (logoSize - bottomTitleWidthDp) / 2
                val bottomTitleYDp = logoYDp + logoSize + 6.dp
                val bottomTitleXPx = with(density) { bottomTitleXDp.toPx().roundToInt() }
                val bottomTitleYPx = with(density) { bottomTitleYDp.toPx().roundToInt() }

                // Side title ("projekt_b-d") next to compact logo when collapsed
                val sideTitleAlpha = ((collapseFraction - 0.50f) / 0.50f).coerceIn(0f, 1f)
                val sideTitleSlidePx = with(density) { ((1f - sideTitleAlpha) * (-10).dp.toPx()) }
                val sideTitleXDp = collapsedLogoX + 38.dp + 12.dp
                val sideTitleXPx = with(density) { sideTitleXDp.toPx().roundToInt() }

                // 1. Logo Box
                Box(
                    modifier = Modifier
                        .offset { IntOffset(logoXPx, logoYPx) }
                        .size(logoSize),
                    contentAlignment = Alignment.Center
                ) {
                    HeaderLogoView(
                        style = headerLogoStyle,
                        customBitmap = customLogoBitmap,
                        size = logoSize
                    )
                }

                // 2. Expanded Title below the logo (always kept in composition to avoid layout jumps)
                Text(
                    text = "projekt_b-d",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .width(bottomTitleWidthDp)
                        .offset { IntOffset(bottomTitleXPx, bottomTitleYPx) }
                        .graphicsLayer {
                            alpha = bottomTitleAlpha
                            scaleX = bottomTitleScale
                            scaleY = bottomTitleScale
                        }
                )

                // 3. Collapsed Title beside the logo in the top bar
                Box(
                    modifier = Modifier
                        .height(collapsedHeaderHeight)
                        .offset { IntOffset(sideTitleXPx, 0) }
                        .graphicsLayer {
                            alpha = sideTitleAlpha
                            translationX = sideTitleSlidePx
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "projekt_b-d",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                // 4. Top Right Downloads Button
                val downloadTopPad = lerp(10.dp, 6.dp, smoothFraction)
                IconButton(
                    onClick = onDownloadsClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = downloadTopPad, end = 12.dp)
                ) {
                    if (active > 0) {
                        BadgedBox(badge = { Badge { Text("$active") } }) {
                            Icon(Icons.Default.Download, contentDescription = "İndirmeler")
                        }
                    } else {
                        Icon(Icons.Default.Download, contentDescription = "İndirmeler")
                    }
                }

                if (collapseFraction > 0.85f) {
                    HorizontalDivider(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        thickness = 0.5.dp,
                        color = colors.border.copy(alpha = ((collapseFraction - 0.85f) / 0.15f).coerceIn(0f, 1f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderLogoView(
    style: HeaderLogoStyle,
    customBitmap: android.graphics.Bitmap?,
    size: Dp
) {
    when (style) {
        HeaderLogoStyle.CUSTOM -> {
            if (customBitmap != null) {
                Image(
                    bitmap = customBitmap.asImageBitmap(),
                    contentDescription = "Özel Logo",
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "Özel Logo Ekle",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.75f)
                )
            }
        }
        HeaderLogoStyle.ORIGINAL -> {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "projekt_b-d",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.75f)
            )
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
    val downloadsBrush = remember(primary) {
        Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF10B981), primary))
    }
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
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
        } else if (pin is PinnedItem.Playlist && pin.playlist.id == LOCAL_DOWNLOADS_ID) {
            Box(Modifier.fillMaxSize().background(downloadsBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DownloadDone, contentDescription = "İndirilen Müzikler", tint = Color.White, modifier = Modifier.size(42.dp))
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
                    } else if (pin is PinnedItem.Playlist && pin.playlist.id == LOCAL_DOWNLOADS_ID) {
                        if (downloadedTracks.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Tümünü çal") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = {
                                    viewModel.playbackManager.playTrackList(downloadedTracks, 0)
                                    menu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Hızlı erişimden kaldır") },
                            leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                            onClick = {
                                viewModel.setDownloadsQuickAccessPinned(false)
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
