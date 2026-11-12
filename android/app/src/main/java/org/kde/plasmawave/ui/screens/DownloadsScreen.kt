package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun DownloadsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val downloads by viewModel.fullDownloads.collectAsState()
    val oldDownloads by viewModel.downloads.collectAsState()
    val colors = LocalPlasmaColors.current
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("İndirilenler", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))
        Text("Müzik / PlasmaWave", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        if (downloads.isEmpty() && oldDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("Henüz indirilmiş şarkı yok.", color = colors.textSecondary)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(downloads, key = { it.id }) { task ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${task.track.artist} • ${task.quality.label}", color = colors.textSecondary,
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val status = when (task.state) {
                                        "queued" -> "Sırada • Ağ bağlantısı bekleniyor olabilir"
                                        "running" -> "İndiriliyor • %${task.progress}"
                                        "completed" -> "İndirildi"
                                        "cancelled" -> "İptal edildi"
                                        else -> "İndirilemedi"
                                    }
                                    Text(status, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                                when (task.state) {
                                    "completed" -> IconButton(onClick = { viewModel.playFullDownload(task) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "İndirileni çal")
                                    }
                                    "queued", "running" -> IconButton(onClick = { viewModel.cancelFullDownload(task.id) }) {
                                        Icon(Icons.Default.Cancel, contentDescription = "İndirmeyi iptal et")
                                    }
                                    else -> IconButton(onClick = { viewModel.retryFullDownload(task) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Tekrar dene")
                                    }
                                }
                                if (task.state !in listOf("queued", "running")) IconButton(onClick = { viewModel.forgetFullDownload(task.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Listeden kaldır; dosyayı silme")
                                }
                            }
                            if (task.state == "running") {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            task.error?.let { Text(it, color = colors.negative, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (oldDownloads.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(16.dp)); Text("Eski indirmeler", fontWeight = FontWeight.Bold) }
                    items(oldDownloads, key = { "old:${it.id}" }) { task ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (task.trackId.startsWith("deezer:")) "Eski Deezer önizlemesi • ${task.state}" else task.state,
                                    color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            if (task.state == "completed") IconButton(onClick = { viewModel.playDownloadedTask(task) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Eski indirmeyi çal")
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}
