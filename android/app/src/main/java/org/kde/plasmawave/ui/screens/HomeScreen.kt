package org.kde.plasmawave.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.kde.plasmawave.R
import org.kde.plasmawave.player.SleepTimerState
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.PlasmaCustomColors
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
import org.kde.plasmawave.ui.components.AddHomeWidgetBottomSheet
import org.kde.plasmawave.ui.components.ArtworkImage
import org.kde.plasmawave.ui.components.CustomizeHomeWidgetBottomSheet
import org.kde.plasmawave.ui.components.HeaderLogoStyle
import org.kde.plasmawave.ui.components.HomeEditHoldIndicatorPill
import org.kde.plasmawave.ui.components.HomeEditToolbar
import org.kde.plasmawave.ui.components.HomeEditableItemShell
import org.kde.plasmawave.ui.components.HomeFeedType
import org.kde.plasmawave.ui.components.HomeWidgetType
import org.kde.plasmawave.ui.components.ListeningStatsDialog
import org.kde.plasmawave.ui.components.TrackListItem
import org.kde.plasmawave.ui.components.defaultTitleForHomeKind
import org.kde.plasmawave.ui.components.homeEditHoldTrigger
import org.kde.plasmawave.ui.components.resolveHomeWidgetVisuals
import uniffi.plasmawave_core.FfiHomeWidgetItem
import uniffi.plasmawave_core.FfiHomeWidgetSize
import uniffi.plasmawave_core.FfiHomeWidgetStyle
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

private object CustomHeaderLogoCache {
    @Volatile var cachedTimestamp: Long = Long.MIN_VALUE
    @Volatile var cachedBitmap: ImageBitmap? = null
}

private const val CALM_TOP_GRADIENT_ALPHA = 0.22f
private const val CALM_TOP_GRADIENT_END_STOP = 0.35f
private const val CALM_SPECIAL_TILE_ALPHA = 0.14f
private const val CALM_RESUME_CARD_PRIMARY_MIX = 0.12f

