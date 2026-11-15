package org.kde.plasmawave.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.compositeOver
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
import kotlin.random.Random
import org.kde.plasmawave.R
import org.kde.plasmawave.player.SleepTimerState
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.CustomThemeSpec
import org.kde.plasmawave.ui.DEEZER_FAVORITES_ID
import org.kde.plasmawave.ui.LOCAL_DOWNLOADS_ID
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.PinnedItem
import org.kde.plasmawave.ui.QuickCardContentMode
import org.kde.plasmawave.ui.QuickCardLayout
import org.kde.plasmawave.ui.QuickCardShape
import org.kde.plasmawave.ui.QuickCardStyle
import org.kde.plasmawave.ui.ResumePlaybackState
import org.kde.plasmawave.ui.SearchCategory
import org.kde.plasmawave.ui.TrackListeningStat
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.HeaderLogoStyle
import org.kde.plasmawave.ui.components.HomeFeedType
import org.kde.plasmawave.ui.components.HomeWidgetType
import org.kde.plasmawave.ui.components.ListeningStatsDialog
import org.kde.plasmawave.ui.components.TrackListItem
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

private fun formatClockMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}

private fun parseHexColorSafe(hex: String, fallback: Color): Color {
    return try {
        val clean = if (hex.startsWith("#")) hex else "#$hex"
        Color(AndroidColor.parseColor(clean))
    } catch (_: Exception) {
        fallback
    }
}

