package org.kde.plasmawave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiModInfo
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongLongPressBottomSheet(
    track: FfiTrack?, sheetState: SheetState, onDismiss: () -> Unit,
    onPlayNext: (FfiTrack) -> Unit, onAddToQueue: (FfiTrack) -> Unit,
    onToggleFavorite: (FfiTrack) -> Unit, onAddToPlaylist: (FfiTrack) -> Unit,
    onDownload: (FfiTrack) -> Unit, isFavorite: Boolean, isPinned: Boolean,
    onTogglePin: (FfiTrack) -> Unit, canDownloadDeezer: Boolean,
    onMix: (FfiTrack) -> Unit, canRemove: Boolean, onRemove: (FfiTrack) -> Unit,
    sleepTimerSummary: String? = null,
    onSleepTimer: () -> Unit = {},
    customModActions: List<FfiModInfo> = emptyList(),
    onExecuteModAction: (FfiModInfo, FfiTrack) -> Unit = { _, _ -> }
) {
    if (track == null) return
    val colors = LocalPlasmaColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(
                    url = track.artworkUrl,
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(10.dp),
                    fallbackTitle = track.title,
                    fallbackArtist = track.artist
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.album, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (isFavorite) "Beğenildi" else "Beğen", isFavorite) { onToggleFavorite(track); onDismiss() }
                QuickAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Listeye ekle") { onAddToPlaylist(track); onDismiss() }
                QuickAction(Icons.Default.GraphicEq, "Şarkı miksi", enabled = track.provider == "deezer" && canDownloadDeezer) { onMix(track); onDismiss() }
                QuickAction(Icons.Default.CloudDownload, "İndir", enabled = track.provider == "deezer" && canDownloadDeezer) { onDownload(track); onDismiss() }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 20.dp))
            MenuAction(Icons.Default.SkipNext, "Sıradaki şarkı olarak çal") { onPlayNext(track); onDismiss() }
            MenuAction(Icons.AutoMirrored.Filled.QueueMusic, "Çalma sırasına ekle") { onAddToQueue(track); onDismiss() }
            MenuAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Çalma listesine ekle") { onAddToPlaylist(track); onDismiss() }
            if (canRemove) MenuAction(Icons.Default.RemoveCircleOutline, "Bu listeden çıkar") { onRemove(track); onDismiss() }
            MenuAction(Icons.Default.PushPin, if (isPinned) "Ana sayfadan kaldır" else "Ana sayfaya sabitle") { onTogglePin(track); onDismiss() }
            if (track.provider == "deezer" && canDownloadDeezer) {
                MenuAction(Icons.Default.GraphicEq, "Bu şarkının miksini başlat") { onMix(track); onDismiss() }
                MenuAction(Icons.Default.CloudDownload, "Şarkıyı indir") { onDownload(track); onDismiss() }
            }
            MenuAction(
                icon = Icons.Default.Bedtime,
                label = "Uyku zamanı modu",
                trailingText = sleepTimerSummary,
                highlight = sleepTimerSummary != null
            ) { onSleepTimer(); onDismiss() }
            customModActions.forEach { mod ->
                MenuAction(Icons.Default.Extension, mod.name) { onExecuteModAction(mod, track); onDismiss() }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalPlasmaColors.current
    val tint = if (!enabled) {
        colors.textDisabled
    } else if (active && colors.legacyUiMode) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp)) {
        if (colors.legacyUiMode) {
            Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            }
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    trailingText: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val tint = if (highlight && colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelLarge,
                color = if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