@OptIn(ExperimentalFoundationApi::class)
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
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val downloadedTrackCount by remember {
        viewModel.downloadedTracks.map { it.size }.distinctUntilChanged()
    }.collectAsState(initial = viewModel.downloadedTracks.value.size)
    val headerLogoStyle by viewModel.headerLogoStyle.collectAsState()
    val customLogoTimestamp by viewModel.customHeaderLogoTimestamp.collectAsState()
    val homeFeedTypes by viewModel.homeFeedTypes.collectAsState()
    val hasResumeState by remember {
        viewModel.resumePlaybackState.map { it != null }.distinctUntilChanged()
    }.collectAsState(initial = viewModel.resumePlaybackState.value != null)
    val activeTheme by viewModel.activeTheme.collectAsState()
    val userCustomThemes by viewModel.userCustomThemes.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val mostPlayedTracks by viewModel.mostPlayedTracks.collectAsState()
    val pins by viewModel.pins.collectAsState()
    val deezerPlaylists by viewModel.deezerPlaylists.collectAsState()
    val localPlaylists by viewModel.playlists.collectAsState()
    val activeDownloads by remember {
        viewModel.fullDownloads
            .map { list -> list.count { it.state == "queued" || it.state == "running" } }
            .distinctUntilChanged()
    }.collectAsState(initial = viewModel.fullDownloads.value.count { it.state == "queued" || it.state == "running" })
    val currentTrackId by viewModel.playbackManager.currentTrackId.collectAsState()
    val rustHomeLayout by viewModel.homeLayout.collectAsState()
    val homeWidgetCatalog by viewModel.homeWidgetCatalog.collectAsState()
    val canUndoHomeLayout by viewModel.canUndoHomeLayout.collectAsState()
    val homeEditHoldSeconds by viewModel.homeEditHoldSeconds.collectAsState()
    val holdDurationMs = (homeEditHoldSeconds.coerceIn(3, 10) * 1000L)
    val colors = LocalPlasmaColors.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var showStatsDialog by remember { mutableStateOf(false) }
    var selectedQuickArtist by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    val holdProgressState = remember { mutableStateOf<Float?>(null) }
    var showAddWidgetSheet by remember { mutableStateOf(false) }
    var customizingInstanceId by remember { mutableStateOf<String?>(null) }
    var showResetLayoutConfirm by remember { mutableStateOf(false) }

    var isDraggingWidget by remember { mutableStateOf(false) }
    var draggingInstanceId by remember { mutableStateOf<String?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var displayedLayout by remember { mutableStateOf(rustHomeLayout.filter { it.enabled }) }

    LaunchedEffect(rustHomeLayout, isDraggingWidget) {
        if (!isDraggingWidget) {
            displayedLayout = rustHomeLayout.filter { it.enabled }
        }
    }

    BackHandler(enabled = isEditMode) {
        isEditMode = false
        showAddWidgetSheet = false
        customizingInstanceId = null
    }

    fun enterEditMode() {
        if (!isEditMode) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            isEditMode = true
        }
    }

    if (showStatsDialog) {
        ListeningStatsDialog(
            viewModel = viewModel,
            onDismiss = { showStatsDialog = false }
        )
    }

    val allKnownTracks by viewModel.allKnownTracks.collectAsState()

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

    val customLogoImage by produceState<ImageBitmap?>(
        initialValue = if (headerLogoStyle == HeaderLogoStyle.CUSTOM &&
            CustomHeaderLogoCache.cachedTimestamp == customLogoTimestamp
        ) {
            CustomHeaderLogoCache.cachedBitmap
        } else null,
        customLogoTimestamp,
        headerLogoStyle
    ) {
        if (headerLogoStyle != HeaderLogoStyle.CUSTOM) {
            value = null
            return@produceState
        }
        if (CustomHeaderLogoCache.cachedTimestamp == customLogoTimestamp) {
            value = CustomHeaderLogoCache.cachedBitmap
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "custom_header_logo.png")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else null
        }
        CustomHeaderLogoCache.cachedTimestamp = customLogoTimestamp
        CustomHeaderLogoCache.cachedBitmap = decoded
        value = decoded
    }

    val isDeezerConnected = deezerUser != null
    val likedPlaylist = remember(isDeezerConnected, deezerFavorites.size) {
        if (isDeezerConnected || deezerFavorites.isNotEmpty()) FfiPlaylist(
            DEEZER_FAVORITES_ID, "Sevdiğim Şarkılar", null, false,
            deezerFavorites.size.toUInt(), null, "real"
        ) else null
    }

    val downloadedPlaylist = remember(downloadedTrackCount) {
        FfiPlaylist(
            id = LOCAL_DOWNLOADS_ID,
            name = "İndirilen Müzikler",
            description = "Cihaza indirilen çevrimdışı şarkılar",
            isLocal = true,
            trackCount = downloadedTrackCount.toUInt(),
            artworkUrl = null,
            origin = "real"
        )
    }

    val quick = remember(pins, likedPlaylist, downloadedPlaylist, downloadsQuickAccessPinned, deezerPlaylists, localPlaylists, flowQuickAccessPinned, isDeezerConnected) {
        val currentPlaylists = (deezerPlaylists.map { it.playlist } + localPlaylists).associateBy { it.id }
        val refreshed = pins.map { pin ->
            if (pin is PinnedItem.Playlist) PinnedItem.Playlist(currentPlaylists[pin.playlist.id] ?: pin.playlist) else pin
        }
        val flowItem = if (flowQuickAccessPinned && isDeezerConnected) listOf(PinnedItem.Flow) else emptyList()
        val downloadsItem = if (downloadsQuickAccessPinned) listOf(PinnedItem.Playlist(downloadedPlaylist)) else emptyList()
        val likedItem = listOfNotNull(likedPlaylist?.let { PinnedItem.Playlist(it) })
        (flowItem + downloadsItem + likedItem + refreshed).distinctBy { it.id }
    }
    val pinnedIds = remember(pins, flowQuickAccessPinned, downloadsQuickAccessPinned) {
        val set = pins.mapTo(HashSet(pins.size.coerceAtLeast(1) + 2)) { it.id }
        if (flowQuickAccessPinned) set.add(PinnedItem.Flow.id)
        if (downloadsQuickAccessPinned) set.add("playlist:$LOCAL_DOWNLOADS_ID")
        set
    }

    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val ambientBackdrop = org.kde.plasmawave.ui.components.LocalAmbientBackdropEnabled.current
    val bgBrush = remember(primary, bg, colors.legacyUiMode, ambientBackdrop) {
        if (ambientBackdrop) {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
        } else if (colors.legacyUiMode) {
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

    val collapseFractionState = remember(listState, maxCollapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / maxCollapseDistancePx).coerceIn(0f, 1f)
        }
    }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val quickCardStyle by viewModel.quickCardStyle.collectAsState()
    val quickCardScale by viewModel.quickCardScale.collectAsState()
    val quickIconScale by viewModel.quickIconScale.collectAsState()
    val quickCardShape by viewModel.quickCardShape.collectAsState()
    val quickCardLayout by viewModel.quickCardLayout.collectAsState()
    val quickCardContentMode by viewModel.quickCardContentMode.collectAsState()

    val activeCustomizerItem = customizingInstanceId?.let { targetId ->
        rustHomeLayout.find { it.instanceId == targetId }
    }
    if (showAddWidgetSheet) {
        AddHomeWidgetBottomSheet(
            catalog = homeWidgetCatalog,
            activeLayout = rustHomeLayout,
            onAddKind = { kind ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.addHomeWidget(kind)
            },
            onDismiss = { showAddWidgetSheet = false }
        )
    }
    if (activeCustomizerItem != null) {
        val entry = homeWidgetCatalog.find { it.kind == activeCustomizerItem.kind }
        CustomizeHomeWidgetBottomSheet(
            item = activeCustomizerItem,
            catalogEntry = entry,
            onApplyPatch = { patch -> viewModel.customizeHomeWidget(activeCustomizerItem.instanceId, patch) },
            onDismiss = { customizingInstanceId = null }
        )
    }
    if (showResetLayoutConfirm) {
        AlertDialog(
            onDismissRequest = { showResetLayoutConfirm = false },
            title = { Text("Ana Sayfayı Sıfırla", fontWeight = FontWeight.Bold) },
            text = { Text("Tüm widget sırası, eklenen kartlar ve özel stiller varsayılan düzene döndürülecek.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.resetHomeLayout()
                    showResetLayoutConfirm = false
                }) { Text("Sıfırla") }
            },
            dismissButton = {
                TextButton(onClick = { showResetLayoutConfirm = false }) { Text("İptal") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBarTop + collapsedHeaderHeight, bottom = 96.dp)
        ) {
            // Item 0: Dedicated collapse range spacer so firstVisibleItemIndex stays 0 throughout 0..collapseRangeDp
            item(key = "header_collapse_spacer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(collapseRangeDp)
                        .homeEditHoldTrigger(
                            enabled = !isEditMode,
                            holdDurationMs = holdDurationMs,
                            onHoldProgress = { holdProgressState.value = it },
                            onTriggered = { enterEditMode() }
                        )
                )
            }

            if (isEditMode) {
                itemsIndexed(
                    items = displayedLayout,
                    key = { _, item -> item.instanceId },
                    contentType = { _, item -> item.kind }
                ) { idx, layoutItem ->
                    HomeLayoutShellItem(
                        idx = idx,
                        layoutItem = layoutItem,
                        getDisplayedLayout = { displayedLayout },
                        onUpdateDisplayedLayout = { displayedLayout = it },
                        isEditMode = true,
                        holdDurationMs = holdDurationMs,
                        listState = listState,
                        isDraggingWidget = isDraggingWidget,
                        getDraggingInstanceId = { draggingInstanceId },
                        getDragDeltaY = { dragDeltaY },
                        onSetDragging = { dragging, id, delta ->
                            isDraggingWidget = dragging
                            draggingInstanceId = id
                            dragDeltaY = delta
                        },
                        onSetDragDeltaY = { dragDeltaY = it },
                        onHoldProgress = { holdProgressState.value = it },
                        onEnterEditMode = { enterEditMode() },
                        onCustomize = { customizingInstanceId = layoutItem.instanceId },
                        viewModel = viewModel,
                        deezerLoggedIn = deezerUser != null,
                        flowLoading = flowLoading,
                        quick = quick,
                        pinnedIds = pinnedIds,
                        quickCardStyle = quickCardStyle,
                        quickCardScale = quickCardScale,
                        quickIconScale = quickIconScale,
                        quickCardShape = quickCardShape,
                        quickCardLayout = quickCardLayout,
                        quickCardContentMode = quickCardContentMode,
                        allKnownTracks = allKnownTracks,
                        recentTracks = recentTracks,
                        mostPlayedTracks = mostPlayedTracks,
                        deezerFavorites = deezerFavorites,
                        favorites = favorites,
                        homeFeedTypes = homeFeedTypes,
                        likedPlaylist = likedPlaylist,
                        currentTrackId = currentTrackId,
                        activeThemeId = activeTheme.id,
                        userCustomThemes = userCustomThemes,
                        onPlaylistClick = onPlaylistClick,
                        onOpenFullStats = { showStatsDialog = true },
                        onArtistClick = { selectedQuickArtist = it }
                    )
                }
            } else {
                displayedLayout.forEachIndexed { idx, layoutItem ->
                    val isVisibleOutsideEdit = when (layoutItem.kind) {
                        "block_flow_hero" -> deezerUser != null
                        "block_resume_playback" -> hasResumeState
                        "widget_quick_artist", "widget_lucky_pick", "widget_mood_mix" -> allKnownTracks.isNotEmpty()
                        "widget_recent_tracks" -> recentTracks.isNotEmpty()
                        else -> true
                    }
                    if (!isVisibleOutsideEdit) return@forEachIndexed

                    if (layoutItem.kind == "block_feed") {
                        val feedSections = buildHomeFeedSections(
                            item = layoutItem,
                            isEditMode = false,
                            showSubtitleLegacyOnly = colors.legacyUiMode,
                            homeFeedTypes = homeFeedTypes,
                            deezerFavorites = deezerFavorites,
                            favorites = favorites,
                            recentTracks = recentTracks,
                            mostPlayedTracks = mostPlayedTracks,
                            likedPlaylist = likedPlaylist
                        )
                        homeFeedLazyItems(
                            instanceId = layoutItem.instanceId,
                            sections = feedSections,
                            currentTrackId = currentTrackId,
                            primary = primary,
                            colors = colors,
                            onPlaylistClick = onPlaylistClick,
                            onPlayTracks = viewModel.playbackManager::playTrackList,
                            onTrackMenu = viewModel::openTrackMenu
                        )
                    } else {
                        item(key = layoutItem.instanceId, contentType = layoutItem.kind) {
                            HomeLayoutShellItem(
                                idx = idx,
                                layoutItem = layoutItem,
                                getDisplayedLayout = { displayedLayout },
                                onUpdateDisplayedLayout = { displayedLayout = it },
                                isEditMode = false,
                                holdDurationMs = holdDurationMs,
                                listState = listState,
                                isDraggingWidget = false,
                                getDraggingInstanceId = { null },
                                getDragDeltaY = { 0f },
                                onSetDragging = { _, _, _ -> },
                                onSetDragDeltaY = {},
                                onHoldProgress = { holdProgressState.value = it },
                                onEnterEditMode = { enterEditMode() },
                                onCustomize = {},
                                viewModel = viewModel,
                                deezerLoggedIn = deezerUser != null,
                                flowLoading = flowLoading,
                                quick = quick,
                                pinnedIds = pinnedIds,
                                quickCardStyle = quickCardStyle,
                                quickCardScale = quickCardScale,
                                quickIconScale = quickIconScale,
                                quickCardShape = quickCardShape,
                                quickCardLayout = quickCardLayout,
                                quickCardContentMode = quickCardContentMode,
                                allKnownTracks = allKnownTracks,
                                recentTracks = recentTracks,
                                mostPlayedTracks = mostPlayedTracks,
                                deezerFavorites = deezerFavorites,
                                favorites = favorites,
                                homeFeedTypes = homeFeedTypes,
                                likedPlaylist = likedPlaylist,
                                currentTrackId = currentTrackId,
                                activeThemeId = activeTheme.id,
                                userCustomThemes = userCustomThemes,
                                onPlaylistClick = onPlaylistClick,
                                onOpenFullStats = { showStatsDialog = true },
                                onArtistClick = { selectedQuickArtist = it }
                            )
                        }
                    }
                }
            }

            if (!isEditMode && displayedLayout.isEmpty()) {
                item(key = "home_empty_recovery") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                            .combinedClickable(
                                onClick = { enterEditMode() },
                                onLongClick = { enterEditMode() }
                            )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(20.dp).fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Widgets, contentDescription = null, tint = primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Ana Sayfan Boş", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                "Widget eklemek ve düzenlemek için dokun veya basılı tut",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            if (isEditMode) {
                item(key = "edit_mode_footer_add") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showAddWidgetSheet = true },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Yeni Widget veya Bölüm Ekle", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Pinned Collapsing Header driven purely on the graphicsLayer/draw phase (0 recompositions during scroll)
        if (!isEditMode) {
            CollapsingHomeHeader(
                fraction = { collapseFractionState.value },
                bg = bg,
                borderColor = colors.border,
                statusBarTop = statusBarTop,
                expandedHeaderHeight = expandedHeaderHeight,
                collapsedHeaderHeight = collapsedHeaderHeight,
                headerLogoStyle = headerLogoStyle,
                customLogoImage = customLogoImage,
                activeDownloads = activeDownloads,
                onDownloadsClick = onDownloadsClick,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        AnimatedVisibility(
            visible = isEditMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 4.dp)
                .zIndex(20f)
        ) {
            HomeEditToolbar(
                canUndo = canUndoHomeLayout,
                onAddWidgetClick = { showAddWidgetSheet = true },
                onUndoClick = { viewModel.undoHomeLayoutChange() },
                onResetClick = { showResetLayoutConfirm = true },
                onDoneClick = { isEditMode = false }
            )
        }

        HomeEditHoldIndicatorHost(
            isEditMode = isEditMode,
            holdProgressState = holdProgressState,
            holdSeconds = homeEditHoldSeconds,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 6.dp)
                .zIndex(25f)
        )
    }
}

