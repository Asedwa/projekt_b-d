package org.kde.plasmawave.ui.screens

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.parseHexColor
import org.kde.plasmawave.ui.CustomThemeSpec
import org.kde.plasmawave.ui.MainViewModel
import uniffi.plasmawave_core.FfiTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTheme by viewModel.activeTheme.collectAsState()
    val availableThemes by viewModel.availableThemes.collectAsState()
    val userCustomThemes by viewModel.userCustomThemes.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val customColors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var showCustomThemeDialog by remember { mutableStateOf(false) }
    var editingCustomTheme by remember { mutableStateOf<CustomThemeSpec?>(null) }

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
                    text = if (customColors.legacyUiMode) "VURGU RENGİ (ACCENT COLOR)" else "Vurgu rengi",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = customColors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                AccentColorSection(
                    activeTheme = activeTheme,
                    customAccentColor = settings.customAccentColor,
                    onSetAccentColor = viewModel::setCustomAccentColor
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = if (customColors.legacyUiMode) "SELECT THEME" else "Tema seçimi",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = customColors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = customColors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (customColors.legacyUiMode) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFED438D).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = Color(0xFFED438D),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = customColors.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Tema Seç",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                if (customColors.legacyUiMode) {
                                    Text(
                                        "Breeze, Nothing, Twilight, Pinkie ve kendi oluşturduğunuz özel temalar",
                                        style = MaterialTheme.typography.bodySmall.copy(color = customColors.textSecondary)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        val builtInMenuThemes = remember(availableThemes) {
                            availableThemes.filter { !it.id.startsWith("custom.") }
                        }
                        if (builtInMenuThemes.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                builtInMenuThemes.forEach { presetTheme ->
                                    val isPresetSelected = activeTheme.id == presetTheme.id
                                    val presetCard = if (customColors.legacyUiMode) parseHexColor(presetTheme.cardColor, Color(0xFF232629)) else customColors.cardPressed
                                    val presetText = if (customColors.legacyUiMode) parseHexColor(presetTheme.textColor, Color.White) else MaterialTheme.colorScheme.onSurface
                                    val rawPresetAccent = parseHexColor(presetTheme.accentColor, primary)
                                    val presetAccent = if (customColors.legacyUiMode) rawPresetAccent else org.kde.plasmawave.theme.desaturateForCalmUi(rawPresetAccent)

                                    Button(
                                        onClick = { viewModel.setTheme(presetTheme.id) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = presetCard,
                                            contentColor = presetText
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        border = if (!customColors.legacyUiMode) {
                                            if (isPresetSelected) BorderStroke(1.5.dp, presetAccent) else null
                                        } else if (isPresetSelected) {
                                            BorderStroke(2.dp, presetAccent)
                                        } else {
                                            BorderStroke(1.dp, presetAccent.copy(alpha = 0.35f))
                                        }
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(presetAccent)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                presetTheme.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (userCustomThemes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                userCustomThemes.forEach { customSpec ->
                                    val isCustomSelected = activeTheme.id == customSpec.id
                                    val cardBg = parseHexColor(customSpec.cardHex, Color(0xFF232629))
                                    val accentCol = parseHexColor(customSpec.accentHex, primary)
                                    val textCol = parseHexColor(customSpec.textHex, Color.White)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Button(
                                            onClick = { viewModel.setTheme(customSpec.id) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = cardBg,
                                                contentColor = textCol
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            border = if (isCustomSelected) {
                                                BorderStroke(2.dp, accentCol)
                                            } else {
                                                BorderStroke(1.dp, accentCol.copy(alpha = 0.4f))
                                            }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(12.dp)
                                                        .clip(CircleShape)
                                                        .background(accentCol)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = customSpec.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isCustomSelected) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Aktif",
                                                        tint = accentCol,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                editingCustomTheme = customSpec
                                                showCustomThemeDialog = true
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Temayı Düzenle",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                viewModel.deleteUserCustomTheme(customSpec.id)
                                                Toast.makeText(
                                                    context,
                                                    "'${customSpec.name}' teması silindi.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Temayı Sil",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                editingCustomTheme = null
                                showCustomThemeDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary.copy(alpha = 0.18f),
                                contentColor = primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Custom Tema Yap",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
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

    if (showCustomThemeDialog) {
        CustomThemeEditorDialog(
            initialSpec = editingCustomTheme,
            onDismiss = {
                showCustomThemeDialog = false
                editingCustomTheme = null
            },
            onSave = { spec ->
                viewModel.saveUserCustomTheme(spec)
                showCustomThemeDialog = false
                editingCustomTheme = null
                Toast.makeText(context, "'${spec.name}' teması kaydedildi ve uygulandı!", Toast.LENGTH_SHORT).show()
            },
            onDelete = editingCustomTheme?.let { existing ->
                {
                    viewModel.deleteUserCustomTheme(existing.id)
                    showCustomThemeDialog = false
                    editingCustomTheme = null
                    Toast.makeText(context, "'${existing.name}' teması silindi.", Toast.LENGTH_SHORT).show()
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

private enum class ThemeColorRole(val label: String) {
    ACCENT("Vurgu"),
    BACKGROUND("Arka Plan"),
    CARD("Kart"),
    TEXT("Yazı")
}

private fun colorToHexString(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

private fun colorToHsv(color: Color): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    return hsv
}

private fun hsvToComposeColor(h: Float, s: Float, v: Float): Color {
    return Color(AndroidColor.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
}

@Composable
private fun CustomThemeEditorDialog(
    initialSpec: CustomThemeSpec?,
    onDismiss: () -> Unit,
    onSave: (CustomThemeSpec) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val colors = LocalPlasmaColors.current
    val defaultName = initialSpec?.name ?: "Benim Temam"
    val defaultBg = initialSpec?.backgroundHex ?: "#141022"
    val defaultCard = initialSpec?.cardHex ?: "#241D3D"
    val defaultAccent = initialSpec?.accentHex ?: "#A855F7"
    val defaultText = initialSpec?.textHex ?: "#F8FAFC"

    var name by remember { mutableStateOf(defaultName) }
    var bgHex by remember { mutableStateOf(defaultBg) }
    var cardHex by remember { mutableStateOf(defaultCard) }
    var accentHex by remember { mutableStateOf(defaultAccent) }
    var textHex by remember { mutableStateOf(defaultText) }
    var isDarkMode by remember {
        mutableStateOf(parseHexColor(defaultBg, Color(0xFF141022)).luminance() < 0.5f)
    }
    var activeRole by remember { mutableStateOf(ThemeColorRole.ACCENT) }
    var colorIntensity by remember { mutableFloatStateOf(0.8f) }

    val previewBg = parseHexColor(bgHex, Color(0xFF141022))
    val previewCard = parseHexColor(cardHex, Color(0xFF241D3D))
    val previewAccent = parseHexColor(accentHex, Color(0xFFA855F7))
    val previewText = parseHexColor(textHex, Color(0xFFF8FAFC))

    val activeHex = when (activeRole) {
        ThemeColorRole.ACCENT -> accentHex
        ThemeColorRole.BACKGROUND -> bgHex
        ThemeColorRole.CARD -> cardHex
        ThemeColorRole.TEXT -> textHex
    }
    val activeColor = parseHexColor(activeHex, previewAccent)
    val initialHsv = remember(activeRole, activeHex) { colorToHsv(activeColor) }
    var hue by remember(activeRole) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(activeRole) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(activeRole) { mutableFloatStateOf(initialHsv[2]) }

    fun applyActiveColor(newColor: Color) {
        val hex = colorToHexString(newColor)
        when (activeRole) {
            ThemeColorRole.ACCENT -> accentHex = hex
            ThemeColorRole.BACKGROUND -> {
                bgHex = hex
                isDarkMode = newColor.luminance() < 0.5f
            }
            ThemeColorRole.CARD -> cardHex = hex
            ThemeColorRole.TEXT -> textHex = hex
        }
    }

    fun applyAppearanceMode(dark: Boolean) {
        isDarkMode = dark
        val accentHsv = colorToHsv(previewAccent)
        val h = accentHsv[0]
        if (dark) {
            bgHex = colorToHexString(hsvToComposeColor(h, 0.35f * colorIntensity, 0.12f))
            cardHex = colorToHexString(hsvToComposeColor(h, 0.30f * colorIntensity, 0.20f))
            textHex = "#F8FAFC"
        } else {
            bgHex = colorToHexString(hsvToComposeColor(h, 0.10f * colorIntensity, 0.97f))
            cardHex = colorToHexString(hsvToComposeColor(h, 0.14f * colorIntensity, 0.91f))
            textHex = "#18181B"
        }
        val refreshed = colorToHsv(
            parseHexColor(
                when (activeRole) {
                    ThemeColorRole.ACCENT -> accentHex
                    ThemeColorRole.BACKGROUND -> bgHex
                    ThemeColorRole.CARD -> cardHex
                    ThemeColorRole.TEXT -> textHex
                },
                previewAccent
            )
        )
        hue = refreshed[0]
        sat = refreshed[1]
        value = refreshed[2]
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(24.dp),
            color = colors.card,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Temanı özelleştir",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = previewAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Live Preview Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(previewBg)
                            .border(1.dp, previewAccent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(previewCard)
                                .padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(previewAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name.ifBlank { "Tema Önizleme" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = previewText
                                    )
                                )
                                Text(
                                    text = if (isDarkMode) "Koyu Tema" else "Açık Tema",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = previewText.copy(alpha = 0.7f)
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(previewAccent.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Aktif",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = previewAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Tema Adı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Görünüm (Dark / Light segmented toggle)
                    Text(
                        text = "Görünüm",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { applyAppearanceMode(true) },
                            color = if (isDarkMode) colors.card else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            border = if (isDarkMode) BorderStroke(1.dp, previewAccent.copy(alpha = 0.6f)) else null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.DarkMode,
                                    contentDescription = "Koyu",
                                    tint = if (isDarkMode) previewAccent else colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Koyu",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDarkMode) MaterialTheme.colorScheme.onSurface else colors.textSecondary
                                    )
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { applyAppearanceMode(false) },
                            color = if (!isDarkMode) colors.card else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            border = if (!isDarkMode) BorderStroke(1.dp, previewAccent.copy(alpha = 0.6f)) else null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LightMode,
                                    contentDescription = "Açık",
                                    tint = if (!isDarkMode) previewAccent else colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Açık",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isDarkMode) MaterialTheme.colorScheme.onSurface else colors.textSecondary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Renkler
                    Text(
                        text = "Renkler",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Gradient Palette Bar + Role Chips
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(previewBg, previewCard, previewAccent, previewText)
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ThemeColorRole.entries.forEach { role ->
                            val roleColor = when (role) {
                                ThemeColorRole.ACCENT -> previewAccent
                                ThemeColorRole.BACKGROUND -> previewBg
                                ThemeColorRole.CARD -> previewCard
                                ThemeColorRole.TEXT -> previewText
                            }
                            val selected = activeRole == role
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        activeRole = role
                                        val hsv = colorToHsv(roleColor)
                                        hue = hsv[0]
                                        sat = hsv[1]
                                        value = hsv[2]
                                    },
                                color = if (selected) previewAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) previewAccent else colors.border
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(roleColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = role.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Touch 2D Saturation-Value Canvas
                    TouchSvColorSquare(
                        hue = hue,
                        saturation = sat,
                        value = value,
                        onSvChanged = { newSat, newVal ->
                            sat = newSat
                            value = newVal
                            applyActiveColor(hsvToComposeColor(hue, newSat, newVal))
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Touch Hue Rainbow Bar
                    TouchHueBar(
                        hue = hue,
                        onHueChanged = { newHue ->
                            hue = newHue
                            applyActiveColor(hsvToComposeColor(newHue, sat, value))
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hex Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(activeColor)
                                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedTextField(
                            value = activeHex,
                            onValueChange = { raw ->
                                if (raw.length <= 7) {
                                    val normalized = raw.uppercase()
                                    when (activeRole) {
                                        ThemeColorRole.ACCENT -> accentHex = normalized
                                        ThemeColorRole.BACKGROUND -> bgHex = normalized
                                        ThemeColorRole.CARD -> cardHex = normalized
                                        ThemeColorRole.TEXT -> textHex = normalized
                                    }
                                    if (normalized.length == 7 && normalized.startsWith("#")) {
                                        val parsed = parseHexColor(normalized, activeColor)
                                        val hsv = colorToHsv(parsed)
                                        hue = hsv[0]
                                        sat = hsv[1]
                                        value = hsv[2]
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Kontroller: Renk Yoğunluğu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Renk Yoğunluğu",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = "${(colorIntensity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = previewAccent
                            )
                        )
                    }
                    Slider(
                        value = colorIntensity,
                        onValueChange = { intensity ->
                            colorIntensity = intensity
                            val aHsv = colorToHsv(previewAccent)
                            val bHsv = colorToHsv(previewBg)
                            val cHsv = colorToHsv(previewCard)
                            accentHex = colorToHexString(hsvToComposeColor(aHsv[0], (0.25f + 0.75f * intensity).coerceIn(0f, 1f), aHsv[2].coerceAtLeast(0.45f)))
                            bgHex = colorToHexString(hsvToComposeColor(bHsv[0], (intensity * if (isDarkMode) 0.55f else 0.18f).coerceIn(0f, 1f), bHsv[2]))
                            cardHex = colorToHexString(hsvToComposeColor(cHsv[0], (intensity * if (isDarkMode) 0.45f else 0.22f).coerceIn(0f, 1f), cHsv[2]))
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = previewAccent,
                            activeTrackColor = previewAccent
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Beni Şaşırt! & Sıfırla
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val randomHue = Random.nextFloat() * 360f
                                val accentS = (0.65f + Random.nextFloat() * 0.30f) * colorIntensity.coerceAtLeast(0.4f)
                                accentHex = colorToHexString(hsvToComposeColor(randomHue, accentS.coerceIn(0.3f, 1f), 0.92f))
                                if (isDarkMode) {
                                    bgHex = colorToHexString(hsvToComposeColor(randomHue, 0.35f * colorIntensity, 0.09f + Random.nextFloat() * 0.06f))
                                    cardHex = colorToHexString(hsvToComposeColor(randomHue, 0.30f * colorIntensity, 0.16f + Random.nextFloat() * 0.07f))
                                    textHex = "#F8FAFC"
                                } else {
                                    bgHex = colorToHexString(hsvToComposeColor(randomHue, 0.08f * colorIntensity, 0.97f))
                                    cardHex = colorToHexString(hsvToComposeColor(randomHue, 0.14f * colorIntensity, 0.91f))
                                    textHex = "#18181B"
                                }
                                val refreshed = colorToHsv(
                                    parseHexColor(
                                        when (activeRole) {
                                            ThemeColorRole.ACCENT -> accentHex
                                            ThemeColorRole.BACKGROUND -> bgHex
                                            ThemeColorRole.CARD -> cardHex
                                            ThemeColorRole.TEXT -> textHex
                                        },
                                        previewAccent
                                    )
                                )
                                hue = refreshed[0]
                                sat = refreshed[1]
                                value = refreshed[2]
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Beni Şaşırt!", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                name = defaultName
                                bgHex = defaultBg
                                cardHex = defaultCard
                                accentHex = defaultAccent
                                textHex = defaultText
                                isDarkMode = parseHexColor(defaultBg, Color(0xFF141022)).luminance() < 0.5f
                                colorIntensity = 0.8f
                                val refreshed = colorToHsv(
                                    parseHexColor(
                                        when (activeRole) {
                                            ThemeColorRole.ACCENT -> defaultAccent
                                            ThemeColorRole.BACKGROUND -> defaultBg
                                            ThemeColorRole.CARD -> defaultCard
                                            ThemeColorRole.TEXT -> defaultText
                                        },
                                        previewAccent
                                    )
                                )
                                hue = refreshed[0]
                                sat = refreshed[1]
                                value = refreshed[2]
                            },
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sıfırla")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Save / Delete / Cancel Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sil")
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("İptal")
                    }

                    Button(
                        onClick = {
                            val cleanName = name.trim().ifEmpty { "Custom Tema" }
                            val spec = CustomThemeSpec(
                                id = initialSpec?.id ?: "custom.${UUID.randomUUID().toString().take(8)}",
                                name = cleanName,
                                backgroundHex = if (bgHex.startsWith("#")) bgHex else "#$bgHex",
                                cardHex = if (cardHex.startsWith("#")) cardHex else "#$cardHex",
                                accentHex = if (accentHex.startsWith("#")) accentHex else "#$accentHex",
                                textHex = if (textHex.startsWith("#")) textHex else "#$textHex"
                            )
                            onSave(spec)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = previewAccent,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kaydet")
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchSvColorSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onSvChanged: (Float, Float) -> Unit
) {
    val pureHueColor = remember(hue) { hsvToComposeColor(hue, 1f, 1f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(165.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                    onSvChanged(s, v)
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                    onSvChanged(s, v)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            val cx = (saturation.coerceIn(0f, 1f)) * size.width
            val cy = (1f - value.coerceIn(0f, 1f)) * size.height
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = 12.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun TouchHueBar(
    hue: Float,
    onHueChanged: (Float) -> Unit
) {
    val rainbowColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newHue = ((offset.x / size.width).coerceIn(0f, 1f)) * 360f
                    onHueChanged(newHue)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val newHue = ((change.position.x / size.width).coerceIn(0f, 1f)) * 360f
                    onHueChanged(newHue)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(rainbowColors))
            val cx = (hue.coerceIn(0f, 360f) / 360f) * size.width
            val cy = size.height / 2f
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = 12.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}
