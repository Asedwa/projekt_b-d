package org.kde.plasmawave.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.kde.plasmawave.player.PlaybackProgress
import org.kde.plasmawave.player.PlayerUiState
import org.kde.plasmawave.theme.DynamicThemeHelper
import org.kde.plasmawave.theme.ExtractedArtworkColors
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiRepeatMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    playerState: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    sheetState: SheetState,
    dynamicColorEnabled: Boolean,
    seekbarStyle: SeekbarStyle,
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
    canStartMix: Boolean
) {
    val entry = playerState.currentEntry ?: return
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val context = LocalContext.current

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
        primary.copy(alpha = 0.24f)
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

    val headerBrush = remember(animTopColor, animBottomColor, bg) {
        Brush.verticalGradient(
            0.0f to animTopColor.copy(alpha = 0.85f),
            0.55f to animBottomColor,
            1.0f to bg
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = bg, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(headerBrush)) {
            val coverSize = (maxHeight * 0.40f).coerceIn(180.dp, 320.dp)
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Oynatıcıyı kapat", modifier = Modifier.size(30.dp)) }
                    Text("ŞİMDİ ÇALIYOR", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = colors.textSecondary)
                    IconButton(onClick = onOpenMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Şarkı seçenekleri") }
                }
                Spacer(Modifier.height(16.dp))
                ArtworkImage(url = entry.track.artworkUrl, modifier = Modifier.size(coverSize), shape = RoundedCornerShape(16.dp), background = colors.card)
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text("${entry.track.artist} • ${entry.track.album}", style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "Beğeniyi kaldır" else "Şarkıyı beğen",
                            tint = if (isFavorite) effectiveAccent else MaterialTheme.colorScheme.onBackground)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PlayerSeekSection(
                    progressFlow = progressFlow,
                    style = seekbarStyle,
                    accentColor = effectiveAccent,
                    isPlaying = playerState.isPlaying,
                    onSeekTo = onSeekTo
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleShuffle) { Icon(Icons.Default.Shuffle, contentDescription = "Karıştır", tint = if (playerState.isShuffle) effectiveAccent else colors.textSecondary) }
                    IconButton(onClick = onSkipPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Önceki", modifier = Modifier.size(36.dp)) }
                    Box(Modifier.size(68.dp).clip(CircleShape).background(effectiveAccent), contentAlignment = Alignment.Center) {
                        IconButton(onClick = onTogglePlayPause, modifier = Modifier.fillMaxSize()) {
                            Icon(if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Duraklat" else "Çal", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = onSkipNext) { Icon(Icons.Default.SkipNext, contentDescription = "Sonraki", modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = onToggleRepeat) {
                        val (icon, tint) = when (playerState.repeatMode) {
                            FfiRepeatMode.OFF -> Icons.Default.Repeat to colors.textSecondary
                            FfiRepeatMode.ALL -> Icons.Default.Repeat to effectiveAccent
                            FfiRepeatMode.ONE -> Icons.Default.RepeatOne to effectiveAccent
                        }
                        Icon(icon, contentDescription = "Tekrarla", tint = tint)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onAddToPlaylist, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Listeye ekle", maxLines = 1)
                    }
                    OutlinedButton(onClick = onStartMix, enabled = canStartMix, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Şarkı miksi", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekSection(
    progressFlow: StateFlow<PlaybackProgress>,
    style: SeekbarStyle,
    accentColor: Color,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit
) {
    val colors = LocalPlasmaColors.current
    val progress by progressFlow.collectAsState()
    CustomSeekbar(
        positionMs = progress.positionMs,
        durationMs = progress.durationMs,
        style = style,
        primaryColor = accentColor,
        trackColor = colors.border.copy(alpha = 0.6f),
        isPlaying = isPlaying,
        onSeekTo = onSeekTo,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        showTimeLabels = true
    )
}