@Composable
private fun HomeEditHoldIndicatorHost(
    isEditMode: Boolean,
    holdProgressState: State<Float?>,
    holdSeconds: Int,
    modifier: Modifier = Modifier
) {
    // Read holdProgressState.value inside this small leaf scope so ~30 FPS progress ticks
    // never recompose the entire HomeScreen Box and LazyColumn.
    val activeHoldProgress = holdProgressState.value
    if (!isEditMode && activeHoldProgress != null) {
        HomeEditHoldIndicatorPill(
            progress = activeHoldProgress,
            holdSeconds = holdSeconds,
            modifier = modifier
        )
    }
}

@Composable
private fun LazyItemScope.HomeLayoutShellItem(
    idx: Int,
    layoutItem: FfiHomeWidgetItem,
    getDisplayedLayout: () -> List<FfiHomeWidgetItem>,
    onUpdateDisplayedLayout: (List<FfiHomeWidgetItem>) -> Unit,
    isEditMode: Boolean,
    holdDurationMs: Long,
    listState: LazyListState,
    isDraggingWidget: Boolean,
    getDraggingInstanceId: () -> String?,
    getDragDeltaY: () -> Float,
    onSetDragging: (Boolean, String?, Float) -> Unit,
    onSetDragDeltaY: (Float) -> Unit,
    onHoldProgress: (Float?) -> Unit,
    onEnterEditMode: () -> Unit,
    onCustomize: () -> Unit,
    viewModel: MainViewModel,
    deezerLoggedIn: Boolean,
    flowLoading: Boolean,
    quick: List<PinnedItem>,
    pinnedIds: Set<String>,
    quickCardStyle: QuickCardStyle,
    quickCardScale: Float,
    quickIconScale: Float,
    quickCardShape: QuickCardShape,
    quickCardLayout: QuickCardLayout,
    quickCardContentMode: QuickCardContentMode,
    allKnownTracks: List<FfiTrack>,
    recentTracks: List<FfiTrack>,
    mostPlayedTracks: List<FfiTrack>,
    deezerFavorites: List<FfiTrack>,
    favorites: List<FfiTrack>,
    homeFeedTypes: Set<HomeFeedType>,
    likedPlaylist: FfiPlaylist?,
    currentTrackId: String?,
    activeThemeId: String,
    userCustomThemes: List<CustomThemeSpec>,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    onOpenFullStats: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val currentDisplayedLayout = getDisplayedLayout()
    val currentDraggingId = getDraggingInstanceId()
    val isDraggingThis = currentDraggingId == layoutItem.instanceId
    HomeEditableItemShell(
        item = layoutItem,
        title = defaultTitleForHomeKind(layoutItem.kind),
        indexInLayout = idx,
        totalCount = currentDisplayedLayout.size,
        isEditMode = isEditMode,
        holdDurationMs = holdDurationMs,
        allowHoldOnShell = layoutItem.kind != "block_feed",
        listState = listState,
        isDraggingThis = isDraggingThis,
        dragOffsetY = if (isDraggingThis) getDragDeltaY() else 0f,
        onHoldProgress = onHoldProgress,
        onEnterEditMode = onEnterEditMode,
        onDragStart = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSetDragging(true, layoutItem.instanceId, 0f)
        },
        onDragDeltaY = { dy ->
            val activeId = getDraggingInstanceId() ?: return@HomeEditableItemShell
            val liveLayout = getDisplayedLayout()
            var nextDeltaY = getDragDeltaY() + dy
            val visible = listState.layoutInfo.visibleItemsInfo
            val curInfo = visible.find { it.key == activeId } ?: run {
                onSetDragDeltaY(nextDeltaY)
                return@HomeEditableItemShell
            }
            val curIdx = liveLayout.indexOfFirst { it.instanceId == activeId }
            if (curIdx < 0) {
                onSetDragDeltaY(nextDeltaY)
                return@HomeEditableItemShell
            }
            val curCenter = curInfo.offset + curInfo.size / 2f + nextDeltaY

            if (nextDeltaY > 0f && curIdx < liveLayout.lastIndex) {
                val nextId = liveLayout[curIdx + 1].instanceId
                val nextInfo = visible.find { it.key == nextId }
                if (nextInfo != null && curCenter > nextInfo.offset + nextInfo.size * 0.5f) {
                    onUpdateDisplayedLayout(
                        liveLayout.toMutableList().apply {
                            val moved = removeAt(curIdx)
                            add(curIdx + 1, moved)
                        }
                    )
                    nextDeltaY -= nextInfo.size.toFloat()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            } else if (nextDeltaY < 0f && curIdx > 0) {
                val prevId = liveLayout[curIdx - 1].instanceId
                val prevInfo = visible.find { it.key == prevId }
                if (prevInfo != null && curCenter < prevInfo.offset + prevInfo.size * 0.5f) {
                    onUpdateDisplayedLayout(
                        liveLayout.toMutableList().apply {
                            val moved = removeAt(curIdx)
                            add(curIdx - 1, moved)
                        }
                    )
                    nextDeltaY += prevInfo.size.toFloat()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            onSetDragDeltaY(nextDeltaY)
        },
        onDragEnd = {
            val finalOrder = getDisplayedLayout().map { it.instanceId }
            onSetDragging(false, null, 0f)
            viewModel.reorderHomeWidgets(finalOrder)
        },
        onMoveUp = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.moveHomeWidgetStep(layoutItem.instanceId, -1)
        },
        onMoveDown = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.moveHomeWidgetStep(layoutItem.instanceId, 1)
        },
        onCustomize = onCustomize,
        onRemove = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.removeHomeWidget(layoutItem.instanceId)
        },
        modifier = if (isDraggingWidget) Modifier else Modifier.animateItem()
    ) {
        RenderHomeLayoutItem(
            item = layoutItem,
            isEditMode = isEditMode,
            viewModel = viewModel,
            deezerLoggedIn = deezerLoggedIn,
            flowLoading = flowLoading,
            quick = quick,
            pinnedIds = pinnedIds,
            quickCardStyle = quickCardStyle,
            quickCardScale = quickCardScale,
            quickIconScale = quickIconScale,
            quickCardShape = quickCardShape,
            quickCardLayout = quickCardLayout,
            quickCardContentMode = quickCardContentMode,
            allKnownTracks = allKnownTracks,
            recentTracks = recentTracks,
            mostPlayedTracks = mostPlayedTracks,
            deezerFavorites = deezerFavorites,
            favorites = favorites,
            homeFeedTypes = homeFeedTypes,
            likedPlaylist = likedPlaylist,
            currentTrackId = currentTrackId,
            activeThemeId = activeThemeId,
            userCustomThemes = userCustomThemes,
            onPlaylistClick = onPlaylistClick,
            onOpenFullStats = onOpenFullStats,
            onArtistClick = onArtistClick,
            onRemoveBlock = { viewModel.removeHomeWidget(layoutItem.instanceId) }
        )
    }
}

