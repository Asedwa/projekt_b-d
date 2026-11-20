package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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

private data class PlaylistDownloadGroup(
    val key: String,
    val name: String,
    val tasks: List<SavedDownload>
)

@Composable
fun DownloadsScreen(viewModel: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val downloads by viewModel.fullDownloads.collectAsState()
    val oldDownloads by viewModel.downloads.collectAsState()
    val quality by viewModel.downloadQuality.collectAsState()
    val mobileAllowed by viewModel.mobileDownloadsAllowed.collectAsState()
    val colors = LocalPlasmaColors.current
    var showQuality by remember { mutableStateOf(false) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val totalPending = remember(downloads) { downloads.count { it.state == "running" || it.state == "queued" } }
    val playlistGroups = remember(downloads) {
        downloads
            .filter { !it.playlistName.isNullOrBlank() }
            .groupBy { it.playlistId ?: it.playlistName!! }
            .map { (key, items) ->
                PlaylistDownloadGroup(
                    key = key,
                    name = items.firstNotNullOfOrNull { it.playlistName } ?: key,
                    tasks = items
                )
            }
            .reversed()
            .sortedByDescending { group ->
                if (group.tasks.any { it.state == "running" || it.state == "queued" }) 1 else 0
            }
    }
    val singleDownloads = remember(downloads) { downloads.filter { it.playlistName.isNullOrBlank() } }
    val singlePending = remember(singleDownloads) { singleDownloads.filter { it.state == "running" || it.state == "queued" } }
    val singleFinished = remember(singleDownloads) { singleDownloads.filterNot { it.state == "running" || it.state == "queued" }.asReversed() }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                Text("İndirmeler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "Müzik / PlasmaWave • Listeler klasör olarak kaydedilir",
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall
            )
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
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = { if (option == quality) Icon(Icons.Default.Check, contentDescription = null) },
                                        onClick = { viewModel.setDownloadQuality(option); showQuality = false }
                                    )
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
            if (totalPending > 0) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Aktif indirmeler ($totalPending)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = viewModel::cancelAllDownloads) { Text("Tümünü iptal et") }
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }

        if (playlistGroups.isNotEmpty()) {
            item {
                Text(
                    "İndirilen Listeler (${playlistGroups.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
            }
            items(playlistGroups, key = { "pl:${it.key}" }) { group ->
                val expanded = expandedGroups[group.key] == true
                PlaylistDownloadCard(
                    group = group,
                    expanded = expanded,
                    onToggleExpand = { expandedGroups[group.key] = !expanded },
                    viewModel = viewModel
                )
            }
        }

        if (singlePending.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Tekli İndirme Sırası (${singlePending.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
            }
            itemsIndexed(singlePending, key = { _, it -> it.id }) { index, task -> DownloadCard(task, index + 1, viewModel) }
        }

        if (singleFinished.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (playlistGroups.isNotEmpty()) "Tekli Şarkılar" else "Tamamlananlar ve geçmiş",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
            }
            items(singleFinished, key = { it.id }) { task -> DownloadCard(task, null, viewModel) }
        }

        if (downloads.isEmpty() && oldDownloads.isEmpty()) item {
            Text(
                "Henüz indirilmiş şarkı yok. Bir şarkının menüsünden veya bir listeden indirme başlatabilirsin.",
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
            )
        }

        if (oldDownloads.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)); Text("Eski indirmeler", fontWeight = FontWeight.Bold) }
            items(oldDownloads, key = { "old:${it.id}" }) { task ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (task.trackId.startsWith("deezer:")) "Eski Deezer önizlemesi • ${task.state}" else task.state,
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (task.state == "completed") {
                        IconButton(onClick = { viewModel.playDownloadedTask(task) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Eski indirmeyi çal")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun PlaylistDownloadCard(
    group: PlaylistDownloadGroup,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    viewModel: MainViewModel
) {
    val colors = LocalPlasmaColors.current
    val totalCount = group.tasks.size
    val completedTasks = remember(group.tasks) { group.tasks.filter { it.state == "completed" && it.uri != null } }
    val completedCount = completedTasks.size
    val activeTasks = remember(group.tasks) { group.tasks.filter { it.state == "running" || it.state == "queued" } }
    val activeCount = activeTasks.size
    val failedCount = remember(group.tasks) { group.tasks.count { it.state == "failed" } }
    val runningTask = remember(group.tasks) { group.tasks.firstOrNull { it.state == "running" } }

    val progressFraction = if (totalCount > 0) {
        val currentFraction = (runningTask?.progress ?: 0) / 100f
        ((completedCount + currentFraction) / totalCount.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val statusText = when {
        activeCount > 0 -> "$completedCount / $totalCount indirildi • $activeCount sırada"
        failedCount > 0 -> "$completedCount / $totalCount indirildi • $failedCount başarısız"
        completedCount == totalCount -> "$totalCount şarkı indirildi • Çevrimdışı hazır"
        else -> "$completedCount / $totalCount şarkı indirildi"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusText,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (completedTasks.isNotEmpty()) {
                    IconButton(onClick = { viewModel.playDownloadedPlaylist(completedTasks) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Listeyi çevrimdışı çal")
                    }
                }
                if (activeCount > 0) {
                    IconButton(onClick = { viewModel.cancelDownloadGroup(activeTasks.map { it.id }) }) {
                        Icon(Icons.Default.Cancel, contentDescription = "Liste indirmesini iptal et")
                    }
                } else {
                    IconButton(onClick = { viewModel.forgetDownloadGroup(group.tasks.map { it.id }) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Listeyi geçmişten kaldır")
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Daralt" else "Şarkıları göster"
                    )
                }
            }

            if (activeCount > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = colors.divider)
                Spacer(Modifier.height(6.dp))
                group.tasks.forEachIndexed { idx, task ->
                    val queuePos = if (task.state == "queued" || task.state == "running") idx + 1 else null
                    val completedIndex = completedTasks.indexOfFirst { it.id == task.id }
                    DownloadCard(
                        task = task,
                        queuePosition = queuePos,
                        viewModel = viewModel,
                        onPlayOverride = if (completedIndex >= 0) {
                            { viewModel.playDownloadedPlaylist(completedTasks, completedIndex) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    task: SavedDownload,
    queuePosition: Int?,
    viewModel: MainViewModel,
    onPlayOverride: (() -> Unit)? = null
) {
    val colors = LocalPlasmaColors.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${task.track.artist} • ${task.quality.label}", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (task.state) {
                        "queued" -> task.error ?: "Sırada • $queuePosition"
                        "running" -> "İndiriliyor • %${task.progress}"
                        "completed" -> "İndirildi"
                        "cancelled" -> "İptal edildi"
                        else -> "İndirilemedi"
                    }
                    Text(status, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (task.state) {
                    "completed" -> IconButton(onClick = { onPlayOverride?.invoke() ?: viewModel.playFullDownload(task) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "İndirileni çal")
                    }
                    "queued", "running" -> IconButton(onClick = { viewModel.cancelFullDownload(task.id) }) {
                        Icon(Icons.Default.Cancel, contentDescription = "İndirmeyi iptal et")
                    }
                    else -> IconButton(onClick = { viewModel.retryFullDownload(task) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Tekrar dene")
                    }
                }
                if (task.state !in listOf("queued", "running")) {
                    IconButton(onClick = { viewModel.forgetFullDownload(task.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Geçmişten kaldır; dosyayı silme")
                    }
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
