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
            0.45f to animTopColor.copy(alpha = 0.92f),
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
            val isCompactScreen = maxHeight < 660.dp
            val coverSize = (maxHeight * 0.38f).coerceIn(180.dp, 330.dp)

            Column(
                modifier = if (isCompactScreen) {
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 12.dp)
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 12.dp)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isCompactScreen) Arrangement.spacedBy(16.dp) else Arrangement.SpaceBetween
            ) {
                // 1. Top Header Row (Playing X of Y Pill, Cast, 3-dots Menu)
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

                // 2. Cover Art with Selected Frame and Swipe-to-Skip Gesture
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
                                        // Swipe Left -> Skip Next
                                        coroutineScope.launch {
                                            dragOffset.animateTo(-700f, tween(150))
                                            onSkipNext()
                                            dragOffset.snapTo(700f)
                                            dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                                        }
                                    } else if (dragOffset.value > threshold) {
                                        // Swipe Right -> Skip Previous
                                        coroutineScope.launch {
                                            dragOffset.animateTo(700f, tween(150))
                                            onSkipPrevious()
                                            dragOffset.snapTo(-700f)
                                            dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                                        }
                                    } else {
                                        // Snap back
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
                        isPlaying = playerState.isPlaying,
                        size = coverSize
                    )
                }

                // 3. Seekbar & Audio Quality Info Row
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
                        isPlaying = playerState.isPlaying,
                        onSeekTo = onSeekTo,
                        modifier = Modifier.fillMaxWidth(),
                        showTimeLabels = false
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
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

                // 4. Centered Title, Clickable Artist and Album Info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        entry.track.title,
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
                        entry.track.artist,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onArtistClick(entry.track.artist) }
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                    if (entry.track.album.isNotBlank() && !entry.track.album.equals(entry.track.title, ignoreCase = true)) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            entry.track.album,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                            color = Color.White.copy(alpha = 0.50f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAlbumClick(entry.track.album, entry.track.artist) }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                // 5. Playback Controls Row (|<  (10)  ( > )  (10)  >|)
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
                            modifier = Modifier.size(34.dp),
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { onSeekTo((progress.positionMs - 10000).coerceAtLeast(0L)) }) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "10s Geri",
                            modifier = Modifier.size(28.dp),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(effectiveAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = onTogglePlayPause, modifier = Modifier.fillMaxSize()) {
                            val onAccent = if (effectiveAccent.luminance() > 0.5f) Color(0xFF121212) else Color.White
                            Icon(
                                if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Duraklat" else "Çal",
                                tint = onAccent,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                    IconButton(onClick = { onSeekTo((progress.positionMs + 10000).coerceAtMost(progress.durationMs)) }) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "10s İleri",
                            modifier = Modifier.size(28.dp),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Sonraki",
                            modifier = Modifier.size(34.dp),
                            tint = Color.White
                        )
                    }
                }

                // 6. Sleek 7-Icon Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleRepeat) {
                        val (icon, tint) = when (playerState.repeatMode) {
                            FfiRepeatMode.OFF -> Icons.Default.Repeat to Color.White.copy(alpha = 0.65f)
                            FfiRepeatMode.ALL -> Icons.Default.Repeat to effectiveAccent
                            FfiRepeatMode.ONE -> Icons.Default.RepeatOne to effectiveAccent
                        }
                        Icon(icon, contentDescription = "Tekrarla", tint = tint, modifier = Modifier.size(23.dp))
                    }
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Karıştır",
                            tint = if (playerState.isShuffle) effectiveAccent else Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    IconButton(onClick = onStartMix, enabled = canStartMix) {
                        Icon(
                            Icons.Default.Radio,
                            contentDescription = "Şarkı miksi",
                            tint = if (canStartMix) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.30f),
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    IconButton(onClick = onShowSleepTimer) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Uyku zamanlayıcı",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "Beğeniyi kaldır" else "Şarkıyı beğen",
                            tint = if (isFavorite) effectiveAccent else Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Listeye ekle",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "Seçenekler",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