private data class HomeFeedSectionData(
    val keySuffix: String,
    val title: String,
    val subtitle: String?,
    val showAllPlaylist: FfiPlaylist?,
    val allTracks: List<FfiTrack>,
    val visibleTracks: List<FfiTrack>,
    val topSpacing: Dp,
    val isSecondaryHeader: Boolean = false
)

private fun buildHomeFeedSections(
    item: FfiHomeWidgetItem,
    isEditMode: Boolean,
    showSubtitleLegacyOnly: Boolean,
    homeFeedTypes: Set<HomeFeedType>,
    deezerFavorites: List<FfiTrack>,
    favorites: List<FfiTrack>,
    recentTracks: List<FfiTrack>,
    mostPlayedTracks: List<FfiTrack>,
    likedPlaylist: FfiPlaylist?
): List<HomeFeedSectionData> {
    val feedLimit = item.maxItems.toInt().coerceIn(3, 20)
    val sections = mutableListOf<HomeFeedSectionData>()

    HomeFeedType.entries.filter { it in homeFeedTypes }.forEach { feedType ->
        val feedTracks = when (feedType) {
            HomeFeedType.FOR_YOU -> if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites
            HomeFeedType.RECENTLY_PLAYED -> if (recentTracks.isNotEmpty()) recentTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
            HomeFeedType.MOST_PLAYED -> if (mostPlayedTracks.isNotEmpty()) mostPlayedTracks else (if (deezerFavorites.isNotEmpty()) deezerFavorites else favorites)
        }
        if (feedTracks.isNotEmpty()) {
            val previewSlice = if (item.size == FfiHomeWidgetSize.COMPACT) {
                feedTracks.take(3.coerceAtMost(feedLimit))
            } else {
                feedTracks.take(feedLimit)
            }
            sections.add(
                HomeFeedSectionData(
                    keySuffix = feedType.name,
                    title = if (feedType == HomeFeedType.FOR_YOU && !item.customTitle.isNullOrBlank()) item.customTitle!! else feedType.title,
                    subtitle = if (item.showSubtitle && showSubtitleLegacyOnly) feedType.subtitle else null,
                    showAllPlaylist = if (likedPlaylist != null && feedType == HomeFeedType.FOR_YOU) likedPlaylist else null,
                    allTracks = feedTracks,
                    visibleTracks = previewSlice,
                    topSpacing = if (isEditMode) 8.dp else 18.dp
                )
            )
        }
    }

    if (favorites.isNotEmpty() && HomeFeedType.FOR_YOU in homeFeedTypes && deezerFavorites.isNotEmpty()) {
        val localSlice = if (item.size == FfiHomeWidgetSize.COMPACT) {
            favorites.take(3)
        } else {
            favorites.take(5.coerceAtMost(feedLimit))
        }
        sections.add(
            HomeFeedSectionData(
                keySuffix = "local_favorites",
                title = "Bu cihazda beğendiklerin",
                subtitle = null,
                showAllPlaylist = null,
                allTracks = favorites,
                visibleTracks = localSlice,
                topSpacing = 16.dp,
                isSecondaryHeader = true
            )
        )
    }
    return sections
}

