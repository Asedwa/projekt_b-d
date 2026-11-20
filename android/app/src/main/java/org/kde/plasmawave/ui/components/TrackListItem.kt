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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kde.plasmawave.download.DownloadStorage
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiTrack

data class DownloadStatusSnapshot(
    val downloadedTrackIds: Set<String> = emptySet(),
    val activeTrackProgress: Map<String, Int?> = emptyMap(),
    val failedTrackErrors: Map<String, String> = emptyMap(),
    val unavailableTrackIds: Set<String> = emptySet(),
    val downloadedPlaylistIds: Set<String> = emptySet()
)

val LocalDeezerConnected = compositionLocalOf { false }
val LocalDownloadStatus = compositionLocalOf { DownloadStatusSnapshot() }

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
    isUnavailable: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit = onLongClick,
    modifier: Modifier = Modifier
) {
    val customColors = LocalPlasmaColors.current
    val deezerConnected = LocalDeezerConnected.current
    val downloadStatus = LocalDownloadStatus.current
    val normalizedId = DownloadStorage.normalizeTrackId(track.id)
    val isDownloaded = track.provider == "local" ||
        (normalizedId.isNotBlank() && normalizedId in downloadStatus.downloadedTrackIds)
    val activeProgress = if (normalizedId.isNotBlank() && normalizedId in downloadStatus.activeTrackProgress) {
        downloadStatus.activeTrackProgress[normalizedId]
    } else null
    val isDownloading = !isDownloaded && normalizedId.isNotBlank() && normalizedId in downloadStatus.activeTrackProgress
    val effectivelyUnavailable = !isDownloaded && !isDownloading &&
        (isUnavailable || (normalizedId.isNotBlank() && normalizedId in downloadStatus.unavailableTrackIds))
    val isFailed = !isDownloaded && !isDownloading && !effectivelyUnavailable &&
        normalizedId.isNotBlank() && normalizedId in downloadStatus.failedTrackErrors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (effectivelyUnavailable) 0.48f else 1f)
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
            tint = if (isPlaying && customColors.legacyUiMode) MaterialTheme.colorScheme.primary else customColors.textSecondary,
            fallbackTitle = track.title,
            fallbackArtist = track.artist
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
                    color = if (isPlaying && customColors.legacyUiMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Cihaza indirildi",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (isDownloading) {
                    if (activeProgress != null && activeProgress > 0) {
                        CircularProgressIndicator(
                            progress = { (activeProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                } else if (effectivelyUnavailable) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "Deezer'da kullanılamıyor",
                        tint = customColors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Deezer'da kullanılamıyor • ",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = customColors.textSecondary,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                } else if (isFailed) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "İndirilemedi",
                        tint = customColors.negative,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
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
                        text = if (customColors.legacyUiMode) "[PREVIEW] " else "Önizleme • ",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (customColors.legacyUiMode) MaterialTheme.colorScheme.primary else customColors.textSecondary,
                            fontSize = if (customColors.legacyUiMode) 10.sp else 11.sp
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
