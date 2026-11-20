package org.kde.plasmawave.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.plasmawave.BuildConfig
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.deezer.StreamingQuality
import org.kde.plasmawave.download.DownloadQuality
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.HomeWidgetType
import org.kde.plasmawave.ui.components.ListeningStatsDialog

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badgeColor: Color
) {
    HOME_AND_WIDGETS(
        title = "Ana Sayfa ve Widget'lar",
        subtitle = "Widget ekle/düzenle, Kaldığın yerden devam et, hızlı erişim",
        icon = Icons.Default.Widgets,
        badgeColor = Color(0xFF8B5CF6)
    ),
    CUSTOMIZATION_AND_THEMES(
        title = "Özelleştirme ve Görünüm",
        subtitle = "Uygulama ikonu, ana sayfa logosu, Custom Themes ve ekran",
        icon = Icons.Default.Palette,
        badgeColor = Color(0xFFEC4899)
    ),
    AUDIO_AND_PLAYBACK(
        title = "Ses Kalitesi ve Oynatma",
        subtitle = "Wi-Fi ve mobil yayın kalitesi, indirme kalitesi, oynatma",
        icon = Icons.Default.GraphicEq,
        badgeColor = Color(0xFF10B981)
    ),
    DOWNLOADS_AND_STORAGE(
        title = "İndirmeler ve Depolama",
        subtitle = "İndirme sırası, çevrimdışı müzik sabitleme ve mobil veri",
        icon = Icons.Default.Download,
        badgeColor = Color(0xFF3B82F6)
    ),
    DATA_AND_PRIVACY(
        title = "Veri, İstatistikler ve Gizlilik",
        subtitle = "Dinleme süresi, şarkı sayaçları, veri indirme ve gizlilik izinleri",
        icon = Icons.Default.BarChart,
        badgeColor = Color(0xFFF59E0B)
    ),
    PLUGINS_AND_ABOUT(
        title = "Eklentiler ve Hakkında",
        subtitle = "Mod yöneticisi, geliştirici seçenekleri ve sürüm bilgisi",
        icon = Icons.Default.Info,
        badgeColor = Color(0xFF06B6D4)
    )
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateThemes: () -> Unit,
    onNavigateMods: () -> Unit,
    onNavigateDownloads: () -> Unit = {},
    onNavigateCustomization: () -> Unit = {},
    onNavigateStats: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val user by viewModel.deezerUser.collectAsState()
    val busy by viewModel.deezerBusy.collectAsState()
    val loginError by viewModel.deezerLoginError.collectAsState()
    val wifiQuality by viewModel.wifiQuality.collectAsState()
    val mobileQuality by viewModel.mobileQuality.collectAsState()
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val mobileDownloads by viewModel.mobileDownloadsAllowed.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val downloadsLibraryPinned by viewModel.downloadsLibraryPinned.collectAsState()
    val flowHeroCardEnabled by viewModel.flowHeroCardEnabled.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val collectStats by viewModel.collectListeningStatsEnabled.collectAsState()
    val collectHistory by viewModel.collectListeningHistoryEnabled.collectAsState()
    val saveResumePos by viewModel.saveResumePositionEnabled.collectAsState()
    val resumeCardEnabled by viewModel.resumePlaybackEnabled.collectAsState()
    val enabledWidgets by viewModel.enabledHomeWidgets.collectAsState()
    val homeEditHoldSeconds by viewModel.homeEditHoldSeconds.collectAsState()
    val trackStats by viewModel.trackListeningStats.collectAsState()
    val totalListeningMs by viewModel.totalListeningTimeMs.collectAsState()
    val privateSession by viewModel.privateSessionStatus.collectAsState()
    val storageBreakdown by viewModel.storageBreakdown.collectAsState()
    val highRefresh by viewModel.highRefreshRate.collectAsState()
    val dynamicColor by viewModel.dynamicColorEnabled.collectAsState()
    val legacyUiMode by viewModel.legacyUiMode.collectAsState()
    val seekbarStyle by viewModel.seekbarStyle.collectAsState()
    val downloads by viewModel.fullDownloads.collectAsState()
    val theme by viewModel.activeTheme.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val colors = LocalPlasmaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCategory = activeCategoryName?.let { name ->
        SettingsCategory.entries.find { it.name == name }
    }
    var showLogin by remember { mutableStateOf(false) }
    var showArl by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var arl by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = viewModel.buildListeningStatsCsv()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        true
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) "CSV istatistik dosyası kaydedildi." else "CSV kaydedilemedi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val jsonImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                }
                if (raw.isNullOrBlank()) {
                    Toast.makeText(context, "Yedek dosyası okunamadı.", Toast.LENGTH_LONG).show()
                } else {
                    pendingImportJson = raw
                }
            }
        }
    }

    pendingImportJson?.let { rawJson ->
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text("Yedekten Geri Yükle") },
            text = {
                Text("Seçtiğiniz JSON yedeği mevcut verilerle birleştirilsin mi yoksa mevcut verilerin üzerine mi yazılsın?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportJson = null
                        scope.launch {
                            val res = viewModel.importUserDataFromJson(rawJson, merge = true)
                            res.onSuccess { r ->
                                Toast.makeText(
                                    context,
                                    "Birleştirildi: ${r.importedTracks} şarkı, ${r.importedDays} gün, ${r.importedThemes} tema",
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "Geri yükleme hatası: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text("Birleştir") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingImportJson = null
                            scope.launch {
                                val res = viewModel.importUserDataFromJson(rawJson, merge = false)
                                res.onSuccess { r ->
                                    Toast.makeText(
                                        context,
                                        "Geri yüklendi: ${r.importedTracks} şarkı, ${r.importedDays} gün",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Geri yükleme hatası: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) { Text("Üzerine Yaz", color = Color(0xFFEF4444)) }
                    TextButton(onClick = { pendingImportJson = null }) { Text("İptal") }
                }
            }
        )
    }

    BackHandler(enabled = activeCategory != null) {
        activeCategoryName = null
    }

    if (showLogin) {
        LaunchedEffect(user) { if (user != null) showLogin = false }
        DeezerLoginDialog(
            busy = busy,
            error = loginError,
            onDismiss = { showLogin = false },
            onLogin = viewModel::connectDeezerArl
        )
    }

    if (showStatsDialog) {
        ListeningStatsDialog(
            viewModel = viewModel,
            onDismiss = { showStatsDialog = false }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Toplanan Verileri Sil?") },
            text = {
                Text("Dinleme istatistikleri, şarkı sayaçları, toplam dinleme süresi ve kaldığın yerden devam et kayıtları kalıcı olarak silinecek.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllCollectedUserData()
                        showResetConfirm = false
                        Toast.makeText(context, "Toplanan tüm veriler silindi.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Sil", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    val totalPlays = remember(trackStats) { trackStats.sumOf { it.playCount } }
    val activeDownloadsCount = remember(downloads) {
        downloads.count { it.state == "queued" || it.state == "running" }
    }

    AnimatedContent(
        targetState = activeCategory,
        label = "settings_category_transition"
    ) { category ->
        if (category == null) {
            // ANA AYARLAR MENÜSÜ (Telefon Ayarları Görünümü)
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Ayarlar",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) query = ""
                        }
                    ) {
                        Icon(
                            if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Ayarlarda ara"
                        )
                    }
                }

                if (searchVisible) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Ayar, widget, kalite veya gizlilik ara...") }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Deezer Hesap Kartı (Telefon Ayarları Üst Profil Kartı gibi)
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (colors.legacyUiMode) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    user?.name ?: "Deezer Hesabı",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    user?.statusLabel ?: "Kitaplığın ve tam şarkılar için giriş yapın",
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (user != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = viewModel::refreshDeezerData,
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Senkronize Et")
                                }
                                OutlinedButton(
                                    onClick = viewModel::disconnectDeezer,
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Çıkış Yap")
                                }
                            }
                        } else {
                            Button(
                                onClick = { showLogin = true },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (busy) "Giriş yapılıyor…" else "Deezer ile Giriş Yap")
                            }
                            TextButton(onClick = { showArl = !showArl }) {
                                Text("ARL Anahtarı ile Giriş")
                            }
                            if (showArl) {
                                OutlinedTextField(
                                    value = arl,
                                    onValueChange = { arl = it },
                                    label = { Text("Deezer ARL") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextButton(
                                    onClick = {
                                        val value = arl
                                        arl = ""
                                        viewModel.connectDeezerArl(value)
                                    },
                                    enabled = arl.isNotBlank() && !busy
                                ) {
                                    Text("Bağlan")
                                }
                            }
                        }
                    }
                }

                fun matchesSearch(vararg keywords: String): Boolean {
                    if (query.isBlank()) return true
                    val q = query.trim()
                    return keywords.any { it.contains(q, ignoreCase = true) }
                }

                // Kategori Grup 1: Görünüm ve Ana Sayfa
                if (matchesSearch("eski mod", "klasik", "görünüm", "breeze", "widget", "ana sayfa", "kaldığın", "devam", "özelleştirme", "tema", "ikon", "logo", "renk", "120", "ekran")) {
                    Spacer(Modifier.height(14.dp))
                    PhoneSettingsCard {
                        SettingsToggle(
                            icon = Icons.Default.Palette,
                            title = "Eski Mod",
                            subtitle = "Klasik renkli gradyanlar, kart kenarlıkları ve daire ikonlar",
                            checked = legacyUiMode,
                            onChange = viewModel::setLegacyUiMode
                        )
                    }
                    PhoneSettingsGroupLabel("GÖRÜNÜM VE ANA SAYFA")
                    PhoneSettingsCard {
                        if (matchesSearch("widget", "ana sayfa", "kaldığın", "devam", "istatistik", "sanatçı", "şanslı", "uyku", "miks")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.HOME_AND_WIDGETS.icon,
                                badgeColor = SettingsCategory.HOME_AND_WIDGETS.badgeColor,
                                title = "Ana Sayfa ve Widget'lar",
                                subtitle = "Widget ekle/düzenle (${enabledWidgets.size} aktif) • Kaldığın yerden devam et",
                                onClick = { activeCategoryName = SettingsCategory.HOME_AND_WIDGETS.name }
                            )
                        }
                        if (matchesSearch("widget", "ana sayfa", "kaldığın", "devam") &&
                            matchesSearch("özelleştirme", "tema", "ikon", "logo", "renk", "120", "ekran")
                        ) {
                            PhoneSettingsDivider()
                        }
                        if (matchesSearch("özelleştirme", "tema", "ikon", "logo", "renk", "120", "ekran", "twilight", "pinkie")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.CUSTOMIZATION_AND_THEMES.icon,
                                badgeColor = SettingsCategory.CUSTOMIZATION_AND_THEMES.badgeColor,
                                title = "Özelleştirme ve Temalar",
                                subtitle = "Tema: ${theme.name} • Uygulama ikonu, logo ve oynatıcı",
                                onClick = { activeCategoryName = SettingsCategory.CUSTOMIZATION_AND_THEMES.name }
                            )
                        }
                    }
                }

                // Kategori Grup 2: Ses, Oynatma ve İndirmeler
                if (matchesSearch("ses", "kalite", "wifi", "mobil", "flac", "mp3", "indir", "çevrimdışı", "depolama", "sabitle")) {
                    PhoneSettingsGroupLabel("SES VE İNDİRMELER")
                    PhoneSettingsCard {
                        if (matchesSearch("ses", "kalite", "wifi", "mobil", "flac", "mp3", "oynatma")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.AUDIO_AND_PLAYBACK.icon,
                                badgeColor = SettingsCategory.AUDIO_AND_PLAYBACK.badgeColor,
                                title = "Ses Kalitesi ve Oynatma",
                                subtitle = "Wi-Fi: ${wifiQuality.label} • Mobil: ${mobileQuality.label}",
                                onClick = { activeCategoryName = SettingsCategory.AUDIO_AND_PLAYBACK.name }
                            )
                        }
                        if (matchesSearch("ses", "kalite", "wifi", "mobil", "flac", "mp3") &&
                            matchesSearch("indir", "çevrimdışı", "depolama", "sabitle")
                        ) {
                            PhoneSettingsDivider()
                        }
                        if (matchesSearch("indir", "çevrimdışı", "depolama", "sabitle", "dosya")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.DOWNLOADS_AND_STORAGE.icon,
                                badgeColor = SettingsCategory.DOWNLOADS_AND_STORAGE.badgeColor,
                                title = "İndirmeler ve Depolama",
                                subtitle = if (activeDownloadsCount > 0) {
                                    "$activeDownloadsCount aktif indirme • Kalite: ${downloadQuality.label}"
                                } else {
                                    "Kalite: ${downloadQuality.label} • Çevrimdışı müzikler ve sabitleme"
                                },
                                onClick = { activeCategoryName = SettingsCategory.DOWNLOADS_AND_STORAGE.name }
                            )
                        }
                    }
                }

                // Kategori Grup 3: Veri, Gizlilik ve Sistem
                if (matchesSearch("veri", "gizlilik", "istatistik", "süre", "sayaç", "geçmiş", "eklenti", "mod", "hakkında", "sürüm", "projekt")) {
                    PhoneSettingsGroupLabel("VERİ, GİZLİLİK VE SİSTEM")
                    PhoneSettingsCard {
                        if (matchesSearch("veri", "gizlilik", "istatistik", "süre", "sayaç", "geçmiş", "json", "sıfırla")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.DATA_AND_PRIVACY.icon,
                                badgeColor = SettingsCategory.DATA_AND_PRIVACY.badgeColor,
                                title = "Veri, İstatistikler ve Gizlilik",
                                subtitle = "Süre: ${viewModel.formatDurationTr(totalListeningMs)} • $totalPlays dinlenme, veri indirme",
                                onClick = { activeCategoryName = SettingsCategory.DATA_AND_PRIVACY.name }
                            )
                        }
                        if (matchesSearch("veri", "gizlilik", "istatistik", "süre", "sayaç") &&
                            matchesSearch("eklenti", "mod", "hakkında", "sürüm", "projekt")
                        ) {
                            PhoneSettingsDivider()
                        }
                        if (matchesSearch("eklenti", "mod", "hakkında", "sürüm", "projekt", "geliştirici")) {
                            PhoneCategoryRow(
                                icon = SettingsCategory.PLUGINS_AND_ABOUT.icon,
                                badgeColor = SettingsCategory.PLUGINS_AND_ABOUT.badgeColor,
                                title = "Eklentiler ve Hakkında",
                                subtitle = "Mod yöneticisi ve sistem • projekt_b-d v${BuildConfig.VERSION_NAME}",
                                onClick = { activeCategoryName = SettingsCategory.PLUGINS_AND_ABOUT.name }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(96.dp))
            }
        } else {
            // ALT KATEGORİ SAYFASI
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = { activeCategoryName = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(category.badgeColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            category.icon,
                            contentDescription = null,
                            tint = category.badgeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            category.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            category.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (category) {
                    SettingsCategory.HOME_AND_WIDGETS -> {
                        PhoneSettingsGroupLabel("WIDGET EKLE / DÜZENLE (${enabledWidgets.size} / ${HomeWidgetType.entries.size} AKTİF)")
                        PhoneSettingsCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Ana sayfada görmek istediğiniz widget'ları seçin:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        HomeWidgetType.entries.forEach { w ->
                                            viewModel.setHomeWidgetEnabled(w, true)
                                        }
                                    }
                                ) {
                                    Text("Tümünü Aç")
                                }
                            }
                            PhoneSettingsDivider()
                            HomeWidgetType.entries.forEachIndexed { index, widget ->
                                val isEnabled = widget in enabledWidgets
                                val widgetIcon = when (widget) {
                                    HomeWidgetType.STATS -> Icons.Default.BarChart
                                    HomeWidgetType.QUICK_ARTIST -> Icons.Default.PersonSearch
                                    HomeWidgetType.QUICK_THEME -> Icons.Default.Palette
                                    HomeWidgetType.LUCKY_PICK -> Icons.Default.Casino
                                    HomeWidgetType.SLEEP_TIMER -> Icons.Default.Bedtime
                                    HomeWidgetType.MOOD_MIX -> Icons.Default.AutoAwesome
                                    HomeWidgetType.RECENT_TRACKS -> Icons.Default.History
                                    HomeWidgetType.LISTENING_SUMMARY -> Icons.Default.Speed
                                }
                                SettingsToggle(
                                    icon = widgetIcon,
                                    title = widget.title,
                                    subtitle = widget.subtitle,
                                    checked = isEnabled,
                                    onChange = { viewModel.setHomeWidgetEnabled(widget, it) }
                                )
                                if (index < HomeWidgetType.entries.lastIndex) {
                                    PhoneSettingsDivider()
                                }
                            }
                        }

                        PhoneSettingsGroupLabel("KALDIĞIN YERDEN DEVAM ET")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.Restore,
                                title = "Ana sayfada 'Kaldığın yerden devam et' kartını göster",
                                subtitle = "Son dinlediğiniz şarkıya kaldığınız saniyeden tek dokunuşla devam edin",
                                checked = resumeCardEnabled,
                                onChange = viewModel::setResumePlaybackEnabled
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.PlayCircleOutline,
                                title = "Son çalınan şarkı ve saniyesini otomatik kaydet",
                                subtitle = "Uygulama kapansa bile kaldığınız saniye ve sıra hatırlanır",
                                checked = saveResumePos,
                                onChange = viewModel::setSaveResumePositionEnabled
                            )
                        }

                        PhoneSettingsGroupLabel("DÜZENLEME MODU BASILI TUTMA SÜRESİ")
                        PhoneSettingsCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Düzenleme Modu Basılı Tutma",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            "Ana sayfada düzenleme modunu açmak için gereken süre (3 – 10 sn)",
                                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                        )
                                    }
                                    Text(
                                        "$homeEditHoldSeconds sn",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                Slider(
                                    value = homeEditHoldSeconds.toFloat(),
                                    onValueChange = { viewModel.setHomeEditHoldSeconds(it.toInt().coerceIn(3, 10)) },
                                    valueRange = 3f..10f,
                                    steps = 6
                                )
                            }
                        }

                        PhoneSettingsGroupLabel("ANA SAYFA KARTLARI VE SABİTLEMELER")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.AutoAwesome,
                                title = "Deezer Flow kartını ana sayfada göster",
                                subtitle = "Ana sayfanın üst kısmında büyük Deezer Flow kartı",
                                checked = flowHeroCardEnabled,
                                onChange = viewModel::setFlowHeroCardEnabled
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.PushPin,
                                title = "Deezer Flow'u Hızlı Erişim'e sabitle",
                                subtitle = "Hızlı erişim ızgarasında Deezer Flow kısayolunu göster",
                                checked = flowQuickAccessPinned,
                                onChange = viewModel::setFlowQuickAccessPinned
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.DownloadDone,
                                title = "İndirilenleri Hızlı Erişim'e sabitle",
                                subtitle = "Ana sayfadaki Hızlı Erişim alanında İndirilen Müzikler kartını göster",
                                checked = downloadsQuickAccessPinned,
                                onChange = viewModel::setDownloadsQuickAccessPinned
                            )
                        }
                    }

                    SettingsCategory.CUSTOMIZATION_AND_THEMES -> {
                        PhoneSettingsGroupLabel("TEMA, LOGO VE İKON ÖZELLEŞTİRME")
                        PhoneSettingsCard {
                            SettingsLink(
                                icon = Icons.Default.Tune,
                                title = "Özelleştirme Stüdyosu",
                                subtitle = "Uygulama ikonu, ana sayfa logosu, Custom Themes (Twilight, Pinkie ve özel temalar)",
                                onClick = onNavigateCustomization
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.Palette,
                                title = "Tema Galerisi",
                                subtitle = "Etkin tema: ${theme.name}",
                                onClick = onNavigateThemes
                            )
                        }

                        PhoneSettingsGroupLabel("EKRAN VE OYNATICI GÖRÜNÜMÜ")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.Palette,
                                title = "Eski Mod (Klasik Görünüm)",
                                subtitle = "Eski renkli gradyanlar, çerçeveli kartlar, daire ikonlar ve pin rozetlerini geri getir",
                                checked = legacyUiMode,
                                onChange = viewModel::setLegacyUiMode
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.ColorLens,
                                title = "Dinamik albüm renkleri",
                                subtitle = "Mini oynatıcı ve oynatıcı ekranını çalan kapağa göre renklendir",
                                checked = dynamicColor,
                                onChange = viewModel::setDynamicColorEnabled
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.Speed,
                                title = "Yüksek ekran yenileme hızı (120 Hz)",
                                subtitle = "Desteklenen ekranlarda 120 Hz akıcı kaydırma",
                                checked = highRefresh,
                                onChange = viewModel::setHighRefreshRate
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.LinearScale,
                                title = "İlerleme çubuğu ve kapak çerçevesi",
                                subtitle = "Seçili çubuk: ${seekbarStyle.title}",
                                onClick = onNavigateCustomization
                            )
                        }
                    }

                    SettingsCategory.AUDIO_AND_PLAYBACK -> {
                        PhoneSettingsGroupLabel("YAYIN VE İNDİRME SES KALİTESİ")
                        PhoneSettingsCard {
                            QualityRow(
                                icon = Icons.Default.Wifi,
                                title = "Wi-Fi ile dinleme kalitesi",
                                subtitle = "Kablosuz ağ bağlantısında",
                                selected = wifiQuality.label,
                                options = StreamingQuality.entries.map { it.label },
                                onSelect = { viewModel.setWifiQuality(StreamingQuality.entries[it]) }
                            )
                            PhoneSettingsDivider()
                            QualityRow(
                                icon = Icons.Default.SignalCellularAlt,
                                title = "Mobil veriyle dinleme kalitesi",
                                subtitle = "Mobil ve ölçülen ağlarda",
                                selected = mobileQuality.label,
                                options = StreamingQuality.entries.map { it.label },
                                onSelect = { viewModel.setMobileQuality(StreamingQuality.entries[it]) }
                            )
                            PhoneSettingsDivider()
                            QualityRow(
                                icon = Icons.Default.HighQuality,
                                title = "Varsayılan indirme kalitesi",
                                subtitle = "Deezer hesabına bağlı",
                                selected = downloadQuality.label,
                                options = DownloadQuality.entries.map { it.label },
                                onSelect = { viewModel.setDownloadQuality(DownloadQuality.entries[it]) }
                            )
                        }

                        PhoneSettingsGroupLabel("OYNATMA DEVAMLILIĞI")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.PlayCircleOutline,
                                title = "Kaldığın yerden devam et konumunu kaydet",
                                subtitle = "Son dinlediğiniz şarkının saniyesini ve sırasını otomatik hatırlar",
                                checked = saveResumePos,
                                onChange = viewModel::setSaveResumePositionEnabled
                            )
                        }
                    }

                    SettingsCategory.DOWNLOADS_AND_STORAGE -> {
                        PhoneSettingsGroupLabel("İNDİRME YÖNETİMİ VE DEPOLAMA")
                        PhoneSettingsCard {
                            SettingsLink(
                                icon = Icons.Default.Download,
                                title = "İndirme sırası ve dosyalar",
                                subtitle = if (activeDownloadsCount > 0) {
                                    "$activeDownloadsCount şarkı sırada veya indiriliyor"
                                } else {
                                    "İndirme ilerlemesi, iptal ve çevrimdışı şarkılar"
                                },
                                onClick = onNavigateDownloads
                            )
                            PhoneSettingsDivider()
                            SettingsInfo(
                                icon = Icons.Default.Folder,
                                title = "Kayıt konumu",
                                subtitle = "Müzik / PlasmaWave"
                            )
                        }

                        PhoneSettingsGroupLabel("VERİ VE SABİTLEME SEÇENEKLERİ")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.DataUsage,
                                title = "Mobil veriyle indirmeye izin ver",
                                subtitle = "Kapalıyken yalnızca Wi-Fi veya Ethernet üzerinden indirilir",
                                checked = mobileDownloads,
                                onChange = viewModel::setMobileDownloadsAllowed
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.PushPin,
                                title = "İndirilenleri ana sayfaya sabitle",
                                subtitle = "Ana sayfadaki Hızlı Erişim alanında İndirilen Müzikler kartını göster",
                                checked = downloadsQuickAccessPinned,
                                onChange = viewModel::setDownloadsQuickAccessPinned
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.LibraryMusic,
                                title = "İndirilenleri kitaplığa sabitle",
                                subtitle = "Kitaplık sekmesinde İndirilen Müzikler koleksiyonunu sabitle",
                                checked = downloadsLibraryPinned,
                                onChange = viewModel::setDownloadsLibraryPinned
                            )
                        }
                    }

                    SettingsCategory.DATA_AND_PRIVACY -> {
                        LaunchedEffect(Unit) {
                            viewModel.refreshStorageBreakdown()
                            viewModel.refreshPrivateSessionStatus()
                        }
                        val topSong = remember(trackStats) { trackStats.firstOrNull() }
                        PhoneSettingsGroupLabel("DİNLEME İSTATİSTİKLERİ VE SAYAÇLAR")
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateStats() }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (colors.legacyUiMode) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.BarChart,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            Icons.Default.BarChart,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Dinleme İstatistikleri ve Verilerim",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "Toplam süre: ${viewModel.formatDurationTr(totalListeningMs)} • $totalPlays dinlenme (${trackStats.size} şarkı)",
                                            color = colors.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (topSong != null) {
                                            Text(
                                                "En çok: ${topSong.track.title} (${topSong.playCount} kez)",
                                                color = if (colors.legacyUiMode) MaterialTheme.colorScheme.primary else colors.textSecondary,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onNavigateStats,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Detaylı İstatistikler")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val path = viewModel.exportUserDataToDownloads()
                                            if (path != null) {
                                                Toast.makeText(
                                                    context,
                                                    "Verileriniz İndirilenler klasörüne kaydedildi:\n$path",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("JSON İndir")
                                    }
                                }
                            }
                        }

                        PhoneSettingsGroupLabel("GİZLİ DİNLEME OTURUMU")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.VisibilityOff,
                                title = "Gizli dinleme oturumu",
                                subtitle = if (privateSession.active) {
                                    "Aktif — dinlenen şarkılar istatistiklere ve geçmişe kaydedilmez"
                                } else {
                                    "Açıkken dinlediğiniz şarkılar sayaçlara ve geçmişe işlenmez"
                                },
                                checked = privateSession.active,
                                onChange = { enabled -> viewModel.setPrivateSession(enabled, 0L) }
                            )
                            PhoneSettingsDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = privateSession.active && privateSession.untilEpochMs == 0uL,
                                    onClick = { viewModel.setPrivateSession(true, 0L) },
                                    label = { Text("Kapatana kadar") }
                                )
                                FilterChip(
                                    selected = privateSession.active && privateSession.untilEpochMs > 0uL,
                                    onClick = { viewModel.setPrivateSession(true, 3_600_000L) },
                                    label = { Text("1 Saat") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { viewModel.setPrivateSession(true, 3 * 3_600_000L) },
                                    label = { Text("3 Saat") }
                                )
                            }
                        }

                        PhoneSettingsGroupLabel("VERİ TOPLAMA İZİNLERİ")
                        PhoneSettingsCard {
                            SettingsToggle(
                                icon = Icons.Default.Analytics,
                                title = "Dinleme istatistiklerini ve şarkı sayaçlarını topla",
                                subtitle = "Hangi şarkıyı kaç kere dinlediğinizi ve toplam dinleme sürenizi cihazda kaydeder",
                                checked = collectStats,
                                onChange = viewModel::setCollectListeningStatsEnabled
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.History,
                                title = "Son çalınanlar geçmişini kaydet",
                                subtitle = "Dinlediğiniz son parçaları ve öneri geçmişini cihazda tutar",
                                checked = collectHistory,
                                onChange = viewModel::setCollectListeningHistoryEnabled
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.PlayCircleOutline,
                                title = "Kaldığın yerden devam et konumunu kaydet",
                                subtitle = "Son dinlediğiniz şarkının saniyesini ve sırasını otomatik hatırlar",
                                checked = saveResumePos,
                                onChange = viewModel::setSaveResumePositionEnabled
                            )
                        }

                        PhoneSettingsGroupLabel("YEDEKLEME, DIŞA VE İÇE AKTARMA")
                        PhoneSettingsCard {
                            SettingsLink(
                                icon = Icons.Default.FileDownload,
                                title = "Verilerimi indir (JSON yedek)",
                                subtitle = "İstatistikler, günlük/saatlik grafikler, geçmiş ve özel temalar",
                                onClick = {
                                    val path = viewModel.exportUserDataToDownloads()
                                    if (path != null) {
                                        Toast.makeText(
                                            context,
                                            "Verileriniz İndirilenler klasörüne kaydedildi:\n$path",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.TableChart,
                                title = "İstatistikleri CSV olarak dışa aktar",
                                subtitle = "Excel veya Google E-Tablolar ile açılabilir tablo dosyası",
                                onClick = { csvExportLauncher.launch("plasmawave_istatistikler.csv") }
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.UploadFile,
                                title = "Yedekten geri yükle (JSON içe aktar)",
                                subtitle = "Önceki JSON yedeğinizi mevcut verilerle birleştirin veya geri yükleyin",
                                onClick = { jsonImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                            )
                        }

                        PhoneSettingsGroupLabel("DEPOLAMA VE VERİTABANI DÖKÜMÜ")
                        PhoneSettingsCard {
                            val fmtMb: (Long) -> String = { b -> "%.1f MB".format(b / (1024f * 1024f)) }
                            SettingsInfo(
                                icon = Icons.Default.Storage,
                                title = "Rust Veritabanı (plasmawave.db)",
                                subtitle = fmtMb(storageBreakdown.dbBytes)
                            )
                            PhoneSettingsDivider()
                            SettingsInfo(
                                icon = Icons.Default.Cached,
                                title = "Medya ve Kapak Önbelleği",
                                subtitle = "Medya: ${fmtMb(storageBreakdown.mediaCacheBytes)} • Kapak: ${fmtMb(storageBreakdown.imageCacheBytes)}"
                            )
                            PhoneSettingsDivider()
                            SettingsInfo(
                                icon = Icons.Default.Folder,
                                title = "İndirilen Müzikler (Müzik/PlasmaWave)",
                                subtitle = fmtMb(storageBreakdown.downloadsBytes)
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.CleaningServices,
                                title = "Medya ve kapak önbelleğini temizle",
                                subtitle = "İndirilen şarkılarınız ve istatistikleriniz etkilenmez",
                                onClick = { viewModel.clearAppCaches() }
                            )
                        }

                        PhoneSettingsGroupLabel("SEÇİCİ VERİ TEMİZLEME VE SIFIRLAMA")
                        PhoneSettingsCard {
                            SettingsLink(
                                icon = Icons.Default.Schedule,
                                title = "Son 24 saatin verilerini temizle",
                                subtitle = "Son bir gündeki dinleme geçmişini ve günlük kayıtları siler",
                                onClick = { viewModel.clearLast24hData() }
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.HistoryToggleOff,
                                title = "Yalnızca son çalınanlar geçmişini sil",
                                subtitle = "Şarkı sayaçları ve toplam dinleme süreniz korunur",
                                onClick = { viewModel.clearListeningHistoryOnly() }
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.RestartAlt,
                                title = "Yalnızca dinleme sayaçlarını sıfırla",
                                subtitle = "Sayaçlar, grafikler ve toplam süre sıfırlanır",
                                onClick = { viewModel.clearListeningStatsOnly() }
                            )
                            PhoneSettingsDivider()
                            SettingsLink(
                                icon = Icons.Default.DeleteSweep,
                                title = "Toplanan tüm verileri sıfırla ve temizle",
                                subtitle = "Kaydedilen tüm dinleme istatistiklerini, grafikleri ve geçmişi sil",
                                onClick = { showResetConfirm = true }
                            )
                        }
                    }

                    SettingsCategory.PLUGINS_AND_ABOUT -> {
                        PhoneSettingsGroupLabel("EKLENTİLER VE GELİŞTİRİCİ")
                        PhoneSettingsCard {
                            SettingsLink(
                                icon = Icons.Default.Extension,
                                title = "Eklentiler (Mod Yöneticisi)",
                                subtitle = "Yüklü modları ve özel aksiyonları yönetin",
                                onClick = onNavigateMods
                            )
                            PhoneSettingsDivider()
                            SettingsToggle(
                                icon = Icons.Default.Code,
                                title = "Geliştirme örneklerini göster",
                                subtitle = "Yalnızca test içerikleri ve örnek veriler",
                                checked = settings.showDevFixtures,
                                onChange = { viewModel.updateSettings(settings.copy(showDevFixtures = it)) }
                            )
                        }

                        PhoneSettingsGroupLabel("UYGULAMA HAKKINDA")
                        PhoneSettingsCard {
                            SettingsInfo(
                                icon = Icons.Default.Info,
                                title = "Uygulama Adı",
                                subtitle = "projekt_b-d"
                            )
                            PhoneSettingsDivider()
                            SettingsInfo(
                                icon = Icons.Default.Verified,
                                title = "Sürüm",
                                subtitle = BuildConfig.VERSION_NAME
                            )
                        }
                    }
                }

                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun PhoneSettingsGroupLabel(title: String) {
    val legacyUi = LocalPlasmaColors.current.legacyUiMode
    val formattedTitle = if (legacyUi) {
        title
    } else {
        title.lowercase(java.util.Locale("tr", "TR"))
            .replaceFirstChar { it.titlecase(java.util.Locale("tr", "TR")) }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text = formattedTitle,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalPlasmaColors.current.textSecondary,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

@Composable
private fun PhoneSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LocalPlasmaColors.current.card),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun PhoneSettingsDivider() {
    HorizontalDivider(
        color = LocalPlasmaColors.current.border.copy(alpha = 0.35f),
        thickness = 0.8.dp,
        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
    )
}

@Composable
private fun PhoneCategoryRow(
    icon: ImageVector,
    badgeColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (colors.legacyUiMode) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPlasmaColors.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = LocalPlasmaColors.current.textSecondary
        )
    }
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SettingRow(icon, title, subtitle, Modifier.clickable(onClick = onClick)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = LocalPlasmaColors.current.textSecondary
        )
    }
}

@Composable
private fun SettingsInfo(icon: ImageVector, title: String, subtitle: String) {
    SettingRow(icon, title, subtitle) {}
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable { onChange(!checked) }
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QualityRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon, title, "$subtitle • $selected", Modifier.clickable { expanded = true }) {
            Icon(Icons.Default.ExpandMore, contentDescription = "Kalite seç")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    leadingIcon = {
                        if (selected == option) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    val colors = LocalPlasmaColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (colors.legacyUiMode) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = colors.textSecondary
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPlasmaColors.current.textSecondary
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
