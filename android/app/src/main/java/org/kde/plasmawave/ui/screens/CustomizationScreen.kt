package org.kde.plasmawave.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File
import org.kde.plasmawave.R
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.*

private enum class CropTarget {
    HEADER_LOGO,
    LAUNCHER_ICON
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsState()
    val seekbarStyle by viewModel.seekbarStyle.collectAsState()
    val albumFrame by viewModel.albumFrame.collectAsState()
    val swipeToSkipEnabled by viewModel.swipeToSkipEnabled.collectAsState()
    val flowHeroCardEnabled by viewModel.flowHeroCardEnabled.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val flowLibraryPinned by viewModel.flowLibraryPinned.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val downloadsLibraryPinned by viewModel.downloadsLibraryPinned.collectAsState()
    val headerLogoStyle by viewModel.headerLogoStyle.collectAsState()
    val customLogoTimestamp by viewModel.customHeaderLogoTimestamp.collectAsState()
    val customLauncherTimestamp by viewModel.customLauncherIconTimestamp.collectAsState()
    val launcherIcon by viewModel.launcherIcon.collectAsState()
    val homeFeedType by viewModel.homeFeedType.collectAsState()
    val resumePlaybackEnabled by viewModel.resumePlaybackEnabled.collectAsState()
    val enabledHomeWidgets by viewModel.enabledHomeWidgets.collectAsState()
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    // Cropper Dialog State
    var selectedCropUri by remember { mutableStateOf<Uri?>(null) }
    var activeCropTarget by remember { mutableStateOf(CropTarget.HEADER_LOGO) }

    val customFile = remember(customLogoTimestamp) {
        File(context.filesDir, "custom_header_logo.png")
    }
    val customBitmap = remember(customLogoTimestamp) {
        if (customFile.exists()) {
            BitmapFactory.decodeFile(customFile.absolutePath)
        } else null
    }

    val customLauncherFile = remember(customLauncherTimestamp) {
        File(context.filesDir, LauncherIconManager.CUSTOM_LAUNCHER_ICON_FILE)
    }
    val customLauncherBitmap = remember(customLauncherTimestamp) {
        if (customLauncherFile.exists()) {
            BitmapFactory.decodeFile(customLauncherFile.absolutePath)
        } else null
    }

