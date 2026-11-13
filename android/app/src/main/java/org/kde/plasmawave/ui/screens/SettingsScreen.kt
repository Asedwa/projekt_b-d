package org.kde.plasmawave.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.BuildConfig
import org.kde.plasmawave.deezer.DeezerLoginDialog
import org.kde.plasmawave.deezer.StreamingQuality
import org.kde.plasmawave.download.DownloadQuality
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateThemes: () -> Unit,
    onNavigateMods: () -> Unit,
    onNavigateDownloads: () -> Unit = {},
    onNavigateCustomization: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val user by viewModel.deezerUser.collectAsState()
    val busy by viewModel.deezerBusy.collectAsState()
    val loginError by viewModel.deezerLoginError.collectAsState()
    val wifiQuality by viewModel.wifiQuality.collectAsState()
    val mobileQuality by viewModel.mobileQuality.collectAsState()
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val mobileDownloads by viewModel.mobileDownloadsAllowed.collectAsState()
    val highRefresh by viewModel.highRefreshRate.collectAsState()
    val dynamicColor by viewModel.dynamicColorEnabled.collectAsState()
    val seekbarStyle by viewModel.seekbarStyle.collectAsState()
    val downloads by viewModel.fullDownloads.collectAsState()
    val theme by viewModel.activeTheme.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val colors = LocalPlasmaColors.current
    var showLogin by remember { mutableStateOf(false) }
    var showArl by remember { mutableStateOf(false) }
    var arl by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    fun matches(vararg words: String) = query.isBlank() || words.any { it.contains(query.trim(), ignoreCase = true) }

    if (showLogin) {
        LaunchedEffect(user) { if (user != null) showLogin = false }
        DeezerLoginDialog(busy = busy, error = loginError, onDismiss = { showLogin = false }, onLogin = viewModel::connectDeezerArl)
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ayarlar", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) {
                Icon(Icons.Default.Search, contentDescription = "Ayarlarda ara")
            }
        }
        if (searchVisible) OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, placeholder = { Text("Ayarlarda ara") })

        if (matches("hesap", "deezer", "giriş", "oturum")) {
            SettingsSection("Hesap")
            Card(colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user?.name ?: "Deezer hesabı", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(user?.statusLabel ?: "Kitaplığın ve tam şarkılar için giriş yap", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (user != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::refreshDeezerData, modifier = Modifier.weight(1f), enabled = !busy) { Text("Yenile") }
                            OutlinedButton(onClick = viewModel::disconnectDeezer, modifier = Modifier.weight(1f), enabled = !busy) { Text("Çıkış yap") }
                        }
                    } else {
                        Button(onClick = { showLogin = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Giriş yapılıyor…" else "Deezer ile giriş yap") }
                        TextButton(onClick = { showArl = !showArl }) { Text("ARL ile giriş") }
                        if (showArl) {
                            OutlinedTextField(arl, { arl = it }, label = { Text("Deezer ARL") }, visualTransformation = PasswordVisualTransformation(),
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { val value = arl; arl = ""; viewModel.connectDeezerArl(value) }, enabled = arl.isNotBlank() && !busy) { Text("Bağlan") }
                        }
                    }
                }
            }
        }

        if (matches("kalite", "wifi", "wi-fi", "mobil", "ses", "flac", "mp3", "dinleme")) {
            SettingsSection("Ses kalitesi")
            QualityRow(Icons.Default.Wifi, "Wi-Fi ile dinleme", "Wi-Fi üzerinden", wifiQuality.label,
                StreamingQuality.entries.map { it.label }, { viewModel.setWifiQuality(StreamingQuality.entries[it]) })
            QualityRow(Icons.Default.SignalCellularAlt, "Mobil veriyle dinleme", "Mobil ve diğer ölçülen ağlarda", mobileQuality.label,
                StreamingQuality.entries.map { it.label }, { viewModel.setMobileQuality(StreamingQuality.entries[it]) })
            QualityRow(Icons.Default.HighQuality, "İndirme kalitesi", "Varsayılan seçim • Deezer hesabına bağlı", downloadQuality.label,
                DownloadQuality.entries.map { it.label }, { viewModel.setDownloadQuality(DownloadQuality.entries[it]) })
        }
        if (matches("indir", "çevrimdışı", "mobil", "veri", "wifi", "wi-fi", "depolama", "dosya")) {
            SettingsSection("Veri ve indirmeler")
            SettingsToggle(Icons.Default.DataUsage, "Mobil veriyle indirmeye izin ver",
                "Kapalıyken ölçülmeyen Wi-Fi veya Ethernet beklenir", mobileDownloads, viewModel::setMobileDownloadsAllowed)
            val active = downloads.count { it.state == "queued" || it.state == "running" }
            SettingsLink(Icons.Default.Download, "İndirme sırası ve dosyalar",
                if (active > 0) "$active şarkı sırada veya indiriliyor" else "İlerleme, iptal ve çevrimdışı dinleme", onNavigateDownloads)
            SettingsInfo(Icons.Default.Folder, "Kayıt konumu", "Müzik / PlasmaWave")
        }
        if (matches("özelleştirme", "görüntü", "ekran", "yenileme", "akıcı", "120", "tema", "görünüm", "renk", "desen", "çubuk", "albüm")) {
            SettingsSection("Özelleştirme ve görünüm")
            SettingsLink(Icons.Default.Tune, "Özelleştirme", "Dinamik albüm renkleri, ${seekbarStyle.title}", onNavigateCustomization)
            SettingsToggle(Icons.Default.ColorLens, "Dinamik albüm renkleri",
                "Tam ekran oynatıcıyı çalan şarkının albüm kapağına göre renklendir", dynamicColor, viewModel::setDynamicColorEnabled)
            SettingsLink(Icons.Default.Palette, "Tema ve renkler", "Etkin tema: ${theme.name}", onNavigateThemes)
            SettingsToggle(Icons.Default.Speed, "Yüksek ekran yenileme hızı", "Desteklenen ekranlarda 120 Hz'e kadar; cihaz ayarları geçerlidir", highRefresh, viewModel::setHighRefreshRate)
        }
        if (matches("eklenti", "mod", "gelişmiş", "geliştirici")) {
            SettingsSection("Gelişmiş")
            SettingsLink(Icons.Default.Extension, "Eklentiler", "Yüklü modları yönet", onNavigateMods)
            SettingsToggle(Icons.Default.Code, "Geliştirme örneklerini göster", "Yalnızca test içerikleri", settings.showDevFixtures) {
                viewModel.updateSettings(settings.copy(showDevFixtures = it))
            }
        }
        if (matches("hakkında", "sürüm", "projekt_b-d", "plasmawave")) {
            SettingsSection("Hakkında")
            SettingsInfo(Icons.Default.Info, "projekt_b-d", BuildConfig.VERSION_NAME)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun SettingsSection(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SettingRow(icon, title, subtitle, Modifier.clickable(onClick = onClick)) {
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(15.dp), tint = LocalPlasmaColors.current.textSecondary)
    }
}

@Composable
private fun SettingsInfo(icon: ImageVector, title: String, subtitle: String) { SettingRow(icon, title, subtitle) {} }

@Composable
private fun SettingsToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, subtitle) { Switch(checked = checked, onCheckedChange = onChange) }
}

@Composable
private fun QualityRow(icon: ImageVector, title: String, subtitle: String, selected: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon, title, "$subtitle • $selected", Modifier.clickable { expanded = true }) {
            Icon(Icons.Default.ExpandMore, contentDescription = "Kalite seç")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, leadingIcon = { if (selected == option) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSelect(index); expanded = false })
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalPlasmaColors.current.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
