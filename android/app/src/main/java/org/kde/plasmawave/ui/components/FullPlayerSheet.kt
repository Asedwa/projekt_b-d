package org.kde.plasmawave.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.StreamingQuality
import org.kde.plasmawave.player.PlaybackProgress
import org.kde.plasmawave.player.PlayerUiState
import org.kde.plasmawave.theme.DynamicThemeHelper
import org.kde.plasmawave.theme.ExtractedArtworkColors
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiRepeatMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    playerState: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    sheetState: SheetState,
    dynamicColorEnabled: Boolean,
    seekbarStyle: SeekbarStyle,
    albumFrame: AlbumCoverFrame,
    swipeToSkipEnabled: Boolean,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenMenu: () -> Unit,
    onStartMix: () -> Unit,
    canStartMix: Boolean,
    onShowSleepTimer: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String, String) -> Unit = { _, _ -> }
) {
    var lastEntry by remember { mutableStateOf(playerState.currentEntry) }
    val entry = playerState.currentEntry ?: lastEntry ?: return
    LaunchedEffect(playerState.currentEntry) {
        playerState.currentEntry?.let { lastEntry = it }
    }
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val context = LocalContext.current
    val progress by progressFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var extractedColors by remember(entry.track.artworkUrl) { mutableStateOf<ExtractedArtworkColors?>(null) }
    LaunchedEffect(entry.track.artworkUrl, dynamicColorEnabled) {
        if (dynamicColorEnabled && !entry.track.artworkUrl.isNullOrBlank()) {
            extractedColors = DynamicThemeHelper.extractColors(context, entry.track.artworkUrl)
        } else {
            extractedColors = null
        }
    }

    val targetTopColor = if (dynamicColorEnabled && extractedColors != null) {
        extractedColors!!.backgroundTop
    } else {
        Color(androidx.core.graphics.ColorUtils.blendARGB(bg.toArgb(), primary.toArgb(), 0.25f))
    }

    val targetBottomColor = if (dynamicColorEnabled && extractedColors != null) {
        extractedColors!!.backgroundBottom
    } else {
        bg
    }

    val animTopColor by animateColorAsState(
        targetValue = targetTopColor,
        animationSpec = tween(500),
        label = "animTopColor"
    )
    val animBottomColor by animateColorAsState(
        targetValue = targetBottomColor,
        animationSpec = tween(500),
        label = "animBottomColor"
    )

    val effectiveAccent = if (dynamicColorEnabled && extractedColors != null) {
        extractedColors!!.accent
    } else {
        primary
    }

    val screenBrush = remember(animTopColor, animBottomColor) {
        Brush.verticalGradient(
            0.0f to animTopColor.copy(alpha = 1f),
            0.50f to animTopColor.copy(alpha = 0.88f),
            1.0f to animBottomColor.copy(alpha = 1f)
        )
    }

    val totalTracks = playerState.queueSnapshot?.entries?.size ?: 1
    val currentTrackIndex = (playerState.queueSnapshot?.currentIndex?.toInt() ?: 0) + 1

    val audioQualityLabel = remember(playerState.audioQualityLabel, entry.track.id, entry.track.playbackUri, entry.track.isPreview) {
        if (playerState.audioQualityLabel.isNotBlank()) {
            playerState.audioQualityLabel
        } else if (entry.track.isPreview) {
            "MP3 • 128 kb/s • 44,1 kHz"
        } else {
            val session = DeezerSession.get(context)
            val format = session.getResolvedTrackFormat(entry.track.id)
            if (format != null) {
                when {
                    format.contains("FLAC", ignoreCase = true) -> "FLAC • 44,1 kHz • Kayıpsız"
                    format.contains("128") -> "MP3 • 128 kb/s • 44,1 kHz"
                    else -> "MP3 • 320 kb/s • 44,1 kHz"
                }
            } else {
                when (session.currentStreamingQuality()) {
                    StreamingQuality.FLAC -> "FLAC • 44,1 kHz • Kayıpsız"
                    StreamingQuality.MP3_128 -> "MP3 • 128 kb/s • 44,1 kHz"
                    StreamingQuality.MP3_320 -> "MP3 • 320 kb/s • 44,1 kHz"
                    StreamingQuality.AUTO -> "MP3 • 320 kb/s • 44,1 kHz"
                }
            }
        }
    }

    // Horizontal swipe gesture offset for album cover
    val dragOffset = remember { Animatable(0f) }
    LaunchedEffect(entry.track.id) {
        dragOffset.snapTo(0f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bg,
        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
        dragHandle = null,
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .background(screenBrush)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                // LANDSCAPE: Two Column Layout with Artwork Left, Controls Right
                val landCoverSize = (maxHeight - 32.dp).coerceIn(160.dp, 300.dp)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerArtwork(
                            coverSize = landCoverSize,
                            dragOffset = dragOffset,
                            swipeToSkipEnabled = swipeToSkipEnabled,
                            entry = entry,
                            albumFrame = albumFrame,
                            effectiveAccent = effectiveAccent,
                            isPlaying = playerState.isPlaying,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            coroutineScope = coroutineScope
                        )
                    }

                    Spacer(Modifier.width(18.dp))

                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PlayerHeader(
                            currentTrackIndex = currentTrackIndex,
                            totalTracks = totalTracks,
                            onDismiss = onDismiss,
                            onOpenMenu = onOpenMenu,
                            context = context
                        )

                        PlayerInfo(
                            title = entry.track.title,
                            artist = entry.track.artist,
                            album = entry.track.album,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick
                        )

                        PlayerSeekbar(
                            progress = progress,
                            seekbarStyle = seekbarStyle,
                            effectiveAccent = effectiveAccent,
                            isPlaying = playerState.isPlaying,
                            audioQualityLabel = audioQualityLabel,
                            onSeekTo = onSeekTo
                        )

                        PlayerControls(
                            isPlaying = playerState.isPlaying,
                            effectiveAccent = effectiveAccent,
                            progress = progress,
                            onSkipPrevious = onSkipPrevious,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipNext = onSkipNext,
                            onSeekTo = onSeekTo,
                            compact = true
                        )

                        PlayerActionBar(
                            repeatMode = playerState.repeatMode,
                            isShuffle = playerState.isShuffle,
                            canStartMix = canStartMix,
                            isFavorite = isFavorite,
                            effectiveAccent = effectiveAccent,
                            onToggleRepeat = onToggleRepeat,
                            onToggleShuffle = onToggleShuffle,
                            onStartMix = onStartMix,
                            onShowSleepTimer = onShowSleepTimer,
                            onToggleFavorite = onToggleFavorite,
                            onAddToPlaylist = onAddToPlaylist,
                            onOpenMenu = onOpenMenu,
                            compact = true
                        )
                    }
                }
            } else {
                // PORTRAIT: Perfectly spaced, full-height single column layout
                val isCompactScreen = maxHeight < 660.dp
                val coverSize = (maxHeight * 0.42f).coerceIn(200.dp, 360.dp)

                Column(
                    modifier = if (isCompactScreen) {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = 16.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = 18.dp)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (isCompactScreen) Arrangement.spacedBy(16.dp) else Arrangement.SpaceBetween
                ) {
                    PlayerHeader(
                        currentTrackIndex = currentTrackIndex,
                        totalTracks = totalTracks,
                        onDismiss = onDismiss,
                        onOpenMenu = onOpenMenu,
                        context = context
                    )

                    PlayerArtwork(
                        coverSize = coverSize,
                        dragOffset = dragOffset,
                        swipeToSkipEnabled = swipeToSkipEnabled,
                        entry = entry,
                        albumFrame = albumFrame,
                        effectiveAccent = effectiveAccent,
                        isPlaying = playerState.isPlaying,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext,
                        coroutineScope = coroutineScope
                    )

                    PlayerSeekbar(
                        progress = progress,
                        seekbarStyle = seekbarStyle,
                        effectiveAccent = effectiveAccent,
                        isPlaying = playerState.isPlaying,
                        audioQualityLabel = audioQualityLabel,
                        onSeekTo = onSeekTo
                    )

                    PlayerInfo(
                        title = entry.track.title,
                        artist = entry.track.artist,
                        album = entry.track.album,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick
                    )

                    PlayerControls(
                        isPlaying = playerState.isPlaying,
                        effectiveAccent = effectiveAccent,
                        progress = progress,
                        onSkipPrevious = onSkipPrevious,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSeekTo = onSeekTo,
                        compact = false
                    )

                    PlayerActionBar(
                        repeatMode = playerState.repeatMode,
                        isShuffle = playerState.isShuffle,
                        canStartMix = canStartMix,
                        isFavorite = isFavorite,
                        effectiveAccent = effectiveAccent,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onStartMix = onStartMix,
                        onShowSleepTimer = onShowSleepTimer,
                        onToggleFavorite = onToggleFavorite,
                        onAddToPlaylist = onAddToPlaylist,
                        onOpenMenu = onOpenMenu,
                        compact = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    currentTrackIndex: Int,
    totalTracks: Int,
    onDismiss: () -> Unit,
    onOpenMenu: () -> Unit,
    context: android.content.Context
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onDismiss,
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.35f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Kapat",
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Çalıyor $currentTrackIndex / $totalTracks",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { Toast.makeText(context, "Yayın cihazı aranıyor...", Toast.LENGTH_SHORT).show() },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
            ) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = "Yayınla",
                    modifier = Modifier.size(19.dp),
                    tint = Color.White
                )
            }
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Şarkı seçenekleri",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun PlayerArtwork(
    coverSize: androidx.compose.ui.unit.Dp,
    dragOffset: Animatable<Float, *>,
    swipeToSkipEnabled: Boolean,
    entry: uniffi.plasmawave_core.FfiQueueEntry,
    albumFrame: AlbumCoverFrame,
    effectiveAccent: Color,
    isPlaying: Boolean,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    Box(
        modifier = Modifier
            .size(coverSize)
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = (dragOffset.value / 600f) * 10f
                alpha = (1f - (kotlin.math.abs(dragOffset.value) / 800f)).coerceIn(0.35f, 1f)
            }
            .pointerInput(entry.track.id, swipeToSkipEnabled) {
                if (!swipeToSkipEnabled) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            dragOffset.snapTo(dragOffset.value + dragAmount)
                        }
                    },
                    onDragEnd = {
                        val threshold = 160f
                        if (dragOffset.value < -threshold) {
                            coroutineScope.launch {
                                dragOffset.animateTo(-700f, tween(150))
                                onSkipNext()
                                dragOffset.snapTo(700f)
                                dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                            }
                        } else if (dragOffset.value > threshold) {
                            coroutineScope.launch {
                                dragOffset.animateTo(700f, tween(150))
                                onSkipPrevious()
                                dragOffset.snapTo(-700f)
                                dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                            }
                        } else {
                            coroutineScope.launch {
                                dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch { dragOffset.animateTo(0f) }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        ArtworkFramedView(
            artworkUrl = entry.track.artworkUrl,
            frame = albumFrame,
            accentColor = effectiveAccent,
            isPlaying = isPlaying,
            size = coverSize
        )
    }
}

@Composable
private fun PlayerSeekbar(
    progress: PlaybackProgress,
    seekbarStyle: SeekbarStyle,
    effectiveAccent: Color,
    isPlaying: Boolean,
    audioQualityLabel: String,
    onSeekTo: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        CustomSeekbar(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            style = seekbarStyle,
            primaryColor = effectiveAccent,
            trackColor = Color.White.copy(alpha = 0.22f),
            isPlaying = isPlaying,
            onSeekTo = onSeekTo,
            modifier = Modifier.fillMaxWidth(),
            showTimeLabels = false
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatMs(progress.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f)
            )
            Text(
                audioQualityLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.60f)
            )
            Text(
                formatMs(progress.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun PlayerInfo(
    title: String,
    artist: String,
    album: String,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            artist,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onArtistClick(artist) }
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
        if (album.isNotBlank() && !album.equals(title, ignoreCase = true)) {
            Spacer(Modifier.height(2.dp))
            Text(
                album,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                color = Color.White.copy(alpha = 0.50f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAlbumClick(album, artist) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    effectiveAccent: Color,
    progress: PlaybackProgress,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    compact: Boolean
) {
    val playBtnSize = if (compact) 54.dp else 72.dp
    val playIconSize = if (compact) 30.dp else 38.dp
    val skipSize = if (compact) 28.dp else 34.dp
    val jumpSize = if (compact) 24.dp else 28.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSkipPrevious) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Önceki",
                modifier = Modifier.size(skipSize),
                tint = Color.White
            )
        }
        IconButton(onClick = { onSeekTo((progress.positionMs - 10000).coerceAtLeast(0L)) }) {
            Icon(
                Icons.Default.Replay10,
                contentDescription = "10s Geri",
                modifier = Modifier.size(jumpSize),
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        Box(
            modifier = Modifier
                .size(playBtnSize)
                .clip(CircleShape)
                .background(effectiveAccent),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.fillMaxSize()) {
                val onAccent = if (effectiveAccent.luminance() > 0.5f) Color(0xFF121212) else Color.White
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Duraklat" else "Çal",
                    tint = onAccent,
                    modifier = Modifier.size(playIconSize)
                )
            }
        }
        IconButton(onClick = { onSeekTo((progress.positionMs + 10000).coerceAtMost(progress.durationMs)) }) {
            Icon(
                Icons.Default.Forward10,
                contentDescription = "10s İleri",
                modifier = Modifier.size(jumpSize),
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        IconButton(onClick = onSkipNext) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Sonraki",
                modifier = Modifier.size(skipSize),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun PlayerActionBar(
    repeatMode: FfiRepeatMode,
    isShuffle: Boolean,
    canStartMix: Boolean,
    isFavorite: Boolean,
    effectiveAccent: Color,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onStartMix: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenMenu: () -> Unit,
    compact: Boolean
) {
    val iconSize = if (compact) 20.dp else 23.dp
    val favSize = if (compact) 21.dp else 24.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleRepeat) {
            val (icon, tint) = when (repeatMode) {
                FfiRepeatMode.OFF -> Icons.Default.Repeat to Color.White.copy(alpha = 0.65f)
                FfiRepeatMode.ALL -> Icons.Default.Repeat to effectiveAccent
                FfiRepeatMode.ONE -> Icons.Default.RepeatOne to effectiveAccent
            }
            Icon(icon, contentDescription = "Tekrarla", tint = tint, modifier = Modifier.size(iconSize))
        }
        IconButton(onClick = onToggleShuffle) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Karıştır",
                tint = if (isShuffle) effectiveAccent else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(iconSize)
            )
        }
        IconButton(onClick = onStartMix, enabled = canStartMix) {
            Icon(
                Icons.Default.Radio,
                contentDescription = "Şarkı miksi",
                tint = if (canStartMix) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(iconSize)
            )
        }
        IconButton(onClick = onShowSleepTimer) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Uyku zamanlayıcı",
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size((iconSize.value - 1).dp)
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Beğeniyi kaldır" else "Şarkıyı beğen",
                tint = if (isFavorite) effectiveAccent else Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(favSize)
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = "Listeye ekle",
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(favSize)
            )
        }
        IconButton(onClick = onOpenMenu) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "Seçenekler",
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