    // Image Picker for custom logo / launcher icon (supports PNG, JPG, WebP, GIF, etc.)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (isSupportedImageUri(context, uri)) {
                selectedCropUri = uri
            } else {
                Toast.makeText(
                    context,
                    "Seçilen dosya geçerli bir görsel formatında değil! Lütfen PNG, JPG veya WebP gibi bir görsel seçin.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (selectedCropUri != null) {
        val isLauncherTarget = activeCropTarget == CropTarget.LAUNCHER_ICON
        ImageCropperDialog(
            imageUri = selectedCropUri!!,
            targetFileName = if (isLauncherTarget) {
                LauncherIconManager.CUSTOM_LAUNCHER_ICON_FILE
            } else {
                "custom_header_logo.png"
            },
            titleText = if (isLauncherTarget) {
                "Telefon İkonunu Kırp ve Konumlandır"
            } else {
                "Logoyu Kırp ve Konumlandır"
            },
            onDismiss = { selectedCropUri = null },
            onCroppedSaved = {
                if (isLauncherTarget) {
                    viewModel.updateCustomLauncherIcon()
                    viewModel.setLauncherIcon(AppLauncherIcon.CUSTOM)
                    LauncherIconManager.pinOrUpdateCustomLauncherShortcut(context, requestPin = true)
                    Toast.makeText(
                        context,
                        "Özel telefon uygulama ikonu kaydedildi ve ana ekrana eklendi!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    viewModel.updateCustomHeaderLogo()
                    viewModel.setHeaderLogoStyle(HeaderLogoStyle.CUSTOM)
                    Toast.makeText(
                        context,
                        "Özel logo başarıyla kaydedildi ve uygulandı!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                selectedCropUri = null
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Özelleştirme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
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
            // 0. TELEFON UYGULAMA İKONU (BAŞLATICI)
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "TELEFON UYGULAMA İKONU (BAŞLATICI)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Telefonunuzun ana ekranında ve uygulama menüsünde görünecek logoyu seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(AppLauncherIcon.entries.size) { index ->
                val iconOption = AppLauncherIcon.entries[index]
                val isSelected = iconOption == launcherIcon

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (iconOption == AppLauncherIcon.CUSTOM && customLauncherBitmap == null) {
                                activeCropTarget = CropTarget.LAUNCHER_ICON
                                imagePickerLauncher.launch("image/*")
                            } else {
                                viewModel.setLauncherIcon(iconOption)
                                if (iconOption == AppLauncherIcon.CUSTOM) {
                                    LauncherIconManager.pinOrUpdateCustomLauncherShortcut(context, requestPin = true)
                                }
                                Toast.makeText(
                                    context,
                                    "${iconOption.title} telefon simgesi uygulandı!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primary else colors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (iconOption == AppLauncherIcon.CUSTOM && customLauncherBitmap == null) {
                                        activeCropTarget = CropTarget.LAUNCHER_ICON
                                        imagePickerLauncher.launch("image/*")
                                    } else {
                                        viewModel.setLauncherIcon(iconOption)
                                        if (iconOption == AppLauncherIcon.CUSTOM) {
                                            LauncherIconManager.pinOrUpdateCustomLauncherShortcut(context, requestPin = true)
                                        }
                                        Toast.makeText(
                                            context,
                                            "${iconOption.title} telefon simgesi uygulandı!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF232629)),
                                contentAlignment = Alignment.Center
                            ) {
                                when (iconOption) {
                                    AppLauncherIcon.DEFAULT -> {
                                        Image(
                                            painter = painterResource(iconOption.previewRes),
                                            contentDescription = iconOption.title,
                                            modifier = Modifier.size(40.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    AppLauncherIcon.CUSTOM -> {
                                        if (customLauncherBitmap != null) {
                                            Image(
                                                bitmap = customLauncherBitmap.asImageBitmap(),
                                                contentDescription = iconOption.title,
                                                modifier = Modifier.size(40.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.AddPhotoAlternate,
                                                contentDescription = null,
                                                tint = primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = iconOption.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = iconOption.subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seçili",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (iconOption == AppLauncherIcon.CUSTOM) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        activeCropTarget = CropTarget.LAUNCHER_ICON
                                        imagePickerLauncher.launch("image/*")
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primary.copy(alpha = 0.15f),
                                        contentColor = primary
                                    )
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (customLauncherBitmap != null) "Görsel Değiştir & Kırp" else "Görsel Yükle & Kırp",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (customLauncherBitmap != null) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.setLauncherIcon(AppLauncherIcon.CUSTOM)
                                            LauncherIconManager.pinOrUpdateCustomLauncherShortcut(context, requestPin = true)
                                            Toast.makeText(
                                                context,
                                                "Özel uygulama ikonu ana ekrana sabitlendi!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sabitle", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 1. Ana Sayfa Logosu (Üst Bar)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ANA SAYFA LOGOSU (ÜST BAR)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ana sayfanın üstünde gösterilecek klasik logoyu veya özel logonuzu seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(HeaderLogoStyle.entries.size) { index ->
                val style = HeaderLogoStyle.entries[index]
                val isSelected = style == headerLogoStyle

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setHeaderLogoStyle(style) }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primary else colors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.setHeaderLogoStyle(style) },
                                colors = RadioButtonDefaults.colors(selectedColor = primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))

                            // Preview Box
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                when (style) {
                                    HeaderLogoStyle.CUSTOM -> {
                                        if (customBitmap != null) {
                                            Image(
                                                bitmap = customBitmap.asImageBitmap(),
                                                contentDescription = style.title,
                                                modifier = Modifier.size(42.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.AddPhotoAlternate,
                                                contentDescription = null,
                                                tint = primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                    HeaderLogoStyle.ORIGINAL -> {
                                        Icon(
                                            Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = style.subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seçili",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // For CUSTOM option: button to pick & crop PNG
                        if (style == HeaderLogoStyle.CUSTOM) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    activeCropTarget = CropTarget.HEADER_LOGO
                                    imagePickerLauncher.launch("image/*")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primary.copy(alpha = 0.15f),
                                    contentColor = primary
                                )
                            ) {
                                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (customBitmap != null) "Görsel Değiştir, Kırp & Ayarla" else "Görsel Yükle, Kırp & Ayarla",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            // 2. ANA SAYFA ŞARKI ÖNERİLERİ BÖLÜMÜ
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ANA SAYFA ŞARKI ÖNERİLERİ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ana sayfadaki 'Senin için' bölümünün yerine ne gösterileceğini seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(HomeFeedType.entries.size) { index ->
                val feedOption = HomeFeedType.entries[index]
                val isSelected = feedOption == homeFeedType

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setHomeFeedType(feedOption) }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primary else colors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setHomeFeedType(feedOption) },
                            colors = RadioButtonDefaults.colors(selectedColor = primary)
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val icon = when (feedOption) {
                                HomeFeedType.FOR_YOU -> Icons.Default.Favorite
                                HomeFeedType.RECENTLY_PLAYED -> Icons.Default.History
                                HomeFeedType.MOST_PLAYED -> Icons.Default.TrendingUp
                            }
                            Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
                        }

                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = feedOption.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                text = feedOption.description,
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Seçili",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // KALDIĞIN YERDEN DEVAM ET & ANA SAYFA WİDGET'LARI
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "KALDIĞIN YERDEN DEVAM ET",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setResumePlaybackEnabled(!resumePlaybackEnabled) }
                            .padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Kaldığın Yerden Devam Et Kartı",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Ana sayfada son dinlediğiniz şarkıyı kaldığınız saniyeden tek tuşla devam ettirin",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }
                        Switch(
                            checked = resumePlaybackEnabled,
                            onCheckedChange = viewModel::setResumePlaybackEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = primary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ANA SAYFA WİDGET'LARI",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ana sayfaya eklemek veya kaldırmak istediğiniz widget'ları seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        HomeWidgetType.entries.forEachIndexed { idx, widget ->
                            val isEnabled = widget in enabledHomeWidgets
                            val wIcon = when (widget) {
                                HomeWidgetType.STATS -> Icons.Default.BarChart
                                HomeWidgetType.QUICK_ARTIST -> Icons.Default.PersonSearch
                                HomeWidgetType.QUICK_THEME -> Icons.Default.Palette
                                HomeWidgetType.LUCKY_PICK -> Icons.Default.Casino
                                HomeWidgetType.SLEEP_TIMER -> Icons.Default.Bedtime
                                HomeWidgetType.MOOD_MIX -> Icons.Default.AutoAwesome
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setHomeWidgetEnabled(widget, !isEnabled) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(wIcon, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = widget.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = widget.subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                }
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { viewModel.setHomeWidgetEnabled(widget, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = primary
                                    )
                                )
                            }
                            if (idx < HomeWidgetType.entries.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(color = colors.border.copy(alpha = 0.35f))
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // DİNAMİK ALBÜM RENKLERİ
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DİNAMİK ALBÜM KAPAĞI RENKLERİ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ColorLens,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dinamik Albüm Renkleri",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (dynamicColorEnabled) "Açık (Kapak rengine göre uyarlanır)" else "Kapalı (Tema rengi kullanılır)",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = dynamicColorEnabled,
                                onCheckedChange = viewModel::setDynamicColorEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Şarkıyı büyüttüğünüzde tam ekran oynatıcı arka planı, çalan şarkının albüm kapağındaki canlı renklere göre otomatik ayarlanır.",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Deezer Flow Yerleşimi
                Text(
                    text = "DEEZER FLOW YERLEŞİMİ (AI MÜZİK AKIŞI)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                Color(0xFF8B5CF6),
                                                Color(0xFFEC4899),
                                                Color(0xFFF97316)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AllInclusive,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Deezer Flow Konumları",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Flow'un nerelerde gösterileceğini özelleştirin",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // Switch 1: Ana Sayfa Büyük Afişi
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setFlowHeroCardEnabled(!flowHeroCardEnabled) }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ana Sayfa Afişi",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Ana sayfanın üstündeki renkli büyük Flow paneli",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = flowHeroCardEnabled,
                                onCheckedChange = viewModel::setFlowHeroCardEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = colors.border.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(6.dp))

                        // Switch 2: Hızlı Erişime Sabitle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setFlowQuickAccessPinned(!flowQuickAccessPinned) }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hızlı Erişime Sabitle",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Ana sayfadaki 'Hızlı erişim' listesine Flow kartı ekle",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = flowQuickAccessPinned,
                                onCheckedChange = viewModel::setFlowQuickAccessPinned,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = colors.border.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(6.dp))

                        // Switch 3: Kitaplığa Sabitle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setFlowLibraryPinned(!flowLibraryPinned) }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kitaplığa Sabitle",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Kitaplığım sekmesinde çalma listelerinin başında göster",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = flowLibraryPinned,
                                onCheckedChange = viewModel::setFlowLibraryPinned,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // İndirilenler Sabitleme
                Text(
                    text = "İNDİRİLENLER SABİTLEME",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 1. Hızlı Erişime (Ana Sayfaya) Sabitle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DownloadDone,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ana Sayfaya (Hızlı Erişim) Sabitle",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Ana sayfadaki Hızlı Erişim ızgarasında İndirilen Müzikler kartını göster",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = downloadsQuickAccessPinned,
                                onCheckedChange = viewModel::setDownloadsQuickAccessPinned,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = colors.divider)
                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. Kitaplığa Sabitle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0EA5E9).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    tint = Color(0xFF0EA5E9),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kitaplığa Sabitle",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Kitaplık sekmesinde (Tümü & Sabitlenenler) İndirilen Müzikler koleksiyonunu göster",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = downloadsLibraryPinned,
                                onCheckedChange = viewModel::setDownloadsLibraryPinned,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Albüm Kapağı Hareketi
                Text(
                    text = "ALBÜM KAPAĞI HAREKETİ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kapağı Kaydırarak Şarkı Değiştir",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (swipeToSkipEnabled) "Açık (Sağa/sola kaydırarak parça değiştir)" else "Kapalı",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = swipeToSkipEnabled,
                                onCheckedChange = viewModel::setSwipeToSkipEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Oynatıcı ekranında albüm kapağını sola kaydırarak sonraki parçaya, sağa kaydırarak önceki parçaya animasyonlu yumuşak geçiş yapabilirsiniz.",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Albüm Kapağı Çerçevesi
                Text(
                    text = "ALBÜM KAPAĞI ÇERÇEVESİ (5 MODEL)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Oynatıcıda albüm kapağının çerçevesini ve sunum tarzını seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 5 Farklı Albüm Kapağı Çerçevesi
            items(AlbumCoverFrame.entries.size) { index ->
                val frameOption = AlbumCoverFrame.entries[index]
                val isSelected = frameOption == albumFrame

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setAlbumFrame(frameOption) }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primary else colors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setAlbumFrame(frameOption) },
                            colors = RadioButtonDefaults.colors(selectedColor = primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = frameOption.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                text = frameOption.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        // Mini visual frame preview
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            ArtworkFramedView(
                                artworkUrl = null,
                                frame = frameOption,
                                accentColor = primary,
                                isPlaying = isSelected,
                                size = 52.dp
                            )
                        }
                    }
                }
            }

            // Oynatıcı İlerleme Çubuğu Deseni (6 Model, Nyan Cat dahil)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "OYNATICI İLERLEME ÇUBUĞU DESENİ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tam ekran oynatıcıdaki kaydırma çubuğunun desenini ve şeklini seçin:",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            items(SeekbarStyle.entries.size) { index ->
                val style = SeekbarStyle.entries[index]
                val isSelected = style == seekbarStyle

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setSeekbarStyle(style) }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primary else colors.border,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.setSeekbarStyle(style) },
                                colors = RadioButtonDefaults.colors(selectedColor = primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = style.subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seçili",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Live visual preview of this seekbar style
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            var previewPos by remember { mutableLongStateOf(84000L) }
                            CustomSeekbar(
                                positionMs = previewPos,
                                durationMs = 210000L,
                                style = style,
                                primaryColor = if (isSelected) primary else primary.copy(alpha = 0.75f),
                                trackColor = colors.border.copy(alpha = 0.6f),
                                onSeekTo = { previewPos = it },
                                isPlaying = true,
                                showTimeLabels = true
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
