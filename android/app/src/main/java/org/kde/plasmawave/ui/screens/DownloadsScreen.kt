package org.kde.plasmawave.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.downloads.collectAsState()
    val customColors = LocalPlasmaColors.current
    val core = PlasmaWaveApp.core

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUTHORIZED DOWNLOADS",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = customColors.textSecondary
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.refreshDownloads() }) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active or saved downloads.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = customColors.textSecondary)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(downloads, key = { it.id }) { task ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = customColors.card),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.fileName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Status: ${task.state.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                    )
                                }

                                when (task.state) {
                                    "running" -> {
                                        IconButton(onClick = {
                                            core.pauseDownload(task.id)
                                            viewModel.refreshDownloads()
                                        }) {
                                            Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    "paused" -> {
                                        IconButton(onClick = {
                                            core.resumeDownload(task.id)
                                            viewModel.refreshDownloads()
                                        }) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    "failed" -> {
                                        IconButton(onClick = {
                                            core.retryDownload(task.id)
                                            viewModel.refreshDownloads()
                                        }) {
                                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", tint = customColors.warning)
                                        }
                                    }
                                    "completed" -> {
                                        IconButton(onClick = {
                                            viewModel.playDownloadedTask(task)
                                        }) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play Offline", tint = customColors.positive)
                                        }
                                    }
                                }

                                IconButton(onClick = {
                                    core.removeDownload(task.id)
                                    viewModel.refreshDownloads()
                                }) {
                                    Icon(imageVector = Icons.Default.Cancel, contentDescription = "Cancel", tint = customColors.textDisabled, modifier = Modifier.size(20.dp))
                                }
                            }

                            if (task.state == "running" || task.state == "paused") {
                                Spacer(modifier = Modifier.height(8.dp))
                                val pct = task.progressPercent?.toInt() ?: 0
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = customColors.border
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$pct%",
                                    style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                )
                            }

                            task.error?.let { err ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = customColors.negative, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.negative)
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}