private const val CALM_TOP_GRADIENT_ALPHA = 0.22f
private const val CALM_TOP_GRADIENT_END_STOP = 0.35f
private const val CALM_SPECIAL_TILE_ALPHA = 0.14f
private const val CALM_RESUME_CARD_PRIMARY_MIX = 0.12f

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
    val homeFeedTypes by viewModel.homeFeedTypes.collectAsState()
    val resumePlaybackEnabled by viewModel.resumePlaybackEnabled.collectAsState()
    val resumeState by viewModel.resumePlaybackState.collectAsState()
    val enabledWidgets by viewModel.enabledHomeWidgets.collectAsState()
    val trackStats by viewModel.trackListeningStats.collectAsState()
    val totalListeningMs by viewModel.totalListeningTimeMs.collectAsState()
    val activeTheme by viewModel.activeTheme.collectAsState()
    val userCustomThemes by viewModel.userCustomThemes.collectAsState()
    val sleepTimerState by viewModel.playbackManager.sleepTimerState.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val mostPlayedTracks by viewModel.mostPlayedTracks.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val localPlaylists by viewModel.playlists.collectAsState()
    val downloads by viewModel.fullDownloads.collectAsState()
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val colors = LocalPlasmaColors.current
    val context = LocalContext.current

    var showStatsDialog by remember { mutableStateOf(false) }
    var selectedQuickArtist by remember { mutableStateOf<String?>(null) }

    if (showStatsDialog) {
        ListeningStatsDialog(
            viewModel = viewModel,
            onDismiss = { showStatsDialog = false }
        )
    }

    val allKnownTracks = remember(deezerFavorites, favorites, downloadedTracks, recentTracks) {
        (recentTracks + deezerFavorites + favorites + downloadedTracks).distinctBy { it.id }
    }

    if (selectedQuickArtist != null) {
        val artistName = selectedQuickArtist!!
        val artistTracks = remember(artistName, allKnownTracks) {
            allKnownTracks.filter { it.artist.contains(artistName, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { selectedQuickArtist = null },
            title = { Text(artistName, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Kitaplığınızda bu sanatçıya ait ${artistTracks.size} şarkı bulundu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    artistTracks.take(5).forEachIndexed { idx, t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.card)
                                .clickable {
                                    viewModel.playbackManager.playTrackList(artistTracks, idx)
                                    selectedQuickArtist = null
                                }
                                .padding(8.dp)
                        ) {
                            ArtworkImage(
                                t.artworkUrl,
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(6.dp),
                                fallbackTitle = t.title,
                                fallbackArtist = t.artist
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(t.album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val name = artistName
                            selectedQuickArtist = null
                            viewModel.navigateToSearch(name, SearchCategory.ARTISTS)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Deezer'da Ara")
                    }
                }
            },
            confirmButton = {
                if (artistTracks.isNotEmpty()) {
                    Button(
                        onClick = {
                            viewModel.playbackManager.playTrackList(artistTracks, 0)
                            selectedQuickArtist = null
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tümünü Çal")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedQuickArtist = null }) {
                    Text("Kapat")
                }
            }
        )
    }

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
    val bgBrush = remember(primary, bg, colors.legacyUiMode) {
        if (colors.legacyUiMode) {
            Brush.verticalGradient(listOf(primary.copy(alpha = 0.16f), bg, bg))
        } else {
            val topTint = primary.copy(alpha = CALM_TOP_GRADIENT_ALPHA).compositeOver(bg)
            Brush.verticalGradient(
                0f to topTint,
                CALM_TOP_GRADIENT_END_STOP to bg,
                1f to bg
            )
        }
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

            if (resumePlaybackEnabled && resumeState != null) {
                item(key = "resume_playback_card") {
                    ResumePlaybackCard(
                        state = resumeState!!,
                        onResume = { viewModel.resumeSavedPlayback() },
                        onHideCard = { viewModel.setResumePlaybackEnabled(false) },
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
                if (colors.legacyUiMode) {
                    Text(
                        "Sevdiğin şarkılar ve sabitlediklerin",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (quick.isNotEmpty()) item(key = "quick_access_grid") {
                val quickCardStyle by viewModel.quickCardStyle.collectAsState()
                val quickCardScale by viewModel.quickCardScale.collectAsState()
                val quickIconScale by viewModel.quickIconScale.collectAsState()
                val quickCardShape by viewModel.quickCardShape.collectAsState()
                val quickCardLayout by viewModel.quickCardLayout.collectAsState()
                val quickCardContentMode by viewModel.quickCardContentMode.collectAsState()
                val effectiveLayout = if (quickCardContentMode == QuickCardContentMode.IMAGE_ONLY) {
                    QuickCardLayout.SQUARE_GRID
                } else {
                    quickCardLayout
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val isLandscape = maxWidth > 600.dp
                    val availableWidth = (maxWidth - 32.dp).coerceAtLeast(160.dp)
                    if (effectiveLayout == QuickCardLayout.COMPACT_HORIZONTAL) {
                        val columns = when {
                            isLandscape -> if (quickCardScale <= 0.8f) 4 else 3
                            quickCardScale <= 0.78f -> 3
                            quickCardScale >= 1.18f -> 1
                            else -> 2
                        }
                        val cardHeight = (58.dp * quickCardScale).coerceIn(44.dp, 76.dp)
                        val rows = quick.chunked(columns)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { pin ->
                                        QuickCard(
                                            pin = pin,
                                            pinned = pin.id in pinnedIds,
                                            viewModel = viewModel,
                                            onPlaylistClick = onPlaylistClick,
                                            size = cardHeight,
                                            cardStyle = quickCardStyle,
                                            iconScale = quickIconScale,
                                            cardShape = quickCardShape,
                                            cardLayout = effectiveLayout,
                                            contentMode = quickCardContentMode,
                                            columnsCount = columns,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(columns - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        val baseCardSize = if (isLandscape) 124.dp else (maxWidth - 48.dp) / 3
                        val cardSize = baseCardSize * quickCardScale
                        val columns = ((availableWidth + 8.dp) / (cardSize + 8.dp)).toInt().coerceIn(2, 6)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(quick.chunked(columns * 3), key = { page -> page.first().id }) { page ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    page.chunked(columns).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { pin ->
                                                QuickCard(
                                                    pin = pin,
                                                    pinned = pin.id in pinnedIds,
                                                    viewModel = viewModel,
                                                    onPlaylistClick = onPlaylistClick,
                                                    size = cardSize,
                                                    cardStyle = quickCardStyle,
                                                    iconScale = quickIconScale,
                                                    cardShape = quickCardShape,
                                                    cardLayout = effectiveLayout,
                                                    contentMode = quickCardContentMode,
                                                    columnsCount = columns
                                                )
                                            }
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

            if (enabledWidgets.isNotEmpty()) {
                item(key = "home_widgets_spacer") {
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (HomeWidgetType.STATS in enabledWidgets) {
                item(key = "widget_stats") {
                    StatsHomeWidgetCard(
                        stats = trackStats,
                        totalListeningFormatted = viewModel.formatDurationTr(totalListeningMs),
                        onOpenFullStats = { showStatsDialog = true },
                        onPlayTrack = { idx ->
                            val tracks = trackStats.map { it.track }
                            if (tracks.isNotEmpty()) {
                                viewModel.playbackManager.playTrackList(tracks, idx)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (HomeWidgetType.QUICK_ARTIST in enabledWidgets && allKnownTracks.isNotEmpty()) {
                item(key = "widget_quick_artist") {
                    QuickArtistWidgetCard(
                        viewModel = viewModel,
                        allTracks = allKnownTracks,
                        stats = trackStats,
                        onArtistClick = { artistName -> selectedQuickArtist = artistName },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (HomeWidgetType.QUICK_THEME in enabledWidgets) {
                item(key = "widget_quick_theme") {
                    QuickThemeWidgetCard(
                        activeThemeId = activeTheme.id,
                        customThemes = userCustomThemes,
                        onSelectTheme = { viewModel.setTheme(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (HomeWidgetType.LUCKY_PICK in enabledWidgets && allKnownTracks.isNotEmpty()) {
                item(key = "widget_lucky_pick") {
                    LuckyPickWidgetCard(
                        tracks = allKnownTracks,
                        onPlayTrack = { track ->
                            viewModel.playbackManager.playTrackList(listOf(track) + allKnownTracks.shuffled().take(19), 0)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (HomeWidgetType.SLEEP_TIMER in enabledWidgets) {
                item(key = "widget_sleep_timer") {
                    SleepTimerWidgetCard(
                        sleepTimerState = sleepTimerState,
                        onSetMinutes = { viewModel.playbackManager.startSleepTimerMinutes(it) },
                        onSetEndOfTrack = { viewModel.playbackManager.startSleepTimerEndOfTrack() },
                        onCancel = { viewModel.playbackManager.cancelSleepTimer() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (HomeWidgetType.MOOD_MIX in enabledWidgets && allKnownTracks.isNotEmpty()) {
                item(key = "widget_mood_mix") {
                    MoodMixWidgetCard(
                        viewModel = viewModel,
                        tracks = allKnownTracks,
                        onStartMix = { moodTracks ->
                            if (moodTracks.isNotEmpty()) {
                                viewModel.playbackManager.playTrackList(moodTracks, 0)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Customizable Feeds (Senin İçin / Son Çalınanlar / En Çok Dinlenenler)
            HomeFeedType.entries.filter { it in homeFeedTypes }.forEach { feedType ->
                val feedTracks = when (feedType) {
                    HomeFeedType.FOR_YOU -> if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites
                    HomeFeedType.RECENTLY_PLAYED -> if (recentTracks.isNotEmpty()) recentTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
                    HomeFeedType.MOST_PLAYED -> if (mostPlayedTracks.isNotEmpty()) mostPlayedTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
                }

                if (feedTracks.isNotEmpty()) {
                    item(key = "feed_header_${feedType.name}") {
                        Spacer(Modifier.height(28.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    feedType.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (colors.legacyUiMode) {
                                    Text(
                                        feedType.subtitle,
                                        color = colors.textSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (likedPlaylist != null && feedType == HomeFeedType.FOR_YOU) {
                                TextButton(onClick = { onPlaylistClick(likedPlaylist) }) {
                                    Text(
                                        "Tümünü gör",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(feedTracks.take(6), key = { _, it -> "feed:${feedType.name}:${it.id}" }) { index, track ->
                        TrackListItem(
                            track,
                            isPlaying = currentTrackId == track.id,
                            onClick = { viewModel.playbackManager.playTrackList(feedTracks, index) },
                            onLongClick = { viewModel.openTrackMenu(track) }
                        )
                    }
                }
            }

            if (favorites.isNotEmpty() && HomeFeedType.FOR_YOU in homeFeedTypes && deezerFavorites.isNotEmpty()) {
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
    val headerTint = if (LocalPlasmaColors.current.legacyUiMode) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    }
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
                    tint = headerTint,
                    modifier = Modifier.size(size * 0.75f)
                )
            }
        }
        HeaderLogoStyle.ORIGINAL -> {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "projekt_b-d",
                tint = headerTint,
                modifier = Modifier.size(size * 0.75f)
            )
        }
    }
}

private val CardBottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))

@Composable
private fun QuickCard(
    pin: PinnedItem,
    pinned: Boolean,
    viewModel: MainViewModel,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    size: androidx.compose.ui.unit.Dp,
    cardStyle: QuickCardStyle = QuickCardStyle.THEME_ADAPTIVE,
    iconScale: Float = 1.0f,
    cardShape: QuickCardShape = QuickCardShape.ROUNDED,
    cardLayout: QuickCardLayout = QuickCardLayout.COMPACT_HORIZONTAL,
    contentMode: QuickCardContentMode = QuickCardContentMode.BOTH,
    columnsCount: Int = 2,
    modifier: Modifier = Modifier
) {
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
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val themeCardBrush = remember(primary, colors.card) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), colors.card))
    }
    val deezerFavBrush = remember(primary) {
        Brush.linearGradient(listOf(primary, Color(0xFF7B63DC), Color(0xFFE993BA)))
    }
    val flowGradient = remember {
        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316)))
    }
    val downloadsBrush = remember(primary) {
        Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF10B981), primary))
    }
    val isSpecialCard = pin is PinnedItem.Flow ||
        (pin is PinnedItem.Playlist && (pin.playlist.id == DEEZER_FAVORITES_ID || pin.playlist.id == LOCAL_DOWNLOADS_ID))
    val legacyUi = colors.legacyUiMode
    val useThemeAdaptive = legacyUi && isSpecialCard && cardStyle == QuickCardStyle.THEME_ADAPTIVE
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val composeShape = cardShape.toComposeShape()
    val isHorizontal = cardLayout == QuickCardLayout.COMPACT_HORIZONTAL
    val isCircle = cardShape == QuickCardShape.CIRCLE
    val centerIcon = when {
        pin is PinnedItem.Flow -> Icons.Default.AllInclusive
        pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> Icons.Default.Favorite
        else -> Icons.Default.DownloadDone
    }
    val gradient = when {
        pin is PinnedItem.Flow -> flowGradient
        pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> deezerFavBrush
        else -> downloadsBrush
    }

    Box(
        modifier
            .then(if (isHorizontal) Modifier.fillMaxWidth().height(size) else Modifier.size(size))
            .clip(composeShape)
            .then(
                if (legacyUi && (isHorizontal || useThemeAdaptive)) Modifier.border(1.dp, colors.border.copy(alpha = 0.6f), composeShape)
                else Modifier
            )
            .clickable {
                when (pin) {
                    is PinnedItem.Track -> viewModel.playbackManager.playTrackList(listOf(pin.track))
                    is PinnedItem.Playlist -> onPlaylistClick(pin.playlist)
                    is PinnedItem.Flow -> viewModel.startFlow()
                }
            }
    ) {
        if (isHorizontal) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.card),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (contentMode != QuickCardContentMode.TITLE_ONLY) {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(composeShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSpecialCard) {
                            val thumbIconSize = (24.dp * (size / 58.dp) * iconScale).coerceIn(14.dp, 38.dp)
                            if (!legacyUi) {
                                // Calm flat cover tile: tinted primary backdrop + primary icon
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(primary.copy(alpha = CALM_SPECIAL_TILE_ALPHA)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(centerIcon, contentDescription = title, tint = primary, modifier = Modifier.size(thumbIconSize))
                                }
                            } else if (useThemeAdaptive) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(centerIcon, contentDescription = title, tint = primary, modifier = Modifier.size(thumbIconSize))
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(gradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(centerIcon, contentDescription = title, tint = Color.White, modifier = Modifier.size(thumbIconSize))
                                }
                            }
                        } else {
                            ArtworkImage(
                                art,
                                modifier = Modifier.fillMaxSize(),
                                shape = composeShape,
                                fallback = if (pin is PinnedItem.Playlist) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.MusicNote
                            )
                        }
                    }
                }
                Text(
                    title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (contentMode == QuickCardContentMode.TITLE_ONLY) 14.dp else 10.dp,
                            end = 30.dp
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = if (columnsCount >= 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (columnsCount >= 3) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            val cardRatio = (size / 108.dp).coerceIn(0.45f, 1.3f)
            val isImageOnly = contentMode == QuickCardContentMode.IMAGE_ONLY
            val isTitleOnly = contentMode == QuickCardContentMode.TITLE_ONLY
            val iconVerticalOffset = if (isImageOnly) 0.dp else (-6).dp

            if (isTitleOnly) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (legacyUi) Modifier.background(themeCardBrush) else Modifier.background(colors.card)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = if (size < 84.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                if (isSpecialCard) {
                    if (!legacyUi) {
                        val baseIconDp = when {
                            pin is PinnedItem.Flow -> 30.dp
                            pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> 34.dp
                            else -> 32.dp
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(primary.copy(alpha = CALM_SPECIAL_TILE_ALPHA).compositeOver(colors.card)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                centerIcon,
                                contentDescription = title,
                                tint = primary,
                                modifier = Modifier
                                    .size((baseIconDp * cardRatio * iconScale).coerceIn(14.dp, 46.dp))
                                    .offset(y = iconVerticalOffset)
                            )
                        }
                    } else if (useThemeAdaptive) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(themeCardBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((48.dp * cardRatio * iconScale).coerceIn(22.dp, 68.dp))
                                    .offset(y = iconVerticalOffset)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    centerIcon,
                                    contentDescription = title,
                                    tint = primary,
                                    modifier = Modifier.size((26.dp * cardRatio * iconScale).coerceIn(12.dp, 38.dp))
                                )
                            }
                        }
                    } else {
                        val baseIconDp = when {
                            pin is PinnedItem.Flow -> 36.dp
                            pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> 42.dp
                            else -> 40.dp
                        }
                        Box(Modifier.fillMaxSize().background(gradient), contentAlignment = Alignment.Center) {
                            Icon(
                                centerIcon,
                                contentDescription = title,
                                tint = Color.White,
                                modifier = Modifier
                                    .size((baseIconDp * cardRatio * iconScale).coerceIn(16.dp, 56.dp))
                                    .offset(y = iconVerticalOffset)
                            )
                        }
                        if (!isImageOnly) {
                            Box(Modifier.fillMaxWidth().height(52.dp).align(Alignment.BottomCenter).background(CardBottomScrim))
                        }
                    }
                } else {
                    ArtworkImage(
                        art,
                        modifier = Modifier.fillMaxSize(),
                        shape = composeShape,
                        fallback = if (pin is PinnedItem.Playlist) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.MusicNote
                    )
                    if (!isImageOnly) {
                        Box(Modifier.fillMaxWidth().height(52.dp).align(Alignment.BottomCenter).background(CardBottomScrim))
                    }
                }
                if (!isImageOnly) {
                    val labelColor = if (!legacyUi || useThemeAdaptive) MaterialTheme.colorScheme.onSurface else Color.White
                    Text(
                        title,
                        modifier = if (isCircle) {
                            Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                        } else {
                            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                        },
                        color = labelColor,
                        style = if (size < 84.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = if (isCircle) TextAlign.Center else TextAlign.Start,
                        maxLines = if (isCircle || size < 84.dp) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(
            Modifier.align(
                when {
                    isHorizontal -> Alignment.CenterEnd
                    isCircle -> Alignment.TopCenter
                    else -> Alignment.TopEnd
                }
            ).then(
                when {
                    isHorizontal -> Modifier.padding(end = 2.dp)
                    isCircle -> Modifier.padding(top = 2.dp)
                    else -> Modifier
                }
            )
        ) {
            IconButton(
                onClick = { if (pin is PinnedItem.Track) viewModel.openTrackMenu(pin.track) else menu = true },
                modifier = Modifier.size(if (isHorizontal || size < 84.dp) 26.dp else 34.dp)
            ) {
                Icon(
                    if (legacyUi && pinned) Icons.Default.PushPin else Icons.Default.MoreVert,
                    contentDescription = "Hızlı erişim seçenekleri",
                    tint = if (!legacyUi || isHorizontal || useThemeAdaptive) colors.textSecondary else Color.White,
                    modifier = Modifier.size(if (isHorizontal || size < 84.dp) 14.dp else 18.dp)
                )
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
    val colors = LocalPlasmaColors.current
    val legacyUi = colors.legacyUiMode
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
        shape = RoundedCornerShape(16.dp),
        color = if (legacyUi) Color(0xFF1E1B2E) else colors.card,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (legacyUi) {
                        Modifier.background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6).copy(alpha = 0.28f),
                                    Color(0xFFEC4899).copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            )
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
                    if (legacyUi) {
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
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.cardPressed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AllInclusive,
                                contentDescription = "Flow",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Deezer Flow",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (legacyUi) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            if (legacyUi) {
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
                        }
                        if (legacyUi) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Zevkine göre sonsuz müzik akışı",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.70f)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    onClick = onStartFlow,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (legacyUi) Color.White else colors.cardPressed,
                        contentColor = if (legacyUi) Color(0xFF1E1B2E) else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = if (legacyUi) Color(0xFF8B5CF6) else MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Flow Başlat", modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumePlaybackCard(
    state: ResumePlaybackState,
    onResume: () -> Unit,
    onHideCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val safeDur = state.durationMs.coerceAtLeast(1L)
    val fraction = (state.positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)

    val calmResumeContainer = remember(colors.card, primary) {
        primary.copy(alpha = CALM_RESUME_CARD_PRIMARY_MIX).compositeOver(colors.card)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onResume() },
        colors = CardDefaults.cardColors(
            containerColor = if (legacyUi) colors.card else calmResumeContainer
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (legacyUi) BorderStroke(1.dp, primary.copy(alpha = 0.4f)) else null
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = null,
                    tint = if (legacyUi) primary else colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (legacyUi) "KALDIĞIN YERDEN DEVAM ET" else "Kaldığın yerden devam et",
                    style = if (legacyUi) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = primary,
                            letterSpacing = 0.5.sp
                        )
                    } else {
                        MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onHideCard,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Kartı Gizle",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ArtworkImage(
                    url = state.track.artworkUrl,
                    modifier = Modifier.size(54.dp),
                    fallback = Icons.Default.MusicNote,
                    shape = RoundedCornerShape(10.dp),
                    fallbackTitle = state.track.title,
                    fallbackArtist = state.track.artist
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        state.track.artist,
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(50)),
                            color = primary,
                            trackColor = colors.border
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${formatClockMs(state.positionMs)} / ${formatClockMs(safeDur)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onResume,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Devam Et", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun StatsHomeWidgetCard(
    stats: List<TrackListeningStat>,
    totalListeningFormatted: String,
    onOpenFullStats: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val totalPlays = remember(stats) { stats.sumOf { it.playCount } }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Şarkı ve Dinleme İstatistikleri",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (legacyUi) {
                        Text(
                            "Toplam: $totalListeningFormatted • $totalPlays dinlenme (${stats.size} şarkı)",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    } else if (totalPlays > 0) {
                        Text(
                            "$totalListeningFormatted • $totalPlays dinlenme",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
                TextButton(onClick = onOpenFullStats) {
                    Text(
                        "Tümünü gör",
                        color = primary
                    )
                }
            }

            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                stats.take(3).forEachIndexed { idx, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPlayTrack(idx) }
                            .padding(vertical = 5.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            "#${idx + 1}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (legacyUi) primary else colors.textSecondary
                            ),
                            modifier = Modifier.width(24.dp)
                        )
                        ArtworkImage(
                            url = s.track.artworkUrl,
                            modifier = Modifier.size(36.dp),
                            fallback = Icons.Default.MusicNote,
                            shape = RoundedCornerShape(6.dp),
                            fallbackTitle = s.track.title,
                            fallbackArtist = s.track.artist
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.track.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                s.track.artist,
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (legacyUi) primary.copy(alpha = 0.16f) else colors.cardPressed)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${s.playCount} kez",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (legacyUi) primary else colors.textSecondary,
                                    fontWeight = if (legacyUi) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickArtistWidgetCard(
    viewModel: MainViewModel,
    allTracks: List<FfiTrack>,
    stats: List<TrackListeningStat>,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode

    val artists = remember(allTracks, stats) {
        viewModel.getQuickArtists(limit = 12, fallbackTracks = allTracks).map { item ->
            val primaryArtist = item.artist.split(",").first().trim().ifEmpty { item.artist }
            Triple(primaryArtist, item.sampleTrack.artworkUrl, item.playCount.toInt())
        }.distinctBy { it.first }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PersonSearch, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.PersonSearch, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Hızlıca Sanatçıya Git",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (legacyUi) {
                        Text(
                            "Sanatçıya dokunarak şarkılarını anında çalın veya arayın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(artists, key = { it.first }) { (artistName, artUrl, _) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onArtistClick(artistName) }
                            .padding(4.dp)
                    ) {
                        ArtworkImage(
                            url = artUrl,
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .then(if (legacyUi) Modifier.border(1.5.dp, primary.copy(alpha = 0.6f), CircleShape) else Modifier),
                            fallback = Icons.Default.Person,
                            shape = CircleShape
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            artistName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickThemeWidgetCard(
    activeThemeId: String,
    customThemes: List<CustomThemeSpec>,
    onSelectTheme: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Hızlıca Tema Değiştir",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (legacyUi) {
                        Text(
                            "Tek dokunuşla temalarınız arasında geçiş yapın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "theme_breeze") {
                    val selected = activeThemeId == "org.kde.breeze-dark"
                    Button(
                        onClick = { onSelectTheme("org.kde.breeze-dark") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.cardPressed,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = if (selected && legacyUi) BorderStroke(2.dp, Color(0xFF3DAEE9)) else null
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4A9FD4)))
                        Spacer(Modifier.width(6.dp))
                        Text("Breeze", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                    }
                }
                item(key = "theme_twilight") {
                    val selected = activeThemeId == "mlp.twilight-sparkle"
                    val dotCol = if (legacyUi) Color(0xFFED438D) else org.kde.plasmawave.theme.desaturateForCalmUi(Color(0xFFED438D))
                    Button(
                        onClick = { onSelectTheme("mlp.twilight-sparkle") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (legacyUi) Color(0xFF282348) else colors.cardPressed,
                            contentColor = if (legacyUi) Color(0xFFF5EEF8) else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = if (selected && legacyUi) BorderStroke(2.dp, Color(0xFFED438D)) else null
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(dotCol))
                        Spacer(Modifier.width(6.dp))
                        Text("Twilight", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                    }
                }
                item(key = "theme_pinkie") {
                    val selected = activeThemeId == "mlp.pinkie-pie"
                    val dotCol = if (legacyUi) Color(0xFFFF2E93) else org.kde.plasmawave.theme.desaturateForCalmUi(Color(0xFFFF2E93))
                    Button(
                        onClick = { onSelectTheme("mlp.pinkie-pie") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (legacyUi) Color(0xFF3E1C32) else colors.cardPressed,
                            contentColor = if (legacyUi) Color(0xFFFFF0F5) else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = if (selected && legacyUi) BorderStroke(2.dp, Color(0xFFFF2E93)) else null
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(dotCol))
                        Spacer(Modifier.width(6.dp))
                        Text("Pinkie", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                    }
                }
                items(customThemes, key = { it.id }) { spec ->
                    val selected = activeThemeId == spec.id
                    val cardCol = if (legacyUi) parseHexColorSafe(spec.cardHex, Color(0xFF232629)) else colors.cardPressed
                    val rawAccent = parseHexColorSafe(spec.accentHex, primary)
                    val accentCol = if (legacyUi) rawAccent else org.kde.plasmawave.theme.desaturateForCalmUi(rawAccent)
                    val textCol = if (legacyUi) parseHexColorSafe(spec.textHex, Color.White) else MaterialTheme.colorScheme.onSurface
                    Button(
                        onClick = { onSelectTheme(spec.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cardCol,
                            contentColor = textCol
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = if (!legacyUi) null else if (selected) BorderStroke(2.dp, accentCol) else BorderStroke(1.dp, accentCol.copy(alpha = 0.4f))
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(accentCol))
                        Spacer(Modifier.width(6.dp))
                        Text(spec.name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}

@Composable
private fun LuckyPickWidgetCard(
    tracks: List<FfiTrack>,
    onPlayTrack: (FfiTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    var currentIndex by remember(tracks.size) {
        mutableIntStateOf(if (tracks.isNotEmpty()) Random.nextInt(tracks.size) else 0)
    }
    val chosenTrack = tracks.getOrNull(currentIndex) ?: tracks.firstOrNull() ?: return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.Casino, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Şanslı Şarkı Seçici",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (legacyUi) {
                        Text(
                            "Kitaplığından senin için rastgele seçilen parça",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
                TextButton(
                    onClick = {
                        if (tracks.size > 1) {
                            var next = Random.nextInt(tracks.size)
                            if (next == currentIndex) next = (next + 1) % tracks.size
                            currentIndex = next
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (legacyUi) primary else colors.textSecondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Başka seç",
                        color = if (legacyUi) primary else colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                    .clickable { onPlayTrack(chosenTrack) }
                    .padding(10.dp)
            ) {
                ArtworkImage(
                    url = chosenTrack.artworkUrl,
                    modifier = Modifier.size(46.dp),
                    fallback = Icons.Default.MusicNote,
                    shape = RoundedCornerShape(8.dp),
                    fallbackTitle = chosenTrack.title,
                    fallbackArtist = chosenTrack.artist
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        chosenTrack.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        chosenTrack.artist,
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledIconButton(
                    onClick = { onPlayTrack(chosenTrack) },
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (legacyUi) primary else colors.cardPressed,
                        contentColor = if (legacyUi) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Çal")
                }
            }
        }
    }
}

@Composable
private fun SleepTimerWidgetCard(
    sleepTimerState: SleepTimerState,
    onSetMinutes: (Int) -> Unit,
    onSetEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode

    val statusText = when (sleepTimerState) {
        is SleepTimerState.Off -> if (legacyUi) "Kapalı • Süre seçerek otomatik duraklatmayı başlatın" else null
        is SleepTimerState.EndOfTrack -> "Aktif • Çalan şarkı bittiğinde duracak"
        is SleepTimerState.Countdown -> "Aktif • Kalan süre: ${formatClockMs(sleepTimerState.remainingMs)}"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bedtime, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Hızlı Uyku Zamanlayıcısı",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (statusText != null) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (sleepTimerState is SleepTimerState.Off || !legacyUi) colors.textSecondary else primary
                            )
                        )
                    }
                }
                if (sleepTimerState !is SleepTimerState.Off) {
                    TextButton(onClick = onCancel) {
                        Text("Kapat", color = if (legacyUi) Color(0xFFEF4444) else colors.textSecondary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(15, 30, 45).forEach { mins ->
                    OutlinedButton(
                        onClick = { onSetMinutes(mins) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("$mins dk", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = onSetEndOfTrack,
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("Şarkı Sonu", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun MoodMixWidgetCard(
    viewModel: MainViewModel,
    tracks: List<FfiTrack>,
    onStartMix: (List<FfiTrack>) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode

    val moods = listOf(
        Triple(if (legacyUi) "Enerjik ⚡" else "Enerjik", Color(0xFFF59E0B), "enerjik"),
        Triple(if (legacyUi) "Gece Modu 🌙" else "Gece Modu", Color(0xFF8B5CF6), "gece"),
        Triple(if (legacyUi) "Odaklanma 🎯" else "Odaklanma", Color(0xFF10B981), "chill"),
        Triple(if (legacyUi) "Karışık Keyif 🎧" else "Karışık", Color(0xFFEC4899), "kesif")
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Ruh Haline Göre Anında Miks",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (legacyUi) {
                        Text(
                            "Modunu seç, şarkıların hemen çalmaya başlasın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                moods.forEach { (title, color, moodKey) ->
                    Button(
                        onClick = {
                            val mix = viewModel.generateMoodMix(mood = moodKey, limit = 25, candidateTracks = tracks)
                            onStartMix(mix)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (legacyUi) color.copy(alpha = 0.2f) else colors.cardPressed,
                            contentColor = if (legacyUi) color else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