@Composable
private fun HomeFeedSectionHeader(
    section: HomeFeedSectionData,
    horizontalPadding: Dp,
    primary: Color,
    colors: PlasmaCustomColors,
    onPlaylistClick: (FfiPlaylist) -> Unit
) {
    Spacer(Modifier.height(section.topSpacing))
    if (section.isSecondaryHeader) {
        Text(
            section.title,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (section.subtitle != null) {
                    Text(
                        section.subtitle,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (section.showAllPlaylist != null) {
                TextButton(onClick = { onPlaylistClick(section.showAllPlaylist) }) {
                    Text("Tümünü gör", color = primary)
                }
            }
        }
    }
}

private fun LazyListScope.homeFeedLazyItems(
    instanceId: String,
    sections: List<HomeFeedSectionData>,
    currentTrackId: String?,
    primary: Color,
    colors: PlasmaCustomColors,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    onPlayTracks: (List<FfiTrack>, Int) -> Unit,
    onTrackMenu: (FfiTrack) -> Unit
) {
    sections.forEach { section ->
        item(
            key = "feed_header_${instanceId}_${section.keySuffix}",
            contentType = if (section.isSecondaryHeader) "feed_secondary_header" else "feed_header"
        ) {
            HomeFeedSectionHeader(
                section = section,
                horizontalPadding = 16.dp,
                primary = primary,
                colors = colors,
                onPlaylistClick = onPlaylistClick
            )
        }
        itemsIndexed(
            items = section.visibleTracks,
            key = { index, track -> "feed_track_${instanceId}_${section.keySuffix}_${track.id}_$index" },
            contentType = { _, _ -> "feed_track_item" }
        ) { index, track ->
            TrackListItem(
                track = track,
                isPlaying = currentTrackId == track.id,
                onClick = { onPlayTracks(section.allTracks, index) },
                onLongClick = { onTrackMenu(track) }
            )
        }
    }
}

@Composable
private fun RenderHomeLayoutItem(
    item: FfiHomeWidgetItem,
    isEditMode: Boolean,
    viewModel: MainViewModel,
    deezerLoggedIn: Boolean,
    flowLoading: Boolean,
    quick: List<PinnedItem>,
    pinnedIds: Set<String>,
    quickCardStyle: QuickCardStyle,
    quickCardScale: Float,
    quickIconScale: Float,
    quickCardShape: QuickCardShape,
    quickCardLayout: QuickCardLayout,
    quickCardContentMode: QuickCardContentMode,
    allKnownTracks: List<FfiTrack>,
    recentTracks: List<FfiTrack>,
    mostPlayedTracks: List<FfiTrack>,
    deezerFavorites: List<FfiTrack>,
    favorites: List<FfiTrack>,
    homeFeedTypes: Set<HomeFeedType>,
    likedPlaylist: FfiPlaylist?,
    currentTrackId: String?,
    activeThemeId: String,
    userCustomThemes: List<CustomThemeSpec>,
    onPlaylistClick: (FfiPlaylist) -> Unit,
    onOpenFullStats: () -> Unit,
    onArtistClick: (String) -> Unit,
    onRemoveBlock: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val outerPad = if (isEditMode) PaddingValues(horizontal = 8.dp, vertical = 4.dp) else PaddingValues(horizontal = 16.dp, vertical = 6.dp)

    when (item.kind) {
        "block_flow_hero" -> {
            if (deezerLoggedIn) {
                DeezerFlowHeroCard(
                    isLoading = flowLoading,
                    onStartFlow = { viewModel.startFlow() },
                    widgetItem = item,
                    modifier = Modifier.padding(outerPad)
                )
            } else if (isEditMode) {
                EditModeEmptyPlaceholder(
                    title = item.customTitle ?: "Deezer Flow",
                    message = "Deezer hesabı bağlandığında burada görünecek",
                    modifier = Modifier.padding(outerPad)
                )
            }
        }
        "block_resume_playback" -> {
            ResumePlaybackWidgetHost(
                viewModel = viewModel,
                isEditMode = isEditMode,
                widgetItem = item,
                onHideCard = onRemoveBlock,
                modifier = Modifier.padding(outerPad)
            )
        }
        "block_quick_access" -> {
            val limitedQuick = remember(quick, item.maxItems) {
                quick.take(item.maxItems.toInt().coerceAtLeast(1))
            }
            val blockTitle = item.customTitle?.takeIf { it.isNotBlank() } ?: "Hızlı erişim"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isEditMode) 4.dp else 6.dp)
            ) {
                Text(
                    blockTitle,
                    modifier = Modifier.padding(horizontal = if (isEditMode) 12.dp else 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (item.showSubtitle && colors.legacyUiMode) {
                    Text(
                        "Sevdiğin şarkılar ve sabitlediklerin",
                        modifier = Modifier.padding(horizontal = if (isEditMode) 12.dp else 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (limitedQuick.isNotEmpty()) {
                    val effectiveLayout = if (quickCardContentMode == QuickCardContentMode.IMAGE_ONLY) {
                        QuickCardLayout.SQUARE_GRID
                    } else {
                        quickCardLayout
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val isLandscape = maxWidth > 600.dp
                        val hPad = if (isEditMode) 12.dp else 16.dp
                        val availableWidth = (maxWidth - hPad * 2).coerceAtLeast(160.dp)
                        if (effectiveLayout == QuickCardLayout.COMPACT_HORIZONTAL) {
                            val columns = when {
                                isLandscape -> if (quickCardScale <= 0.8f) 4 else 3
                                quickCardScale <= 0.78f -> 3
                                quickCardScale >= 1.18f -> 1
                                else -> 2
                            }
                            val cardHeight = (58.dp * quickCardScale).coerceIn(44.dp, 76.dp)
                            val rows = remember(limitedQuick, columns) { limitedQuick.chunked(columns) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = hPad),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rows.forEach { row ->
                                    key(row.first().id) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            row.forEach { pin ->
                                                key(pin.id) {
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
                                            }
                                            repeat(columns - row.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val baseCardSize = if (isLandscape) 124.dp else (maxWidth - 48.dp) / 3
                            val cardSize = baseCardSize * quickCardScale
                            val columns = ((availableWidth + 8.dp) / (cardSize + 8.dp)).toInt().coerceIn(2, 6)
                            val pages = remember(limitedQuick, columns) { limitedQuick.chunked(columns * 3) }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = hPad),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(pages, key = { page -> page.first().id }) { page ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        page.chunked(columns).forEach { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                row.forEach { pin ->
                                                    key(pin.id) {
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
                    }
                } else {
                    Text(
                        "Şarkı veya listelerin menüsünden ana sayfaya sabitleyebilirsin.",
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = if (isEditMode) 12.dp else 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
        "widget_stats" -> {
            StatsHomeWidgetCard(
                viewModel = viewModel,
                onOpenFullStats = onOpenFullStats,
                widgetItem = item,
                modifier = Modifier.padding(outerPad)
            )
        }
        "widget_quick_artist" -> {
            if (allKnownTracks.isNotEmpty()) {
                QuickArtistWidgetCard(
                    viewModel = viewModel,
                    allTracks = allKnownTracks,
                    onArtistClick = onArtistClick,
                    widgetItem = item,
                    modifier = Modifier.padding(outerPad)
                )
            } else if (isEditMode) {
                EditModeEmptyPlaceholder(
                    title = item.customTitle ?: "Hızlıca Sanatçıya Git",
                    message = "Kitaplığında şarkı olduğunda sanatçılar burada sıralanır",
                    modifier = Modifier.padding(outerPad)
                )
            }
        }
        "widget_quick_theme" -> {
            QuickThemeWidgetCard(
                activeThemeId = activeThemeId,
                customThemes = userCustomThemes,
                onSelectTheme = { viewModel.setTheme(it) },
                widgetItem = item,
                modifier = Modifier.padding(outerPad)
            )
        }
        "widget_lucky_pick" -> {
            if (allKnownTracks.isNotEmpty()) {
                LuckyPickWidgetCard(
                    tracks = allKnownTracks,
                    onPlayTrack = { track ->
                        viewModel.playbackManager.playTrackList(listOf(track) + allKnownTracks.shuffled().take(19), 0)
                    },
                    widgetItem = item,
                    modifier = Modifier.padding(outerPad)
                )
            } else if (isEditMode) {
                EditModeEmptyPlaceholder(
                    title = item.customTitle ?: "Şanslı Şarkı Seçici",
                    message = "Kitaplığından rastgele şarkı önerisi burada görünür",
                    modifier = Modifier.padding(outerPad)
                )
            }
        }
        "widget_sleep_timer" -> {
            SleepTimerWidgetCard(
                viewModel = viewModel,
                widgetItem = item,
                modifier = Modifier.padding(outerPad)
            )
        }
        "widget_mood_mix" -> {
            if (allKnownTracks.isNotEmpty()) {
                MoodMixWidgetCard(
                    viewModel = viewModel,
                    tracks = allKnownTracks,
                    onStartMix = { moodTracks ->
                        if (moodTracks.isNotEmpty()) {
                            viewModel.playbackManager.playTrackList(moodTracks, 0)
                        }
                    },
                    widgetItem = item,
                    modifier = Modifier.padding(outerPad)
                )
            } else if (isEditMode) {
                EditModeEmptyPlaceholder(
                    title = item.customTitle ?: "Ruh Haline Göre Anında Miks",
                    message = "Şarkılar yüklendiğinde anında miks butonları aktif olur",
                    modifier = Modifier.padding(outerPad)
                )
            }
        }
        "widget_recent_tracks" -> {
            if (recentTracks.isNotEmpty()) {
                RecentTracksWidgetCard(
                    tracks = recentTracks,
                    onPlayTrack = { idx -> viewModel.playbackManager.playTrackList(recentTracks, idx) },
                    widgetItem = item,
                    modifier = Modifier.padding(outerPad)
                )
            } else if (isEditMode) {
                EditModeEmptyPlaceholder(
                    title = item.customTitle ?: "Son Dinlenenler Şeridi",
                    message = "Son çaldığın parçalar burada listelenecek",
                    modifier = Modifier.padding(outerPad)
                )
            }
        }
        "widget_listening_summary" -> {
            ListeningSummaryWidgetCard(
                viewModel = viewModel,
                onOpenFullStats = onOpenFullStats,
                widgetItem = item,
                modifier = Modifier.padding(outerPad)
            )
        }
        "block_feed" -> {
            val sections = buildHomeFeedSections(
                item = item,
                isEditMode = isEditMode,
                showSubtitleLegacyOnly = colors.legacyUiMode,
                homeFeedTypes = homeFeedTypes,
                deezerFavorites = deezerFavorites,
                favorites = favorites,
                recentTracks = recentTracks,
                mostPlayedTracks = mostPlayedTracks,
                likedPlaylist = likedPlaylist
            )
            val hPad = if (isEditMode) 12.dp else 16.dp
            Column(modifier = Modifier.fillMaxWidth()) {
                sections.forEach { section ->
                    HomeFeedSectionHeader(
                        section = section,
                        horizontalPadding = hPad,
                        primary = primary,
                        colors = colors,
                        onPlaylistClick = onPlaylistClick
                    )
                    section.visibleTracks.forEachIndexed { index, track ->
                        TrackListItem(
                            track,
                            isPlaying = currentTrackId == track.id,
                            onClick = { viewModel.playbackManager.playTrackList(section.allTracks, index) },
                            onLongClick = { viewModel.openTrackMenu(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditModeEmptyPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.card.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(message, style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary))
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

@Composable
private fun CollapsingHomeHeader(
    fraction: () -> Float,
    bg: Color,
    borderColor: Color,
    statusBarTop: Dp,
    expandedHeaderHeight: Dp,
    collapsedHeaderHeight: Dp,
    headerLogoStyle: HeaderLogoStyle,
    customLogoImage: ImageBitmap?,
    activeDownloads: Int,
    onDownloadsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var headerWidthPx by remember { mutableIntStateOf(0) }

    val statusBarTopPx = with(density) { statusBarTop.toPx() }
    val expandedHeaderPx = with(density) { expandedHeaderHeight.toPx() }
    val collapsedHeaderPx = with(density) { collapsedHeaderHeight.toPx() }
    val expandedLogoSize = 80.dp
    val expandedLogoPx = with(density) { expandedLogoSize.toPx() }
    val collapsedLogoPx = with(density) { 38.dp.toPx() }
    val collapsedLogoXPx = with(density) { 16.dp.toPx() }
    val expandedLogoYPx = with(density) { 12.dp.toPx() }
    val bottomTitleWidthDp = 140.dp
    val bottomTitleWidthPx = with(density) { bottomTitleWidthDp.toPx() }
    val bottomTitleGapPx = with(density) { 6.dp.toPx() }
    val sideTitleXPx = with(density) { (16.dp + 38.dp + 12.dp).toPx() }
    val sideSlideOffsetPx = with(density) { 10.dp.toPx() }
    val downloadTopExpandedPx = with(density) { 10.dp.toPx() }
    val downloadTopCollapsedPx = with(density) { 6.dp.toPx() }
    val dividerThicknessPx = with(density) { 0.5.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { headerWidthPx = it.width }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(collapsedHeaderHeight)
        ) {
            // 1. Logo Box (static 80.dp; scaled and translated exclusively on GPU)
            Box(
                modifier = Modifier
                    .size(expandedLogoSize)
                    .graphicsLayer {
                        val f = fraction()
                        val s = f * f * (3f - 2f * f)
                        val w = headerWidthPx.toFloat()
                        val logoPx = lerpFloat(expandedLogoPx, collapsedLogoPx, s)
                        val scale = logoPx / expandedLogoPx
                        val expandedX = if (w > 0f) (w - logoPx) / 2f else 0f
                        val collapsedY = (collapsedHeaderPx - logoPx) / 2f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = lerpFloat(expandedX, collapsedLogoXPx, s)
                        translationY = lerpFloat(expandedLogoYPx, collapsedY, s)
                    },
                contentAlignment = Alignment.Center
            ) {
                HeaderLogoView(
                    style = headerLogoStyle,
                    customImage = customLogoImage,
                    size = expandedLogoSize
                )
            }

            // 2. Expanded Title below the logo
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
                    .graphicsLayer {
                        val f = fraction()
                        val s = f * f * (3f - 2f * f)
                        val w = headerWidthPx.toFloat()
                        val logoPx = lerpFloat(expandedLogoPx, collapsedLogoPx, s)
                        val expandedX = if (w > 0f) (w - logoPx) / 2f else 0f
                        val logoX = lerpFloat(expandedX, collapsedLogoXPx, s)
                        val collapsedY = (collapsedHeaderPx - logoPx) / 2f
                        val logoY = lerpFloat(expandedLogoYPx, collapsedY, s)
                        val titleAlpha = (1f - f * 2.2f).coerceIn(0f, 1f)
                        val titleScale = 0.85f + 0.15f * titleAlpha
                        alpha = titleAlpha
                        scaleX = titleScale
                        scaleY = titleScale
                        translationX = logoX + (logoPx - bottomTitleWidthPx) / 2f
                        translationY = logoY + logoPx + bottomTitleGapPx
                    }
            )

            // 3. Collapsed Title beside the logo in the top bar
            Box(
                modifier = Modifier
                    .height(collapsedHeaderHeight)
                    .graphicsLayer {
                        val f = fraction()
                        val sideAlpha = ((f - 0.50f) / 0.50f).coerceIn(0f, 1f)
                        alpha = sideAlpha
                        translationX = sideTitleXPx + (1f - sideAlpha) * (-sideSlideOffsetPx)
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
            var lastBadgeLabel by remember { mutableStateOf("") }
            val currentBadgeLabel = remember(activeDownloads) {
                when {
                    activeDownloads <= 0 -> ""
                    activeDownloads > 9999 -> "9999+"
                    else -> activeDownloads.toString()
                }
            }
            if (currentBadgeLabel.isNotEmpty()) {
                lastBadgeLabel = currentBadgeLabel
            }
            val targetShift = if (activeDownloads > 0) (currentBadgeLabel.length * 6).dp else 0.dp
            val animatedShift by animateDpAsState(
                targetValue = targetShift,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "downloadIconShift"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp)
                    .graphicsLayer {
                        val f = fraction()
                        val s = f * f * (3f - 2f * f)
                        translationY = lerpFloat(downloadTopExpandedPx, downloadTopCollapsedPx, s)
                    }
                    .height(48.dp)
                    .width(48.dp + animatedShift)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onDownloadsClick)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "İndirmeler",
                    tint = LocalContentColor.current,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp + animatedShift)
                )
                AnimatedVisibility(
                    visible = activeDownloads > 0,
                    enter = fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.72f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 4.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        shadowElevation = 1.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lastBadgeLabel,
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Bold
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
private fun HeaderLogoView(
    style: HeaderLogoStyle,
    customImage: ImageBitmap?,
    size: Dp
) {
    val headerTint = if (LocalPlasmaColors.current.legacyUiMode) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    }
    when (style) {
        HeaderLogoStyle.CUSTOM -> {
            if (customImage != null) {
                Image(
                    bitmap = customImage,
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
private val FlowQuickGradientBrush = Brush.linearGradient(
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316))
)

@Composable
private fun rememberQuickThemeCardBrush(primary: Color, cardColor: Color): Brush =
    remember(primary, cardColor) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.24f), cardColor))
    }

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
    val isSpecialCard = pin is PinnedItem.Flow ||
        (pin is PinnedItem.Playlist && (pin.playlist.id == DEEZER_FAVORITES_ID || pin.playlist.id == LOCAL_DOWNLOADS_ID))
    val legacyUi = colors.legacyUiMode
    val useThemeAdaptive = legacyUi && isSpecialCard && cardStyle == QuickCardStyle.THEME_ADAPTIVE
    val composeShape = cardShape.toComposeShape()
    val isHorizontal = cardLayout == QuickCardLayout.COMPACT_HORIZONTAL
    val isCircle = cardShape == QuickCardShape.CIRCLE
    val centerIcon = when {
        pin is PinnedItem.Flow -> Icons.Default.AllInclusive
        pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> Icons.Default.Favorite
        else -> Icons.Default.DownloadDone
    }
    val gradient = if (legacyUi && isSpecialCard && !useThemeAdaptive) {
        when {
            pin is PinnedItem.Flow -> FlowQuickGradientBrush
            pin is PinnedItem.Playlist && pin.playlist.id == DEEZER_FAVORITES_ID -> remember(primary) {
                Brush.linearGradient(listOf(primary, Color(0xFF7B63DC), Color(0xFFE993BA)))
            }
            else -> remember(primary) {
                Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF10B981), primary))
            }
        }
    } else null

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
                                        .background(gradient ?: FlowQuickGradientBrush),
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
                        .then(
                            if (legacyUi) Modifier.background(rememberQuickThemeCardBrush(primary, colors.card))
                            else Modifier.background(colors.card)
                        ),
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
                                .background(rememberQuickThemeCardBrush(primary, colors.card)),
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
                        Box(Modifier.fillMaxSize().background(gradient ?: FlowQuickGradientBrush), contentAlignment = Alignment.Center) {
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
                        val downloadedTracks by viewModel.downloadedTracks.collectAsState()
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
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val isExpanded = widgetItem.size == FfiHomeWidgetSize.EXPANDED
    val flowGradient = remember(visuals.accentColor) {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF8B5CF6),
                visuals.accentColor,
                Color(0xFFF97316)
            )
        )
    }
    Surface(
        onClick = onStartFlow,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (legacyUi && widgetItem.style != FfiHomeWidgetStyle.MINIMAL) Color(0xFF1E1B2E) else visuals.containerColor,
        border = visuals.borderStroke,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (legacyUi && widgetItem.style != FfiHomeWidgetStyle.MINIMAL) {
                        Modifier.background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6).copy(alpha = 0.28f),
                                    visuals.accentColor.copy(alpha = 0.18f),
                                    Color.Transparent
                                )
                            )
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(
                    horizontal = if (isCompact) 12.dp else 16.dp,
                    vertical = when {
                        isCompact -> 10.dp
                        isExpanded -> 18.dp
                        else -> 14.dp
                    }
                )
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
                    val iconBoxSize = if (isCompact) 38.dp else if (isExpanded) 52.dp else 44.dp
                    if (legacyUi) {
                        Box(
                            modifier = Modifier
                                .size(iconBoxSize)
                                .background(flowGradient, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AllInclusive,
                                contentDescription = "Flow",
                                tint = Color.White,
                                modifier = Modifier.size(if (isCompact) 22.dp else 28.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(iconBoxSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.cardPressed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AllInclusive,
                                contentDescription = "Flow",
                                tint = visuals.accentColor,
                                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Deezer Flow",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (legacyUi) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            if (legacyUi) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = visuals.accentColor.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        "AI AKIŞ",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                        color = visuals.accentColor,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        if (widgetItem.showSubtitle && (legacyUi || isExpanded)) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Zevkine göre sonsuz müzik akışı",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (legacyUi) Color.White.copy(alpha = 0.70f) else colors.textSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    onClick = onStartFlow,
                    modifier = Modifier.size(if (isCompact) 36.dp else 40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (legacyUi) Color.White else colors.cardPressed,
                        contentColor = if (legacyUi) Color(0xFF1E1B2E) else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = if (legacyUi) visuals.accentColor else MaterialTheme.colorScheme.onSurface,
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
private fun ResumePlaybackWidgetHost(
    viewModel: MainViewModel,
    isEditMode: Boolean,
    widgetItem: FfiHomeWidgetItem,
    onHideCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resumeState = viewModel.resumePlaybackState.collectAsState().value
    if (resumeState != null) {
        ResumePlaybackCard(
            state = resumeState,
            onResume = { viewModel.resumeSavedPlayback() },
            onHideCard = onHideCard,
            widgetItem = widgetItem,
            modifier = modifier
        )
    } else if (isEditMode) {
        EditModeEmptyPlaceholder(
            title = widgetItem.customTitle ?: "Kaldığın Yerden Devam Et",
            message = "Bir şarkı çaldığında kaldığın yer burada gösterilir",
            modifier = modifier
        )
    }
}

@Composable
private fun ResumePlaybackCard(
    state: ResumePlaybackState,
    onResume: () -> Unit,
    onHideCard: () -> Unit,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val safeDur = state.durationMs.coerceAtLeast(1L)
    val fraction = (state.positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)

    val calmResumeContainer = remember(colors.card, visuals.accentColor) {
        visuals.accentColor.copy(alpha = CALM_RESUME_CARD_PRIMARY_MIX).compositeOver(colors.card)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onResume() },
        colors = CardDefaults.cardColors(
            containerColor = if (legacyUi || widgetItem.style != FfiHomeWidgetStyle.CARD) visuals.containerColor else calmResumeContainer
        ),
        shape = RoundedCornerShape(16.dp),
        border = visuals.borderStroke ?: if (legacyUi) BorderStroke(1.dp, visuals.accentColor.copy(alpha = 0.4f)) else null
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            if (!isCompact || widgetItem.showSubtitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        tint = if (legacyUi) visuals.accentColor else colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() }
                            ?: if (legacyUi) "KALDIĞIN YERDEN DEVAM ET" else "Kaldığın yerden devam et",
                        style = if (legacyUi) {
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = visuals.accentColor,
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
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ArtworkImage(
                    url = state.track.artworkUrl,
                    modifier = Modifier.size(if (isCompact) 44.dp else 54.dp),
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
                            color = visuals.accentColor,
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
                    contentPadding = PaddingValues(horizontal = if (isCompact) 10.dp else 12.dp, vertical = 8.dp)
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
    viewModel: MainViewModel,
    onOpenFullStats: () -> Unit,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.trackListeningStats.collectAsState()
    val totalListeningMs by viewModel.totalListeningTimeMs.collectAsState()
    val totalListeningFormatted = remember(totalListeningMs) {
        viewModel.formatDurationTr(totalListeningMs)
    }
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val isExpanded = widgetItem.size == FfiHomeWidgetSize.EXPANDED
    val totalPlays = remember(stats) { stats.sumOf { it.playCount } }
    val itemLimit = if (isCompact) widgetItem.maxItems.toInt().coerceAtMost(2) else widgetItem.maxItems.toInt().coerceIn(1, 10)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 30.dp else 36.dp)
                            .clip(CircleShape)
                            .background(visuals.accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(if (isCompact) 17.dp else 20.dp))
                    }
                } else {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Şarkı ve Dinleme İstatistikleri",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (widgetItem.showSubtitle) {
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
                }
                TextButton(onClick = onOpenFullStats) {
                    Text(
                        "Tümünü gör",
                        color = visuals.accentColor
                    )
                }
            }

            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))
                stats.take(itemLimit).forEachIndexed { idx, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val tracks = stats.map { it.track }
                                if (tracks.isNotEmpty()) {
                                    viewModel.playbackManager.playTrackList(tracks, idx)
                                }
                            }
                            .padding(vertical = if (isCompact) 3.dp else if (isExpanded) 6.dp else 5.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            "#${idx + 1}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (legacyUi) visuals.accentColor else colors.textSecondary
                            ),
                            modifier = Modifier.width(24.dp)
                        )
                        ArtworkImage(
                            url = s.track.artworkUrl,
                            modifier = Modifier.size(if (isCompact) 30.dp else if (isExpanded) 42.dp else 36.dp),
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
                            if (!isCompact || isExpanded) {
                                Text(
                                    s.track.artist,
                                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (legacyUi) visuals.accentColor.copy(alpha = 0.16f) else colors.cardPressed)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${s.playCount} kez",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (legacyUi) visuals.accentColor else colors.textSecondary,
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

private fun mapQuickArtistItems(items: List<uniffi.plasmawave_core.FfiQuickArtistItem>): List<Triple<String, String?, Int>> =
    items.map { item ->
        val primaryArtist = item.artist.split(",").first().trim().ifEmpty { item.artist }
        Triple(primaryArtist, item.sampleTrack.artworkUrl, item.playCount.toInt())
    }.distinctBy { it.first }

@Composable
private fun QuickArtistWidgetCard(
    viewModel: MainViewModel,
    allTracks: List<FfiTrack>,
    onArtistClick: (String) -> Unit,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.trackListeningStats.collectAsState()
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val isExpanded = widgetItem.size == FfiHomeWidgetSize.EXPANDED
    val artistLimit = widgetItem.maxItems.toInt().coerceIn(4, 20)

    var artists by remember(artistLimit) {
        mutableStateOf(mapQuickArtistItems(viewModel.getCachedQuickArtists(artistLimit)))
    }
    LaunchedEffect(allTracks, stats, artistLimit) {
        val mapped = mapQuickArtistItems(
            viewModel.loadQuickArtists(limit = artistLimit, fallbackTracks = allTracks)
        )
        if (mapped != artists) {
            artists = mapped
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(visuals.accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PersonSearch, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.PersonSearch, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Hızlıca Sanatçıya Git",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (widgetItem.showSubtitle && (legacyUi || isExpanded)) {
                        Text(
                            "Sanatçıya dokunarak şarkılarını anında çalın veya arayın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))

            val avatarSize = when (widgetItem.size) {
                FfiHomeWidgetSize.COMPACT -> 46.dp
                FfiHomeWidgetSize.NORMAL -> 58.dp
                FfiHomeWidgetSize.EXPANDED -> 70.dp
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)) {
                items(artists, key = { it.first }) { (artistName, artUrl, _) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(avatarSize + 18.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onArtistClick(artistName) }
                            .padding(4.dp)
                    ) {
                        ArtworkImage(
                            url = artUrl,
                            modifier = Modifier
                                .size(avatarSize)
                                .clip(CircleShape)
                                .then(if (legacyUi) Modifier.border(1.5.dp, visuals.accentColor.copy(alpha = 0.6f), CircleShape) else Modifier),
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
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(visuals.accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Hızlıca Tema Değiştir",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (widgetItem.showSubtitle && legacyUi && !isCompact) {
                        Text(
                            "Tek dokunuşla temalarınız arasında geçiş yapın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (isCompact) 8.dp else 10.dp))

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
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    var currentIndex by remember(tracks.size) {
        mutableIntStateOf(if (tracks.isNotEmpty()) Random.nextInt(tracks.size) else 0)
    }
    val chosenTrack = tracks.getOrNull(currentIndex) ?: tracks.firstOrNull() ?: return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
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
                    Icon(Icons.Default.Casino, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Şanslı Şarkı Seçici",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (widgetItem.showSubtitle && legacyUi && !isCompact) {
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
                        tint = if (legacyUi) visuals.accentColor else colors.textSecondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Başka seç",
                        color = if (legacyUi) visuals.accentColor else colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                    .clickable { onPlayTrack(chosenTrack) }
                    .padding(if (isCompact) 8.dp else 10.dp)
            ) {
                ArtworkImage(
                    url = chosenTrack.artworkUrl,
                    modifier = Modifier.size(if (isCompact) 38.dp else 46.dp),
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
                        containerColor = if (legacyUi) visuals.accentColor else colors.cardPressed,
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
    viewModel: MainViewModel,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val sleepTimerState = viewModel.playbackManager.sleepTimerState.collectAsState().value
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT

    val statusText = when (sleepTimerState) {
        is SleepTimerState.Off -> if (legacyUi && widgetItem.showSubtitle && !isCompact) "Kapalı • Süre seçerek otomatik duraklatmayı başlatın" else null
        is SleepTimerState.EndOfTrack -> "Aktif • Çalan şarkı bittiğinde duracak"
        is SleepTimerState.Countdown -> "Aktif • Kalan süre: ${formatClockMs(sleepTimerState.remainingMs)}"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
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
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Hızlı Uyku Zamanlayıcısı",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (statusText != null) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (sleepTimerState is SleepTimerState.Off || !legacyUi) colors.textSecondary else visuals.accentColor
                            )
                        )
                    }
                }
                if (sleepTimerState !is SleepTimerState.Off) {
                    TextButton(onClick = { viewModel.playbackManager.cancelSleepTimer() }) {
                        Text("Kapat", color = if (legacyUi) Color(0xFFEF4444) else colors.textSecondary)
                    }
                }
            }

            Spacer(Modifier.height(if (isCompact) 6.dp else 10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(15, 30, 45).forEach { mins ->
                    OutlinedButton(
                        onClick = { viewModel.playbackManager.startSleepTimerMinutes(mins) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("$mins dk", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.playbackManager.startSleepTimerEndOfTrack() },
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
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val legacyUi = colors.legacyUiMode
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT

    val moods = listOf(
        Triple(if (legacyUi) "Enerjik ⚡" else "Enerjik", Color(0xFFF59E0B), "enerjik"),
        Triple(if (legacyUi) "Gece Modu 🌙" else "Gece Modu", Color(0xFF8B5CF6), "gece"),
        Triple(if (legacyUi) "Odaklanma 🎯" else "Odaklanma", Color(0xFF10B981), "chill"),
        Triple(if (legacyUi) "Karışık Keyif 🎧" else "Karışık", Color(0xFFEC4899), "kesif")
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (legacyUi) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(visuals.accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Ruh Haline Göre Anında Miks",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (widgetItem.showSubtitle && legacyUi && !isCompact) {
                        Text(
                            "Modunu seç, şarkıların hemen çalmaya başlasın",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (isCompact) 6.dp else 10.dp))

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
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = if (isCompact) 7.dp else 10.dp)
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

@Composable
private fun RecentTracksWidgetCard(
    tracks: List<FfiTrack>,
    onPlayTrack: (Int) -> Unit,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val isExpanded = widgetItem.size == FfiHomeWidgetSize.EXPANDED
    val sliced = remember(tracks, widgetItem.maxItems) {
        tracks.take(widgetItem.maxItems.toInt().coerceIn(3, 20))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Son Dinlenenler Şeridi",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (widgetItem.showSubtitle && !isCompact) {
                        Text(
                            "Son çaldığın parçalara anında geri dön",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))

            val coverSize = if (isCompact) 72.dp else if (isExpanded) 112.dp else 92.dp
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(sliced, key = { idx, t -> "recent_w:${t.id}:$idx" }) { index, track ->
                    Column(
                        modifier = Modifier
                            .width(coverSize)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPlayTrack(index) }
                    ) {
                        ArtworkImage(
                            url = track.artworkUrl,
                            modifier = Modifier.size(coverSize),
                            fallback = Icons.Default.MusicNote,
                            shape = RoundedCornerShape(10.dp),
                            fallbackTitle = track.title,
                            fallbackArtist = track.artist
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            track.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isCompact) {
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningSummaryWidgetCard(
    viewModel: MainViewModel,
    onOpenFullStats: () -> Unit,
    widgetItem: FfiHomeWidgetItem,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.trackListeningStats.collectAsState()
    val totalListeningMs by viewModel.totalListeningTimeMs.collectAsState()
    val totalListeningFormatted = remember(totalListeningMs) {
        viewModel.formatDurationTr(totalListeningMs)
    }
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val visuals = resolveHomeWidgetVisuals(widgetItem, colors, primary)
    val isCompact = widgetItem.size == FfiHomeWidgetSize.COMPACT
    val totalPlays = remember(stats) { stats.sumOf { it.playCount } }
    val topArtist = remember(stats) { stats.firstOrNull()?.track?.artist ?: "—" }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenFullStats() },
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        border = visuals.borderStroke,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(if (isCompact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = visuals.accentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    widgetItem.customTitle?.takeIf { it.isNotBlank() } ?: "Mini Dinleme Özeti",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }

            Spacer(Modifier.height(if (isCompact) 6.dp else 10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "Süre" to totalListeningFormatted,
                    "Dinleme" to "$totalPlays",
                    "Favori" to topArtist
                ).forEach { (label, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.cardPressed.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = if (isCompact) 6.dp else 8.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary),
                            maxLines = 1
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = visuals.accentColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
