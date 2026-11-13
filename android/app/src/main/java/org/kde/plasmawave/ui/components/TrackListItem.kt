package org.kde.plasmawave.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiTrack

val LocalDeezerConnected = compositionLocalOf { false }

fun formatDuration(ms: ULong): String {
    val totalSec = ms / 1000u
    val min = totalSec / 60u
    val sec = totalSec % 60u
    return if (sec < 10u) "$min:0$sec" else "$min:$sec"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: FfiTrack,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit = onLongClick,
    modifier: Modifier = Modifier
) {
    val customColors = LocalPlasmaColors.current
    val deezerConnected = LocalDeezerConnected.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            url = track.artworkUrl,
            modifier = Modifier.size(48.dp),
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else customColors.textSecondary
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title & Artist
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.origin == "development_fixture") {
                    Text(
                        text = "[FIXTURE] ",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = customColors.warning,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                } else if (track.isPreview && !(track.provider == "deezer" && deezerConnected)) {
                    Text(
                        text = "[PREVIEW] ",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    )
                }
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = customColors.textSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Duration
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
        )
        IconButton(onClick = onMoreClick, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Şarkı seçenekleri", tint = customColors.textSecondary)
        }
    }
}
