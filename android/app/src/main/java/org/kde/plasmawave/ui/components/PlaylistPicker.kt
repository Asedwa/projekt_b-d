package org.kde.plasmawave.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiPlaylist
import uniffi.plasmawave_core.FfiTrack

@Composable
fun PlaylistPicker(track: FfiTrack, playlists: List<FfiPlaylist>, canCreateDeezer: Boolean, onDismiss: () -> Unit,
                   onSelect: (FfiPlaylist) -> Unit, onCreate: (String, Boolean) -> Unit) {
    var query by remember(track.id) { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var createOnDeezer by remember(canCreateDeezer) { mutableStateOf(canCreateDeezer) }
    val colors = LocalPlasmaColors.current
    val visible = remember(playlists, query) { playlists.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Listeye ekle") },
        text = {
            Column {
                Text(track.title, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, label = { Text("Listelerde ara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (showCreate) {
                    OutlinedTextField(name, { name = it }, label = { Text("Yeni liste adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !createOnDeezer, onClick = { createOnDeezer = false }, label = { Text("Bu cihaz") })
                        if (canCreateDeezer) FilterChip(selected = createOnDeezer, onClick = { createOnDeezer = true }, label = { Text("Deezer") })
                    }
                    TextButton(onClick = { onCreate(name.trim(), createOnDeezer); name = ""; showCreate = false }, enabled = name.isNotBlank()) { Text("Listeyi oluştur") }
                } else TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = null); Text(" Yeni liste oluştur")
                }
                if (visible.isEmpty()) Text("Henüz eşleşen liste yok.", color = colors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                    items(visible, key = { it.id }) { playlist ->
                        val enabled = playlist.isLocal || track.provider == "deezer"
                        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(playlist) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(playlist.artworkUrl, modifier = Modifier.size(48.dp), fallback = Icons.AutoMirrored.Filled.PlaylistPlay, shape = RoundedCornerShape(6.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textDisabled)
                                Text(if (playlist.isLocal) "Bu cihaz" else if (enabled) "Deezer" else "Yalnızca Deezer şarkıları",
                                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } })
}
