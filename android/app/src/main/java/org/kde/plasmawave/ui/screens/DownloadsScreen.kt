package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.download.DownloadQuality
import org.kde.plasmawave.download.SavedDownload
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun DownloadsScreen(viewModel: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val downloads by viewModel.fullDownloads.collectAsState()
    val oldDownloads by viewModel.downloads.collectAsState()
    val quality by viewModel.downloadQuality.collectAsState()
    val mobileAllowed by viewModel.mobileDownloadsAllowed.collectAsState()
    val colors = LocalPlasmaColors.current
    var showQuality by remember { mutableStateOf(false) }
    val pending = remember(downloads) { downloads.filter { it.state == "running" || it.state == "queued" } }
    val finished = remember(downloads) { downloads.filterNot { it.state == "running" || it.state == "queued" }.asReversed() }
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                Text("İndirmeler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("Müzik / PlasmaWave", color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Varsayılan indirme kalitesi", fontWeight = FontWeight.SemiBold)
                            Text("Yeni indirmelerde kullanılır", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            TextButton(onClick = { showQuality = true }) { Text(quality.label) }
                            DropdownMenu(expanded = showQuality, onDismissRequest = { showQuality = false }) {
                                DownloadQuality.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, leadingIcon = { if (option == quality) Icon(Icons.Default.Check, contentDescription = null) },
                                        onClick = { viewModel.setDownloadQuality(option); showQuality = false })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text("Mobil veriyle indir", fontWeight = FontWeight.SemiBold)
                            Text("Kapalıyken Wi-Fi beklenir", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = mobileAllowed, onCheckedChange = viewModel::setMobileDownloadsAllowed)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (pending.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("İndirme sırası (${pending.size})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = viewModel::cancelAllDownloads) { Text("Tümünü iptal et") }
                }
            }
            itemsIndexed(pending, key = { _, it -> it.id }) { index, task -> DownloadCard(task, index + 1, viewModel) }
        }
        if (finished.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)); Text("Tamamlananlar ve geçmiş", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)) }
            items(finished, key = { it.id }) { task -> DownloadCard(task, null, viewModel) }
        }
        if (downloads.isEmpty() && oldDownloads.isEmpty()) item {
            Text("Henüz indirilmiş şarkı yok. Bir şarkının menüsünden veya bir listeden indirme başlatabilirsin.",
                color = colors.textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
        }
        if (oldDownloads.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)); Text("Eski indirmeler", fontWeight = FontWeight.Bold) }
            items(oldDownloads, key = { "old:${it.id}" }) { task ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (task.trackId.startsWith("deezer:")) "Eski Deezer önizlemesi • ${task.state}" else task.state,
                            color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (task.state == "completed") IconButton(onClick = { viewModel.playDownloadedTask(task) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Eski indirmeyi çal") }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun DownloadCard(task: SavedDownload, queuePosition: Int?, viewModel: MainViewModel) {
    val colors = LocalPlasmaColors.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${task.track.artist} • ${task.quality.label}", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    val status = when (task.state) {
                        "queued" -> task.error ?: "Sırada • $queuePosition"
                        "running" -> "İndiriliyor • %${task.progress}"
                        "completed" -> "İndirildi"
                        "cancelled" -> "İptal edildi"
                        else -> "İndirilemedi"
                    }
                    Text(status, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                when (task.state) {
                    "completed" -> IconButton(onClick = { viewModel.playFullDownload(task) }) { Icon(Icons.Default.PlayArrow, contentDescription = "İndirileni çal") }
                    "queued", "running" -> IconButton(onClick = { viewModel.cancelFullDownload(task.id) }) { Icon(Icons.Default.Cancel, contentDescription = "İndirmeyi iptal et") }
                    else -> IconButton(onClick = { viewModel.retryFullDownload(task) }) { Icon(Icons.Default.Refresh, contentDescription = "Tekrar dene") }
                }
                if (task.state !in listOf("queued", "running")) IconButton(onClick = { viewModel.forgetFullDownload(task.id) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Geçmişten kaldır; dosyayı silme")
                }
            }
            if (task.state == "running") {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (task.state == "failed") task.error?.let { Text(it, color = colors.negative, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
