package org.kde.plasmawave.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun ListeningStatsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val stats by viewModel.trackListeningStats.collectAsState()
    val totalListeningMs by viewModel.totalListeningTimeMs.collectAsState()
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val json = viewModel.buildUserDataExportJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Verileriniz başarıyla kaydedildi!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Kayıt hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val totalPlays = remember(stats) { stats.sumOf { it.playCount } }
    val topArtists = remember(stats) {
        stats.groupBy { it.track.artist }
            .map { (artist, list) ->
                Triple(
                    artist,
                    list.sumOf { it.playCount },
                    list.firstNotNullOfOrNull { it.track.artworkUrl }
                )
            }
            .sortedByDescending { it.second }
            .take(8)
    }

    val filteredStats = remember(stats, query) {
        val q = query.trim()
        if (q.isBlank()) stats
        else stats.filter {
            it.track.title.contains(q, ignoreCase = true) ||
                it.track.artist.contains(q, ignoreCase = true) ||
                it.track.album.contains(q, ignoreCase = true)
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Verileri Sıfırla?") },
            text = { Text("Toplanan tüm şarkı dinleme sayaçları, toplam dinleme süresi ve geçmiş kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllCollectedUserData()
                        showClearConfirm = false
                        Toast.makeText(context, "Tüm dinleme verileri sıfırlandı.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Sıfırla", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Dinleme İstatistikleri ve Veriler",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Hangi şarkıyı kaç kere dinlediğiniz ve toplam süreniz",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Summary Cards Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Toplam Süre",
                                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                viewModel.formatDurationTr(totalListeningMs),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = primary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.PlayCircleFilled,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Toplam Dinlenme",
                                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$totalPlays kez • ${stats.size} şarkı",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (topArtists.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "EN ÇOK DİNLENEN SANATÇILAR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(topArtists, key = { it.first }) { (artist, count, artUrl) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.card)
                                    .border(1.dp, colors.border, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                ArtworkImage(
                                    url = artUrl,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape),
                                    fallback = Icons.Default.Person,
                                    shape = CircleShape
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    artist,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${count}x",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Şarkı veya sanatçı ara...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "ŞARKI DİNLENME SAYAÇLARI (${filteredStats.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )

                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filteredStats.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Henüz kayıtlı şarkı istatistiği bulunmuyor.",
                                    color = colors.textSecondary
                                )
                            }
                        }
                    } else {
                        itemsIndexed(filteredStats, key = { _, s -> s.track.id }) { idx, item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.card)
                                    .clickable {
                                        viewModel.playbackManager.playTrackList(
                                            filteredStats.map { it.track },
                                            idx
                                        )
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(
                                    "#${idx + 1}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (idx < 3) primary else colors.textSecondary
                                    ),
                                    modifier = Modifier.width(28.dp)
                                )
                                ArtworkImage(
                                    url = item.track.artworkUrl,
                                    modifier = Modifier.size(44.dp),
                                    fallback = Icons.Default.MusicNote,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.track.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.track.artist} • Toplam: ${viewModel.formatDurationTr(item.totalListenedMs)}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(primary.copy(alpha = 0.16f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "${item.playCount} kez",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = primary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Footer Buttons: Export & Clear
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sıfırla")
                    }

                    Button(
                        onClick = {
                            val savedPath = viewModel.exportUserDataToDownloads()
                            if (savedPath != null) {
                                Toast.makeText(
                                    context,
                                    "Verileriniz İndirilenler klasörüne kaydedildi:\n$savedPath",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                createDocumentLauncher.launch("projekt_b-d_verilerim_$stamp.json")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Verilerimi İndir (JSON)")
                    }
                }
            }
        }
    }
}
