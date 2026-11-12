package org.kde.plasmawave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiCapabilityStatus
import uniffi.plasmawave_core.FfiModInfo
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongLongPressBottomSheet(
    track: FfiTrack?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onPlayNext: (FfiTrack) -> Unit,
    onAddToQueue: (FfiTrack) -> Unit,
    onToggleFavorite: (FfiTrack) -> Unit,
    onAddToPlaylist: (FfiTrack) -> Unit,
    onDownload: (FfiTrack) -> Unit,
    isPinned: Boolean,
    onTogglePin: (FfiTrack) -> Unit,
    canDownloadDeezer: Boolean,
    customModActions: List<FfiModInfo> = emptyList(),
    onExecuteModAction: (FfiModInfo, FfiTrack) -> Unit = { _, _ -> }
) {
    if (track == null) return

    val customColors = LocalPlasmaColors.current
    val core = PlasmaWaveApp.core

    var isFav by remember(track.id) { mutableStateOf(false) }
    var downloadCap by remember(track.id) {
        mutableStateOf(FfiCapabilityStatus(isAvailable = false, requiresAuth = false, unavailableReason = null))
    }
    var offlinePath by remember(track.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id) {
        try {
            isFav = core.isFavorite(track.id)
            downloadCap = core.getTrackDownloadCapability(track.id)
            offlinePath = core.getOfflinePath(track.id)
        } catch (_: Exception) {}
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = customColors.card,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Track Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    url = track.artworkUrl,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(10.dp),
                    background = customColors.border,
                    tint = customColors.textSecondary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = customColors.border.let { androidx.compose.material3.MaterialTheme.colorScheme.onSurface }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                            color = customColors.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.album} • ${formatDuration(track.durationMs)}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                            color = customColors.textDisabled
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                color = customColors.divider,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            // 1. Play Next
            ActionMenuItem(
                icon = Icons.Default.SkipNext,
                title = "Play next",
                onClick = {
                    onPlayNext(track)
                    onDismiss()
                }
            )

            // 2. Add to Queue
            ActionMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Add to queue",
                onClick = {
                    onAddToQueue(track)
                    onDismiss()
                }
            )

            // 3. Add to favorites / Remove favorite
            ActionMenuItem(
                icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = if (isFav) "Remove favorite" else "Add to favorites",
                tint = if (isFav) customColors.negative else null,
                onClick = {
                    onToggleFavorite(track)
                    onDismiss()
                }
            )

            // 4. Add to playlist
            ActionMenuItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Add to playlist",
                onClick = {
                    onAddToPlaylist(track)
                    onDismiss()
                }
            )

            ActionMenuItem(
                icon = Icons.Default.PushPin,
                title = if (isPinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle",
                onClick = { onTogglePin(track); onDismiss() }
            )

            // 5. Download (Capability-Gated)
            if (track.provider == "deezer") {
                ActionMenuItem(
                    icon = Icons.Default.CloudDownload,
                    title = "Şarkıyı indir",
                    subtitle = if (canDownloadDeezer) "MP3 veya FLAC olarak kaydet" else "Deezer'a giriş yap",
                    enabled = canDownloadDeezer,
                    onClick = { onDownload(track); onDismiss() }
                )
            } else if (offlinePath != null) {
                ActionMenuItem(
                    icon = Icons.Default.CloudDone,
                    title = if (track.provider == "deezer") "Preview available offline" else "Offline available",
                    subtitle = "Stored on device: $offlinePath",
                    tint = customColors.positive,
                    enabled = true,
                    onClick = { onDismiss() }
                )
            } else {
                ActionMenuItem(
                    icon = Icons.Default.CloudDownload,
                    title = if (track.isPreview || track.provider == "deezer") "Download Preview" else "Download Track",
                    subtitle = if (!downloadCap.isAvailable) {
                        downloadCap.unavailableReason ?: "Downloads not authorized for this source"
                    } else if (track.provider == "deezer") "Save the 30-second preview" else "Save for offline listening",
                    enabled = downloadCap.isAvailable,
                    onClick = {
                        if (downloadCap.isAvailable) {
                            onDownload(track)
                            onDismiss()
                        }
                    }
                )
            }

            // 7. Custom Mod Actions (WASM Plugins)
            if (customModActions.isNotEmpty()) {
                HorizontalDivider(
                    color = customColors.divider,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Text(
                    text = "COMMUNITY MODS",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                        color = customColors.textDisabled,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                customModActions.forEach { mod ->
                    ActionMenuItem(
                        icon = Icons.Default.Extension,
                        title = mod.name,
                        subtitle = "Wasm Mod by ${mod.author}",
                        onClick = {
                            onExecuteModAction(mod, track)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    val customColors = LocalPlasmaColors.current
    val itemTint = tint ?: if (enabled) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else customColors.textDisabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = itemTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = itemTint
                )
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                        color = customColors.textSecondary,
                        fontSize = 11.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
