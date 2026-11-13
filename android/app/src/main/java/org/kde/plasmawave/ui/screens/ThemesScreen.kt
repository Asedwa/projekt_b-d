package org.kde.plasmawave.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.parseHexColor
import org.kde.plasmawave.ui.MainViewModel
import uniffi.plasmawave_core.FfiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTheme by viewModel.activeTheme.collectAsState()
    val availableThemes by viewModel.availableThemes.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val customColors = LocalPlasmaColors.current

    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Themes & Colors") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Import JSON")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "VURGU RENGİ (ACCENT COLOR)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = customColors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                AccentColorSection(
                    activeTheme = activeTheme,
                    customAccentColor = settings.customAccentColor,
                    onSetAccentColor = viewModel::setCustomAccentColor
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "SELECT THEME",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = customColors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(availableThemes, key = { it.id }) { theme ->
                val isSelected = theme.id == activeTheme.id
                val themeBg = parseHexColor(theme.backgroundColor, Color.DarkGray)
                val themeAccent = parseHexColor(theme.accentColor, Color.Cyan)
                val themeCard = parseHexColor(theme.cardColor, Color.Gray)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setTheme(theme.id) }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else customColors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = customColors.card)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Palette preview circles
                        Row {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(themeBg)
                                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(themeCard)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(themeAccent)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = theme.name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "by ${theme.author} • ${if (theme.isDark) "Dark" else "Light"}",
                                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Theme JSON") },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Paste theme manifest JSON") },
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importJsonText.isNotBlank()) {
                            viewModel.importThemeJson(importJsonText.trim())
                            importJsonText = ""
                            showImportDialog = false
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private val presetSwatches = listOf(
    "#3DAEE9" to "Breeze Mavi",
    "#A238FF" to "Deezer Mor",
    "#FF2E63" to "Neon Pembe",
    "#27AE60" to "Zümrüt",
    "#FF6B35" to "Turuncu",
    "#F1C40F" to "Altın",
    "#E74C3C" to "Kızıl",
    "#1ABC9C" to "Turkuaz",
    "#4C6EF5" to "Kobalt",
    "#BE4BDB" to "Lavanta"
)

@Composable
private fun AccentColorSection(
    activeTheme: FfiTheme,
    customAccentColor: String?,
    onSetAccentColor: (String?) -> Unit
) {
    val customColors = LocalPlasmaColors.current
    val currentAccentColor = remember(activeTheme.accentColor) {
        parseHexColor(activeTheme.accentColor, Color(0xFF3DAEE9))
    }
    var hue by remember { mutableFloatStateOf(200f) }
    var sat by remember { mutableFloatStateOf(0.74f) }
    var value by remember { mutableFloatStateOf(0.91f) }
    var hexInput by remember { mutableStateOf(activeTheme.accentColor) }

    LaunchedEffect(activeTheme.accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentAccentColor.toArgb(), hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        hexInput = activeTheme.accentColor.uppercase()
    }

    val previewColorInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    val previewColor = Color(previewColorInt)
    val previewHex = String.format("#%06X", 0xFFFFFF and previewColorInt)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = customColors.card),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Özel Vurgu Rengi",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (customAccentColor != null) "Aktif özel renk: $customAccentColor" else "Tema varsayılanı (${activeTheme.accentColor})",
                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                    )
                }
                if (customAccentColor != null) {
                    OutlinedButton(
                        onClick = { onSetAccentColor(null) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sıfırla")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Preset Swatches (2 rows of 5)
            presetSwatches.chunked(5).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    rowItems.forEach { (hex, _) ->
                        val swatchColor = parseHexColor(hex, Color.Cyan)
                        val isCurrent = activeTheme.accentColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(
                                    width = if (isCurrent) 3.dp else 1.dp,
                                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    hexInput = hex
                                    onSetAccentColor(hex)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (hex == "#F1C40F") Color.Black else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Custom HSV Sliders
            Text(
                text = "Renk Tonu (Hue) • $previewHex",
                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
            )
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    val cInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
                    hexInput = String.format("#%06X", 0xFFFFFF and cInt)
                },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = previewColor,
                    activeTrackColor = previewColor
                )
            )

            Text(
                text = "Doygunluk (Saturation): ${(sat * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
            )
            Slider(
                value = sat,
                onValueChange = {
                    sat = it
                    val cInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
                    hexInput = String.format("#%06X", 0xFFFFFF and cInt)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = previewColor,
                    activeTrackColor = previewColor
                )
            )

            Text(
                text = "Parlaklık (Brightness): ${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
            )
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    val cInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
                    hexInput = String.format("#%06X", 0xFFFFFF and cInt)
                },
                valueRange = 0.15f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = previewColor,
                    activeTrackColor = previewColor
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("Hex Kodu (#RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val previewOnColor = if (previewColor.luminance() > 0.52f) {
                    Color(0xFF1A1D20)
                } else {
                    Color.White
                }
                Button(
                    onClick = { onSetAccentColor(hexInput) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = previewColor,
                        contentColor = previewOnColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(54.dp)
                ) {
                    Icon(imageVector = Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Uygula")
                }
            }
        }
    }
}
