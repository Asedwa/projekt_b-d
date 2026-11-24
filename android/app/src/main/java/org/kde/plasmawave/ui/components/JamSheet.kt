package org.kde.plasmawave.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kde.plasmawave.jam.JamMode
import org.kde.plasmawave.jam.JamUiState
import org.kde.plasmawave.theme.LocalPlasmaColors
import uniffi.plasmawave_core.FfiTrack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JamSheet(
    jamState: JamUiState,
    localQueueTracks: List<FfiTrack>,
    onDismiss: () -> Unit,
    onStartHost: (String) -> Unit,
    onJoinByCode: (code: String, guestName: String) -> Unit,
    onJoinJam: (hostAddr: String, code: String, guestName: String) -> Unit,
    onStopJam: () -> Unit,
    onToggleGuestControls: (Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onAddTrack: (FfiTrack) -> Unit,
    onSearchTracks: suspend (String) -> List<FfiTrack>
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var hostNameInput by remember { mutableStateOf(jamState.hostName) }
    var guestNameInput by remember { mutableStateOf(jamState.guestName) }
    var quickCodeInput by remember { mutableStateOf("") }
    var manualAddrInput by remember { mutableStateOf("") }
    var manualCodeInput by remember { mutableStateOf("") }
    var showManualLan by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FfiTrack>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(jamState.mode) {
        if (jamState.mode == JamMode.NONE) {
            onStartDiscovery()
        } else {
            onStopDiscovery()
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopDiscovery() }
    }

    LaunchedEffect(jamState.statusMessage, jamState.errorMessage) {
        jamState.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "PlasmaWave Jam",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (jamState.mode) {
                                JamMode.NONE -> "İster aynı Wi-Fi'da ister uzaktan arkadaşlarınla dinle"
                                JamMode.HOST -> if (jamState.isRelayConnected) "Ev Sahibi • Her Ağdan Açık" else "Ev Sahibi • Yerel Ağ"
                                JamMode.GUEST -> "Misafir • Senkronize Dinleme"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                }
            }

            if (jamState.mode == JamMode.NONE) {
                // Quick 1-step Code Join (Works across any Wi-Fi / Mobile Data)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, primary.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Jam Kodu ile Katıl",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Farklı Wi-Fi veya mobil veride olsan bile 6 haneli kodu yazıp hemen katıl.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = guestNameInput,
                                onValueChange = { guestNameInput = it },
                                label = { Text("Adın") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = quickCodeInput,
                                    onValueChange = { quickCodeInput = it.uppercase().take(8) },
                                    label = { Text("Jam Kodu") },
                                    placeholder = { Text("A7K2M9") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onJoinByCode(quickCodeInput, guestNameInput) },
                                    enabled = quickCodeInput.trim().length >= 4,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Text("Katıl", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Host card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Jam Başlat (Ev Sahibi)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Arkadaşların paylaştığın kodla veya davet linkiyle her yerden katılabilir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = hostNameInput,
                                onValueChange = { hostNameInput = it },
                                label = { Text("Görünen Adın") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onStartHost(hostNameInput) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Jam Başlat", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Nearby Wi-Fi Jams
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Yakındaki Jam'ler (Wi-Fi)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(onClick = onStartDiscovery) {
                                    if (jamState.isScanning) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "Yenile")
                                    }
                                }
                            }

                            if (jamState.discoveredJams.isEmpty()) {
                                Text(
                                    if (jamState.isScanning) "Aynı Wi-Fi ağında Jam aranıyor..."
                                    else "Yakında aktif Wi-Fi Jam bulunamadı.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    jamState.discoveredJams.forEach { jam ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, colors.border)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Jam #${jam.jamCode}",
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    Text(
                                                        jam.address,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.textSecondary
                                                    )
                                                }
                                                Button(
                                                    onClick = { onJoinJam(jam.address, jam.jamCode, guestNameInput) },
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Text("Katıl")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showManualLan = !showManualLan },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (showManualLan) "IP Girişini Gizle" else "Yerel Ağ IP ile Bağlan")
                            }

                            if (showManualLan) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = manualAddrInput,
                                        onValueChange = { manualAddrInput = it },
                                        label = { Text("IP:Port") },
                                        placeholder = { Text("192.168.1.20:8765") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.4f)
                                    )
                                    OutlinedTextField(
                                        value = manualCodeInput,
                                        onValueChange = { manualCodeInput = it.uppercase().take(8) },
                                        label = { Text("Kod") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.8f)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { onJoinJam(manualAddrInput, manualCodeInput, guestNameInput) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("IP ile Bağlan", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                // Active Jam Status Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, primary.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "Jam Kodu: ${jamState.jamCode}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = primary
                                    )
                                    Text(
                                        "Adres: ${jamState.hostAddress}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                                Button(
                                    onClick = onStopJam,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFEF4444).copy(alpha = 0.18f),
                                        contentColor = Color(0xFFEF4444)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(if (jamState.mode == JamMode.HOST) "Bitir" else "Ayrıl", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (jamState.mode == JamMode.HOST) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("Jam Kodu", jamState.jamCode))
                                            Toast.makeText(context, "Jam kodu kopyalandı: ${jamState.jamCode}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Kodu Kopyala")
                                    }
                                    Button(
                                        onClick = {
                                            val shareText = "PlasmaWave Jam'e katıl! 🎵\nJam Kodu: ${jamState.jamCode}\nBağlantı: plasmawave://jam/${jamState.jamCode}"
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Jam Davetini Paylaş"))
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Davet Paylaş")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Aynı Wi-Fi tarayıcı: http://${jamState.hostAddress}/?code=${jamState.jamCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Misafirler müziği kontrol edebilsin",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = jamState.allowGuestControls,
                                        onCheckedChange = onToggleGuestControls
                                    )
                                }
                            }

                            if (jamState.participants.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Katılımcılar (${jamState.participants.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    jamState.participants.forEach { p ->
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = primary.copy(alpha = 0.14f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = primary, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(p, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick search to add songs to Jam
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Jam Sırasına Şarkı Ekle",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Şarkı veya sanatçı ara...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSearching = true
                                            searchResults = onSearchTracks(searchQuery)
                                            isSearching = false
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isSearching) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = "Ara")
                                    }
                                }
                            }

                            if (searchResults.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    searchResults.take(5).forEach { track ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ArtworkImage(
                                                url = track.artworkUrl,
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                            }
                                            IconButton(onClick = {
                                                onAddTrack(track)
                                                Toast.makeText(context, "'${track.title}' Jam'e eklendi", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Default.Add, contentDescription = "Jam'e ekle", tint = primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Shared Queue preview
                val queueToShow = if (jamState.mode == JamMode.HOST) localQueueTracks else jamState.remoteQueue
                if (queueToShow.isNotEmpty()) {
                    item {
                        Text(
                            "Ortak Jam Sırası (${queueToShow.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(queueToShow.take(15), key = { "${it.id}_${it.title}" }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(url = track.artworkUrl, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = colors.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
