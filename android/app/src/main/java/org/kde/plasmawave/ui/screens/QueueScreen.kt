package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.player.PlaybackManager
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.components.formatDuration
import org.kde.plasmawave.ui.components.ArtworkImage

@Composable
fun QueueScreen(
    playbackManager: PlaybackManager,
    modifier: Modifier = Modifier
) {
    val playerState by playbackManager.uiState.collectAsState()
    val entries = playerState.queueSnapshot?.entries ?: emptyList()
    val currentIndex = playerState.queueSnapshot?.currentIndex?.toInt()
    val customColors = LocalPlasmaColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Çalma sırası (${entries.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = customColors.textSecondary
                )
            )
            TextButton(onClick = playbackManager::clearQueue, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Temizle")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Çalma sırası boş.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(entries, key = { _, e -> e.entryId.toLong() }) { index, entry ->
                    val isCurrent = index == currentIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) customColors.card else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { playbackManager.jumpToQueueIndex(entry.entryId) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(
                            url = entry.track.artworkUrl,
                            modifier = Modifier.size(40.dp),
                            fallback = if (isCurrent) Icons.Default.Equalizer else Icons.Default.MusicNote,
                            background = if (isCurrent) MaterialTheme.colorScheme.primary else customColors.card,
                            tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else customColors.textSecondary
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.track.title,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${entry.track.artist} • ${formatDuration(entry.track.durationMs)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { playbackManager.removeQueueEntry(entry.entryId) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = customColors.textDisabled,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}
