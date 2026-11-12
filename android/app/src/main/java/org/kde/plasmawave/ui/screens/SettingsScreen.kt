package org.kde.plasmawave.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.kde.plasmawave.deezer.DeezerLoginDialog
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.parseHexColor
import org.kde.plasmawave.ui.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateThemes: () -> Unit,
    onNavigateMods: () -> Unit,
    onNavigateDownloads: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeTheme by viewModel.activeTheme.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val customColors = LocalPlasmaColors.current
    val core = PlasmaWaveApp.core

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "SETTINGS",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = customColors.textSecondary
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        val deezerUser by viewModel.deezerUser.collectAsState()
        val deezerBusy by viewModel.deezerBusy.collectAsState()
        val deezerLoginError by viewModel.deezerLoginError.collectAsState()
        var deezerArl by remember { mutableStateOf("") }
        var showDeezerLogin by remember { mutableStateOf(false) }
        if (showDeezerLogin) {
            LaunchedEffect(deezerUser) { if (deezerUser != null) showDeezerLogin = false }
            DeezerLoginDialog(
                busy = deezerBusy,
                error = deezerLoginError,
                onDismiss = { showDeezerLogin = false },
                onLogin = { arl -> viewModel.connectDeezerArl(arl) }
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = customColors.card),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Spacer(modifier = Modifier.padding(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deezer Hesabı", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        Text(
                            if (deezerUser != null) "Bağlı: ${deezerUser?.name} (${deezerUser?.statusLabel})"
                            else "Tam şarkılar ve kütüphanen için Deezer'a giriş yap.",
                            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (deezerUser != null) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.refreshDeezerData() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Yenile")
                        }
                        Spacer(modifier = Modifier.padding(4.dp))
                        OutlinedButton(onClick = { viewModel.disconnectDeezer() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Çıkış Yap")
                        }
                    }
                } else {
                    Button(onClick = { showDeezerLogin = true }, enabled = !deezerBusy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(if (deezerBusy) "Giriş yapılıyor…" else "Deezer ile Giriş Yap")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("İstersen mevcut ARL oturumunu da kullanabilirsin.", style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deezerArl, onValueChange = { deezerArl = it },
                        label = { Text("Deezer ARL") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { val arl = deezerArl; deezerArl = ""; viewModel.connectDeezerArl(arl) },
                        enabled = deezerArl.isNotBlank() && !deezerBusy, modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("ARL ile Giriş Yap")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ARL, hesabına erişim sağlar; kimseyle paylaşma.", style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Appearance & Themes Card (with Quick Accent Swatches)
        val quickAccents = listOf("#3DAEE9", "#A238FF", "#FF2E63", "#27AE60", "#FF6B35", "#F1C40F")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = customColors.card)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateThemes)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ColorLens,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.padding(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Görünüm, Temalar & Vurgu Rengi",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Aktif: ${activeTheme.name} (${activeTheme.accentColor})",
                            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = customColors.textDisabled,
                        modifier = Modifier.size(16.dp)
                    )
                }

                HorizontalDivider(color = customColors.divider, modifier = Modifier.padding(horizontal = 16.dp))

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "Hızlı Vurgu Rengi Seç (Tüm Mavi Alanlar)",
                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        quickAccents.forEach { hex ->
                            val swatch = parseHexColor(hex, Color.Cyan)
                            val isSelected = activeTheme.accentColor.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.setCustomAccentColor(hex) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (hex == "#F1C40F") Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onNavigateThemes,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Özel Renk Paleti & Hex Girişi Aç")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // WebAssembly Mods Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onNavigateMods),
            colors = CardDefaults.cardColors(containerColor = customColors.card)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WebAssembly Mod Manager",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Manage Wasmi sandboxed plugins",
                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = customColors.textDisabled,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Offline Downloads Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onNavigateDownloads),
            colors = CardDefaults.cardColors(containerColor = customColors.card)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Offline Downloads Manager",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Manage downloaded audio and offline tracks",
                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = customColors.textDisabled,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Switches Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = customColors.card),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Wi-Fi Only Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = customColors.textSecondary)
                    Spacer(modifier = Modifier.padding(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download on Wi-Fi Only",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = "Prevent cellular data usage for media downloads",
                            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                        )
                    }
                    Switch(
                        checked = settings.downloadsWifiOnly,
                        onCheckedChange = { checked ->
                            val updated = settings.copy(downloadsWifiOnly = checked)
                            core.updateSettings(updated)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                    )
                }

                HorizontalDivider(color = customColors.divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Development Fixtures Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = customColors.textSecondary)
                    Spacer(modifier = Modifier.padding(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Development Fixtures",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = "Include clearly-labeled synthetic fixture tracks",
                            style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                        )
                    }
                    Switch(
                        checked = settings.showDevFixtures,
                        onCheckedChange = { checked ->
                            val updated = settings.copy(showDevFixtures = checked)
                            core.updateSettings(updated)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = customColors.card),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PlasmaWave Android",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version: 0.1.0-alpha\nArchitecture: Rust Core + UniFFI + Kotlin Jetpack Compose\nDesign: KDE Plasma Breeze Dark\nPlayback: Android Media3 / ExoPlayer",
                    style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                )
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}
