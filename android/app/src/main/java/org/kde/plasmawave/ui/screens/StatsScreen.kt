package org.kde.plasmawave.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.ArtworkImage
import uniffi.plasmawave_core.FfiDailyListeningPoint
import uniffi.plasmawave_core.FfiHourlyListeningBucket
import uniffi.plasmawave_core.FfiListeningAnalytics
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StatsPeriod(val id: String, val label: String) {
    TODAY("today", "Bugün"),
    WEEK("7d", "7 Gün"),
    MONTH("30d", "30 Gün"),
    YEAR("year", "Bu Yıl"),
    ALL("all", "Tüm Zamanlar")
}

private enum class StatsSort(val id: String, val label: String) {
    PLAYS("plays", "Dinlenme"),
    DURATION("duration", "Süre"),
    RECENT("recent", "Son Dinlenen")
}

private enum class StatsTab(val label: String) {
    OVERVIEW("Özet"),
    TRACKS("Şarkılar"),
    ARTISTS("Sanatçılar"),
    ALBUMS("Albümler"),
    HISTORY("Geçmiş"),
    FORGOTTEN("Unutulanlar")
}

@Composable
fun StatsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recentTracks by viewModel.recentTracks.collectAsState()
    val statsVersion by viewModel.trackListeningStats.collectAsState()
    val privateSession by viewModel.privateSessionStatus.collectAsState()

    var period by rememberSaveable { mutableStateOf(StatsPeriod.ALL) }
    var sort by rememberSaveable { mutableStateOf(StatsSort.PLAYS) }
    var tab by rememberSaveable { mutableStateOf(StatsTab.OVERVIEW) }
    var query by rememberSaveable { mutableStateOf("") }
    var refreshNonce by remember { mutableIntStateOf(0) }

    val analytics by produceState<FfiListeningAnalytics?>(
        initialValue = null,
        period, sort, statsVersion.size, refreshNonce
    ) {
        value = viewModel.loadListeningAnalytics(period.id, sort.id)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Dinleme İstatistikleri",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (privateSession.active) "Gizli Oturum Aktif (kayıt duraklatıldı)"
                    else "Rust Analitik Motoru • ${period.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (privateSession.active) Color(0xFFF59E0B) else colors.textSecondary
                )
            }
            IconButton(onClick = { refreshNonce++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Yenile")
            }
        }

        // Period Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            items(StatsPeriod.entries) { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) }
                )
            }
        }

        // Section Tabs
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        ) {
            items(StatsTab.entries) { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(t.label) }
                )
            }
        }

        val data = analytics
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        when (tab) {
            StatsTab.OVERVIEW -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    // Wrapped / Shareable Summary Card
                    item {
                        WrappedSummaryCard(
                            analytics = data,
                            periodLabel = period.label,
                            formatDuration = { viewModel.formatDurationTr(it.toLong()) },
                            onSavePng = {
                                scope.launch {
                                    val saved = saveWrappedCardPng(
                                        context = context,
                                        analytics = data,
                                        periodLabel = period.label,
                                        formatDuration = { viewModel.formatDurationTr(it.toLong()) }
                                    )
                                    Toast.makeText(
                                        context,
                                        if (saved != null) "Özet kartı kaydedildi: $saved" else "Kart kaydedilemedi",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }

                    // 4 Key Metric Cards (2x2)
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            StatKpiCard(
                                icon = Icons.Default.AccessTime,
                                tint = primary,
                                title = "Toplam Süre",
                                value = viewModel.formatDurationTr(data.totalListenedMs.toLong()),
                                subtitle = "Günlük ort: ${viewModel.formatDurationTr(data.dailyAverageMs.toLong())}",
                                modifier = Modifier.weight(1f)
                            )
                            StatKpiCard(
                                icon = Icons.Default.LocalFireDepartment,
                                tint = Color(0xFFF59E0B),
                                title = "Dinleme Serisi",
                                value = "${data.currentStreakDays} gün",
                                subtitle = "Rekor: ${data.longestStreakDays} gün",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            StatKpiCard(
                                icon = Icons.Default.PlayCircleFilled,
                                tint = Color(0xFF10B981),
                                title = "Dinlenme",
                                value = "${data.totalPlays} kez",
                                subtitle = "${data.uniqueTracks} şarkı • ${data.uniqueArtists} sanatçı",
                                modifier = Modifier.weight(1f)
                            )
                            StatKpiCard(
                                icon = Icons.Default.Verified,
                                tint = Color(0xFF8B5CF6),
                                title = "Tamamlama",
                                value = "%${data.completionRatePct}",
                                subtitle = "${data.completedPlays} tam • ${data.skippedPlays} atlanan",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Listening Persona Card
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Dinleme Profilin: ${data.listeningPersona}", fontWeight = FontWeight.Bold)
                                    Text(
                                        "En aktif saat: %02d:00 • %d benzersiz albüm".format(data.peakHour.toInt(), data.uniqueAlbums.toInt()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                    data.peakDayKey?.let { peakDay ->
                                        Text(
                                            "En yoğun gün: $peakDay (${viewModel.formatDurationTr(data.peakDayMs.toLong())})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Daily Activity Bar Chart (Canvas)
                    item {
                        DailyActivityChartCard(
                            points = data.dailyPoints,
                            primary = primary
                        )
                    }

                    // 24-Hour Distribution Chart (Canvas)
                    item {
                        HourlyDistributionChartCard(
                            buckets = data.hourlyBuckets,
                            primary = primary
                        )
                    }
                }
            }

            StatsTab.TRACKS -> {
                val filtered = remember(data.topTracks, query) {
                    val q = query.trim()
                    if (q.isEmpty()) data.topTracks
                    else data.topTracks.filter {
                        it.track.title.contains(q, true) ||
                            it.track.artist.contains(q, true) ||
                            it.track.album.contains(q, true)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StatsSort.entries.forEach { s ->
                            FilterChip(
                                selected = sort == s,
                                onClick = { sort = s },
                                label = { Text(s.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Şarkı, sanatçı veya albüm ara...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                    if (filtered.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val top50 = filtered.take(50).map { it.track }
                                    viewModel.playbackManager.playTrackList(top50, 0)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Top ${minOf(50, filtered.size)} Çal")
                            }
                            OutlinedButton(
                                onClick = {
                                    val top50 = filtered.take(50).map { it.track }
                                    viewModel.createPlaylistFromStats("En Çok Dinlediklerim (${period.label})", top50)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Listeye Kaydet")
                            }
                        }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, it -> it.track.id }) { idx, stat ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = colors.card),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.playbackManager.playTrackList(filtered.map { it.track }, idx)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "#${idx + 1}",
                                        fontWeight = FontWeight.Bold,
                                        color = primary,
                                        modifier = Modifier.width(34.dp)
                                    )
                                    ArtworkImage(
                                        url = stat.track.artworkUrl,
                                        modifier = Modifier.size(44.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        fallbackTitle = stat.track.title,
                                        fallbackArtist = stat.track.artist
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(stat.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${stat.track.artist} • ${stat.playCount} kez • ${viewModel.formatDurationTr(stat.totalListenedMs.toLong())}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.deleteSingleTrackStat(stat.track.id)
                                        refreshNonce++
                                    }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Sayacı sil", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StatsTab.ARTISTS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(data.topArtists, key = { _, it -> it.artist }) { idx, artist ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = primary, modifier = Modifier.width(34.dp))
                                ArtworkImage(
                                    url = artist.artworkUrl,
                                    modifier = Modifier.size(46.dp),
                                    shape = CircleShape,
                                    fallbackTitle = artist.artist
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(artist.artist, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${artist.playCount} dinlenme • ${viewModel.formatDurationTr(artist.totalListenedMs.toLong())} • ${artist.uniqueTracks} şarkı",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            StatsTab.ALBUMS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(data.topAlbums, key = { _, it -> "${it.album}::${it.artist}" }) { idx, album ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = primary, modifier = Modifier.width(34.dp))
                                ArtworkImage(
                                    url = album.artworkUrl,
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    fallbackTitle = album.album,
                                    fallbackArtist = album.artist
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(album.album, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${album.artist} • ${album.playCount} kez • ${viewModel.formatDurationTr(album.totalListenedMs.toLong())}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            StatsTab.HISTORY -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(recentTracks, key = { i, t -> "${t.id}_$i" }) { idx, track ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.playbackManager.playTrackList(recentTracks, idx) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(
                                    url = track.artworkUrl,
                                    modifier = Modifier.size(44.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    fallbackTitle = track.title,
                                    fallbackArtist = track.artist
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${track.artist} • ${track.album}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            StatsTab.FORGOTTEN -> {
                val forgotten = data.forgottenFavorites
                if (forgotten.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Henüz unutulan favori yok (3+ kez dinlenip 21+ gündür açılmayan şarkılar burada görünür).",
                            color = colors.textSecondary,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { viewModel.playbackManager.playTrackList(forgotten.map { it.track }, 0) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unutulan Favorileri Çal (${forgotten.size})")
                        }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            itemsIndexed(forgotten, key = { _, s -> s.track.id }) { idx, stat ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = colors.card),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.playbackManager.playTrackList(forgotten.map { it.track }, idx) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkImage(
                                            url = stat.track.artworkUrl,
                                            modifier = Modifier.size(44.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            fallbackTitle = stat.track.title,
                                            fallbackArtist = stat.track.artist
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(stat.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${stat.track.artist} • ${stat.playCount} kez dinlendi",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textSecondary
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
    }
}

@Composable
private fun WrappedSummaryCard(
    analytics: FfiListeningAnalytics,
    periodLabel: String,
    formatDuration: (ULong) -> String,
    onSavePng: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        listOf(primary.copy(alpha = 0.28f), Color(0xFF1E293B), Color(0xFF0F172A))
    )
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MÜZİK ÖZETİN • ${periodLabel.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = onSavePng,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PNG Kaydet", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                formatDuration(analytics.totalListenedMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                "${analytics.totalPlays} dinlenme • ${analytics.listeningPersona} • ${analytics.currentStreakDays} gün seri",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.80f)
            )
            analytics.topArtistName?.let { artist ->
                Text(
                    "#1 Sanatçı: $artist (${analytics.topArtistPlays} kez)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            analytics.topTrackTitle?.let { title ->
                Text(
                    "#1 Şarkı: $title (${analytics.topTrackPlays} kez)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            analytics.topAlbumName?.let { album ->
                Text(
                    "#1 Albüm: $album (${analytics.topAlbumPlays} kez)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatKpiCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DailyActivityChartCard(
    points: List<FfiDailyListeningPoint>,
    primary: Color
) {
    val colors = LocalPlasmaColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GÜNLÜK AKTİVİTE (SON ${points.size} GÜN)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.textSecondary)
            val maxVal = remember(points) { points.maxOfOrNull { it.listenedMs.toLong() }?.coerceAtLeast(1L) ?: 1L }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (points.isEmpty()) return@Canvas
                val gap = 4.dp.toPx()
                val barW = ((size.width - gap * (points.size - 1)) / points.size).coerceAtLeast(4f)
                points.forEachIndexed { i, pt ->
                    val frac = (pt.listenedMs.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                    val h = (size.height * frac).coerceAtLeast(4f)
                    val x = i * (barW + gap)
                    drawRoundRect(
                        color = if (pt.listenedMs > 0uL) primary else primary.copy(alpha = 0.16f),
                        topLeft = Offset(x, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }
            if (points.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(points.first().dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    Text("En yüksek: ${(maxVal / 60_000L)} dk", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    Text(points.last().dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun HourlyDistributionChartCard(
    buckets: List<FfiHourlyListeningBucket>,
    primary: Color
) {
    val colors = LocalPlasmaColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SAATLİK DİNLEME DAĞILIMI (00:00 – 23:00)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.textSecondary)
            val maxVal = remember(buckets) {
                buckets.maxOfOrNull { maxOf(it.listenedMs.toLong(), it.playCount.toLong() * 60_000L) }?.coerceAtLeast(1L) ?: 1L
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                if (buckets.isEmpty()) return@Canvas
                val gap = 2.dp.toPx()
                val barW = ((size.width - gap * (buckets.size - 1)) / buckets.size).coerceAtLeast(3f)
                buckets.forEachIndexed { i, b ->
                    val w = maxOf(b.listenedMs.toLong(), b.playCount.toLong() * 60_000L)
                    val frac = (w.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                    val h = (size.height * frac).coerceAtLeast(3f)
                    val x = i * (barW + gap)
                    drawRoundRect(
                        color = if (w > 0L) Color(0xFF10B981) else primary.copy(alpha = 0.14f),
                        topLeft = Offset(x, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("00:00", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Text("06:00", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Text("12:00", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Text("18:00", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Text("23:00", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

private suspend fun saveWrappedCardPng(
    context: Context,
    analytics: FfiListeningAnalytics,
    periodLabel: String,
    formatDuration: (ULong) -> String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#141923") }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#1E293B") }
        c.drawRoundRect(RectF(64f, 80f, w - 64f, h - 80f), 42f, 42f, cardPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#3DAEE9")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 92f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#CBD5E1")
            textSize = 36f
        }

        c.drawText("PLASMAWAVE • MÜZİK ÖZETİ (${periodLabel.uppercase()})", 116f, 180f, accentPaint)
        c.drawText(formatDuration(analytics.totalListenedMs), 116f, 320f, bigPaint)
        c.drawText("${analytics.totalPlays} dinlenme • ${analytics.uniqueTracks} şarkı • ${analytics.uniqueArtists} sanatçı", 116f, 395f, subPaint)
        c.drawText("Profil: ${analytics.listeningPersona} • Seri: ${analytics.currentStreakDays} gün (Rekor ${analytics.longestStreakDays})", 116f, 470f, subPaint)

        var y = 600f
        analytics.topArtistName?.let {
            c.drawText("#1 Sanatçı", 116f, y, accentPaint)
            c.drawText("$it (${analytics.topArtistPlays} kez)", 116f, y + 58f, titlePaint)
            y += 160f
        }
        analytics.topTrackTitle?.let {
            c.drawText("#1 Şarkı", 116f, y, accentPaint)
            c.drawText("$it (${analytics.topTrackPlays} kez)", 116f, y + 58f, titlePaint)
            y += 160f
        }
        analytics.topAlbumName?.let {
            c.drawText("#1 Albüm", 116f, y, accentPaint)
            c.drawText("$it (${analytics.topAlbumPlays} kez)", 116f, y + 58f, titlePaint)
        }

        c.drawText("projekt_b-d • Rust Core", 116f, h - 130f, subPaint)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = "plasmawave_ozet_$stamp.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PlasmaWave")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return@runCatching "Pictures/PlasmaWave/$name"
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file.name
    }.getOrNull()
}
