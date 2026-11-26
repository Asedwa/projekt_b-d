package org.kde.plasmawave.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiPlaylistPickerItem
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(
    track: FfiTrack,
    items: List<FfiPlaylistPickerItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    pendingPlaylistIds: Set<String>,
    canCreateDeezer: Boolean,
    onDismiss: () -> Unit,
    onToggle: (FfiPlaylistPickerItem) -> Unit,
    onCreate: (String, Boolean) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var createOnDeezer by remember(canCreateDeezer, track.provider) {
        mutableStateOf(canCreateDeezer && track.provider == "deezer")
    }
    val colors = LocalPlasmaColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val memberCount = remember(items) { items.count { it.isMember } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: Track preview + Done button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    url = track.artworkUrl,
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    fallbackTitle = track.title,
                    fallbackArtist = track.artist
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Listeye ekle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${track.title} • ${track.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (memberCount > 0) {
                        Text(
                            text = "$memberCount listede mevcut",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Tamam", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Search + New Playlist toggle
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Listelerde ara") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Temizle")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            if (!showCreate) {
                Surface(
                    onClick = { showCreate = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Yeni liste oluştur ve ekle",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showCreate) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Yeni liste adı") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !createOnDeezer,
                                    onClick = { createOnDeezer = false },
                                    label = { Text("Bu cihaz") }
                                )
                                if (canCreateDeezer) {
                                    FilterChip(
                                        selected = createOnDeezer,
                                        onClick = { createOnDeezer = true },
                                        label = { Text("Deezer") }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    showCreate = false
                                    name = ""
                                }) {
                                    Text("İptal")
                                }
                                Button(
                                    onClick = {
                                        onCreate(name.trim(), createOnDeezer)
                                        name = ""
                                        showCreate = false
                                    },
                                    enabled = name.isNotBlank()
                                ) {
                                    Text("Oluştur")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isBlank()) "Henüz çalma listen yok." else "Eşleşen liste bulunamadı.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items, key = { it.playlist.id }) { item ->
                        val playlist = item.playlist
                        val isPending = pendingPlaylistIds.contains(playlist.id)
                        val rowShape = RoundedCornerShape(12.dp)
                        val rowBg = if (item.isMember) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(rowBg)
                                .clickable(enabled = item.isCompatible && !isPending) {
                                    onToggle(item)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(
                                url = playlist.artworkUrl,
                                modifier = Modifier.size(48.dp),
                                fallback = Icons.AutoMirrored.Filled.PlaylistPlay,
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (item.isCompatible) MaterialTheme.colorScheme.onSurface else colors.textDisabled
                                )
                                val sourceLabel = if (playlist.isLocal) "Bu cihaz" else if (item.isCompatible) "Deezer" else "Yalnızca Deezer şarkıları"
                                val countSuffix = if (playlist.trackCount > 0u) " • ${playlist.trackCount} şarkı" else ""
                                Text(
                                    text = sourceLabel + countSuffix,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            when {
                                isPending -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                item.isMember -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Listede",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Listede",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                item.isCompatible -> {
                                    Icon(
                                        Icons.Default.AddCircleOutline,
                                        contentDescription = "Listeye ekle",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(24.dp)
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
