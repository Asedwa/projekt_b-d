package org.kde.plasmawave.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.flow.StateFlow
import org.kde.plasmawave.player.PlaybackProgress
import org.kde.plasmawave.player.PlayerUiState
import org.kde.plasmawave.theme.DynamicThemeHelper
import org.kde.plasmawave.theme.ExtractedArtworkColors
import org.kde.plasmawave.theme.LocalPlasmaColors

@Composable
fun MiniPlayer(
    playerState: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dynamicColorEnabled: Boolean = true
) {
    val entry = playerState.currentEntry ?: return
    val customColors = LocalPlasmaColors.current
    val context = LocalContext.current
    val usePalette = !customColors.legacyUiMode && dynamicColorEnabled

    var extractedColors by remember(entry.track.artworkUrl, usePalette) {
        mutableStateOf(
            if (usePalette) DynamicThemeHelper.getCachedColors(entry.track.artworkUrl, calmMode = true)
            else null
        )
    }
    LaunchedEffect(entry.track.artworkUrl, usePalette) {
        if (usePalette && !entry.track.artworkUrl.isNullOrBlank() && extractedColors == null) {
            extractedColors = DynamicThemeHelper.extractColors(context, entry.track.artworkUrl, calmMode = true)
        } else if (!usePalette || entry.track.artworkUrl.isNullOrBlank()) {
            extractedColors = null
        }
    }

    val targetContainer = if (usePalette && extractedColors != null) {
        Color(ColorUtils.blendARGB(customColors.card.toArgb(), extractedColors!!.backgroundTop.toArgb(), 0.42f))
    } else {
        customColors.card
    }
    val targetAccent = if (usePalette && extractedColors != null) {
        extractedColors!!.accent
    } else {
        MaterialTheme.colorScheme.primary
    }

    val containerColor by animateColorAsState(targetValue = targetContainer, animationSpec = tween(420), label = "miniBg")
    val accentColor by animateColorAsState(targetValue = targetAccent, animationSpec = tween(420), label = "miniAccent")
    val trackColor = if (customColors.legacyUiMode) customColors.border else Color.White.copy(alpha = 0.14f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
    ) {
        MiniProgressBar(progressFlow = progressFlow, accentColor = accentColor, trackColor = trackColor)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                url = entry.track.artworkUrl,
                modifier = Modifier.size(44.dp),
                background = customColors.border,
                fallbackTitle = entry.track.title,
                fallbackArtist = entry.track.artist
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.track.artist} • ${entry.track.album}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = customColors.textSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Controls
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    tint = accentColor,
                    modifier = Modifier.size(30.dp)
                )
            }

            IconButton(onClick = onSkipNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniProgressBar(progressFlow: StateFlow<PlaybackProgress>, accentColor: Color, trackColor: Color) {
    val progressState by progressFlow.collectAsState()
    val fraction = if (progressState.durationMs > 0) {
        (progressState.positionMs.toFloat() / progressState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
        color = accentColor,
        trackColor = trackColor
    )
}
