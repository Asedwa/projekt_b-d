package org.kde.plasmawave.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.roundToInt
import org.kde.plasmawave.R
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.QuickCardContentMode
import org.kde.plasmawave.ui.QuickCardLayout
import org.kde.plasmawave.ui.QuickCardShape
import org.kde.plasmawave.ui.QuickCardStyle
import org.kde.plasmawave.ui.components.*

private enum class CropTarget {
    HEADER_LOGO,
    LAUNCHER_ICON
}

private enum class CardEditTarget(val title: String) {
    BOTH("Birlikte"),
    HOME("Ana Sayfa"),
    LIBRARY("Kitaplık")
}

enum class CustomizationCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    IDENTITY(
        title = "Uygulama Kimliği & Logo",
        subtitle = "Telefon uygulama ikonu ve ana sayfa üst logosu",
        icon = Icons.Default.Apps
    ),
    HOME_AND_SHORTCUTS(
        title = "Ana Sayfa & Hızlı Erişim",
        subtitle = "Hızlı erişim kart stilleri, tuş boyutları, öneriler ve widget'lar",
        icon = Icons.Default.Home
    ),
    PLAYER_AND_VISUALS(
        title = "Oynatıcı & Görsel Efektler",
        subtitle = "Dinamik renkler, kapak çerçevesi ve ilerleme çubuğu stilleri",
        icon = Icons.Default.MusicNote
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialCategory: CustomizationCategory? = null
) {
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsState()
    val ambientBackgroundEnabled by viewModel.ambientBackgroundEnabled.collectAsState()
    val ambientBackdropStyle by viewModel.ambientBackdropStyle.collectAsState()
    val seekbarStyle by viewModel.seekbarStyle.collectAsState()
    val albumFrame by viewModel.albumFrame.collectAsState()
    val swipeToSkipEnabled by viewModel.swipeToSkipEnabled.collectAsState()
    val pinnedPlayerActions by viewModel.pinnedPlayerActions.collectAsState()
    val flowHeroCardEnabled by viewModel.flowHeroCardEnabled.collectAsState()
    val flowQuickAccessPinned by viewModel.flowQuickAccessPinned.collectAsState()
    val flowLibraryPinned by viewModel.flowLibraryPinned.collectAsState()
    val downloadsQuickAccessPinned by viewModel.downloadsQuickAccessPinned.collectAsState()
    val downloadsLibraryPinned by viewModel.downloadsLibraryPinned.collectAsState()
    val headerLogoStyle by viewModel.headerLogoStyle.collectAsState()
    val customLogoTimestamp by viewModel.customHeaderLogoTimestamp.collectAsState()
    val customLauncherTimestamp by viewModel.customLauncherIconTimestamp.collectAsState()
    val launcherIcon by viewModel.launcherIcon.collectAsState()
    val homeFeedTypes by viewModel.homeFeedTypes.collectAsState()
    val resumePlaybackEnabled by viewModel.resumePlaybackEnabled.collectAsState()
    val enabledHomeWidgets by viewModel.enabledHomeWidgets.collectAsState()
    val homeEditHoldSeconds by viewModel.homeEditHoldSeconds.collectAsState()
    val quickCardStyle by viewModel.quickCardStyle.collectAsState()
    val quickCardScale by viewModel.quickCardScale.collectAsState()
    val quickIconScale by viewModel.quickIconScale.collectAsState()
    val quickCardShape by viewModel.quickCardShape.collectAsState()
    val quickCardLayout by viewModel.quickCardLayout.collectAsState()
    val quickCardContentMode by viewModel.quickCardContentMode.collectAsState()
    val librarySyncWithQuick by viewModel.librarySyncWithQuick.collectAsState()
    val libraryCardStyle by viewModel.libraryCardStyle.collectAsState()
    val libraryCardScale by viewModel.libraryCardScale.collectAsState()
    val libraryIconScale by viewModel.libraryIconScale.collectAsState()
    val libraryCardShape by viewModel.libraryCardShape.collectAsState()
    val libraryCardLayout by viewModel.libraryCardLayout.collectAsState()
    val libraryCardContentMode by viewModel.libraryCardContentMode.collectAsState()
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    var activeCategoryName by rememberSaveable(initialCategory) { mutableStateOf(initialCategory?.name) }
    val activeCategory = activeCategoryName?.let { name ->
        CustomizationCategory.entries.firstOrNull { it.name == name }
    }

    BackHandler(enabled = activeCategory != null && initialCategory == null) {
        activeCategoryName = null
    }

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
                title = { Text(activeCategory?.title ?: "Özelleştirme") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeCategory != null && initialCategory == null) {
                            activeCategoryName = null
                        } else {
                            onBack()
                        }
                    }) {
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
        AnimatedContent(
            targetState = activeCategory,
            label = "customizationCategory"
        ) { category ->
            if (category == null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (colors.legacyUiMode) "KATEGORİLER" else "Kategoriler",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textSecondary
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    items(CustomizationCategory.entries.size) { index ->
                        val itemCategory = CustomizationCategory.entries[index]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { activeCategoryName = itemCategory.name },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.card)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (colors.legacyUiMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(primary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = itemCategory.icon,
                                            contentDescription = null,
                                            tint = primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = itemCategory.icon,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = itemCategory.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = itemCategory.subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.textSecondary
                                        )
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = colors.textSecondary
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                ) {
                    if (category == CustomizationCategory.IDENTITY) {
                        // 0. TELEFON UYGULAMA İKONU (BAŞLATICI)
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = sectionHeading("TELEFON UYGULAMA İKONU (BAŞLATICI)", colors.legacyUiMode),
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
                    text = sectionHeading("ANA SAYFA LOGOSU (ÜST BAR)", colors.legacyUiMode),
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
                                        Image(
                                            painter = painterResource(R.drawable.ic_header_logo),
                                            contentDescription = null,
                                            modifier = Modifier.size(34.dp),
                                            contentScale = ContentScale.Fit,
                                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(primary)
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

            item {
                // end of icon picker
            }

                    }

                    if (category == CustomizationCategory.HOME_AND_SHORTCUTS) {
                        // HIZLI ERİŞİM & KİTAPLIK KART AYARLARI
                        item {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = sectionHeading("HIZLI ERİŞİM & KİTAPLIK KART AYARLARI", colors.legacyUiMode),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ana sayfa ve kitaplıktaki kartların görünümünü birlikte veya ayrı ayrı özelleştirin:",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            var editTargetName by rememberSaveable {
                                mutableStateOf(if (librarySyncWithQuick) CardEditTarget.BOTH.name else CardEditTarget.HOME.name)
                            }
                            val editTarget = CardEditTarget.entries.firstOrNull { it.name == editTargetName } ?: CardEditTarget.BOTH
                            val editingLibraryOnly = editTarget == CardEditTarget.LIBRARY
                            val activeCardStyle = if (editingLibraryOnly) libraryCardStyle else quickCardStyle
                            val activeCardScale = if (editingLibraryOnly) libraryCardScale else quickCardScale
                            val activeIconScale = if (editingLibraryOnly) libraryIconScale else quickIconScale
                            val activeCardShape = if (editingLibraryOnly) libraryCardShape else quickCardShape
                            val activeCardLayout = if (editingLibraryOnly) libraryCardLayout else quickCardLayout
                            val activeContentMode = if (editingLibraryOnly) libraryCardContentMode else quickCardContentMode

                            val applyCardStyle: (QuickCardStyle) -> Unit = { style ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickCardStyle(style)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryCardStyle(style)
                            }
                            val applyCardScale: (Float) -> Unit = { scale ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickCardScale(scale)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryCardScale(scale)
                            }
                            val applyIconScale: (Float) -> Unit = { scale ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickIconScale(scale)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryIconScale(scale)
                            }
                            val applyCardShape: (QuickCardShape) -> Unit = { shape ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickCardShape(shape)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryCardShape(shape)
                            }
                            val applyCardLayout: (QuickCardLayout) -> Unit = { layout ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickCardLayout(layout)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryCardLayout(layout)
                            }
                            val applyContentMode: (QuickCardContentMode) -> Unit = { mode ->
                                if (editTarget != CardEditTarget.LIBRARY) viewModel.setQuickCardContentMode(mode)
                                if (editTarget != CardEditTarget.HOME) viewModel.setLibraryCardContentMode(mode)
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.card)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Hedef Seçici: Birlikte / Ana Sayfa / Kitaplık
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CardEditTarget.entries.forEach { targetOption ->
                                            val selected = editTarget == targetOption
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (selected) primary.copy(alpha = 0.16f)
                                                        else MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                                                    )
                                                    .border(
                                                        width = if (selected) 1.5.dp else 1.dp,
                                                        color = if (selected) primary else colors.border.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable {
                                                        editTargetName = targetOption.name
                                                        if (targetOption == CardEditTarget.BOTH) {
                                                            viewModel.setLibrarySyncWithQuick(true)
                                                            viewModel.setLibraryCardStyle(quickCardStyle)
                                                            viewModel.setLibraryCardScale(quickCardScale)
                                                            viewModel.setLibraryIconScale(quickIconScale)
                                                            viewModel.setLibraryCardShape(quickCardShape)
                                                            viewModel.setLibraryCardLayout(quickCardLayout)
                                                            viewModel.setLibraryCardContentMode(quickCardContentMode)
                                                        } else {
                                                            viewModel.setLibrarySyncWithQuick(false)
                                                        }
                                                    }
                                                    .padding(vertical = 9.dp, horizontal = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = targetOption.title,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (selected) primary else MaterialTheme.colorScheme.onBackground
                                                    ),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Canlı çoklu kart önizlemesi (HomeScreen / LibraryScreen ızgara düzeniyle birebir)
                                    val composeShape = activeCardShape.toComposeShape()
                                    val isCircleShape = activeCardShape == QuickCardShape.CIRCLE
                                    val previewItems = remember {
                                        listOf(
                                            Triple("Deezer Flow", Icons.Default.AllInclusive, listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316))),
                                            Triple("İndirilenler", Icons.Default.DownloadDone, listOf(Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFF8B5CF6))),
                                            Triple("Sevdiğim", Icons.Default.Favorite, listOf(Color(0xFFEF5466), Color(0xFF7B63DC), Color(0xFFE993BA))),
                                            Triple("Keşfet", Icons.Default.Explore, listOf(Color(0xFF6366F1), Color(0xFF06B6D4), Color(0xFF10B981))),
                                            Triple("Chill Mix", Icons.Default.Headphones, listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFEC4899))),
                                            Triple("Liste", Icons.Default.QueueMusic, listOf(Color(0xFF14B8A6), Color(0xFF3B82F6), Color(0xFF6366F1)))
                                        )
                                    }

                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                                            .padding(12.dp)
                                    ) {
                                        val containerMaxWidth = maxWidth
                                        val isHorizontalLayout = activeContentMode != QuickCardContentMode.IMAGE_ONLY &&
                                            activeCardLayout == QuickCardLayout.COMPACT_HORIZONTAL
                                        val isImageOnlyMode = activeContentMode == QuickCardContentMode.IMAGE_ONLY
                                        val isTitleOnlyMode = activeContentMode == QuickCardContentMode.TITLE_ONLY
                                        val previewColumns = if (isHorizontalLayout) {
                                            when {
                                                activeCardScale <= 0.78f -> 3
                                                activeCardScale >= 1.18f -> 1
                                                else -> 2
                                            }
                                        } else {
                                            val basePreviewCardSize = (containerMaxWidth - 16.dp) / 3
                                            val previewCardSize = (basePreviewCardSize * activeCardScale).coerceIn(44.dp, 136.dp)
                                            ((containerMaxWidth + 8.dp) / (previewCardSize + 8.dp)).toInt().coerceIn(2, 6)
                                        }
                                        val previewRows = previewItems.chunked(previewColumns)

                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = sectionHeading("CANLI ÖNİZLEME", colors.legacyUiMode),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = colors.textSecondary
                                                    )
                                                )
                                                Text(
                                                    text = "$previewColumns sütun yan yana",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (colors.legacyUiMode) primary else colors.textSecondary
                                                    )
                                                )
                                            }

                                            if (isHorizontalLayout) {
                                                val previewHeight = (54.dp * activeCardScale).coerceIn(42.dp, 72.dp)
                                                previewRows.forEach { rowItems ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        rowItems.forEach { (itemTitle, itemIcon, gradColors) ->
                                                            Box(
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .height(previewHeight)
                                                                    .clip(composeShape)
                                                                    .background(colors.card)
                                                                    .border(1.dp, colors.border.copy(alpha = 0.6f), composeShape)
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    if (!isTitleOnlyMode) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(previewHeight)
                                                                                .clip(composeShape)
                                                                                .background(
                                                                                    if (activeCardStyle == QuickCardStyle.VIBRANT_GRADIENT) {
                                                                                        Brush.linearGradient(gradColors)
                                                                                    } else {
                                                                                        Brush.verticalGradient(
                                                                                            listOf(primary.copy(alpha = 0.18f), primary.copy(alpha = 0.12f))
                                                                                        )
                                                                                    }
                                                                                ),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = itemIcon,
                                                                                contentDescription = null,
                                                                                tint = if (activeCardStyle == QuickCardStyle.VIBRANT_GRADIENT) Color.White else primary,
                                                                                modifier = Modifier.size((22.dp * (previewHeight / 54.dp) * activeIconScale).coerceIn(13.dp, 34.dp))
                                                                            )
                                                                        }
                                                                    }
                                                                    Text(
                                                                        text = itemTitle,
                                                                        style = if (previewColumns >= 3) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = MaterialTheme.colorScheme.onBackground,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .padding(horizontal = if (isTitleOnlyMode) 12.dp else 8.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        repeat(previewColumns - rowItems.size) {
                                                            Spacer(Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            } else {
                                                val basePreviewCardSize = (containerMaxWidth - 16.dp) / 3
                                                val previewCardSize = (basePreviewCardSize * activeCardScale).coerceIn(44.dp, 136.dp)
                                                previewRows.forEach { rowItems ->
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        rowItems.forEach { (itemTitle, itemIcon, gradColors) ->
                                                            val cardRatio = (previewCardSize / 104.dp).coerceIn(0.45f, 1.3f)
                                                            val previewIconBoxSize = (40.dp * cardRatio * activeIconScale).coerceIn(18.dp, 58.dp)
                                                            val previewIconSize = (20.dp * cardRatio * activeIconScale).coerceIn(11.dp, 34.dp)
                                                            val previewGradientIconSize = (28.dp * cardRatio * activeIconScale).coerceIn(14.dp, 48.dp)

                                                            val previewIconOffset = if (isImageOnlyMode) 0.dp else (-6).dp

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(previewCardSize)
                                                                    .clip(composeShape)
                                                                    .then(
                                                                        if (!isTitleOnlyMode && activeCardStyle == QuickCardStyle.VIBRANT_GRADIENT) {
                                                                            Modifier.background(Brush.linearGradient(gradColors))
                                                                        } else {
                                                                            Modifier
                                                                                .background(
                                                                                    Brush.verticalGradient(
                                                                                        listOf(primary.copy(alpha = 0.24f), colors.card)
                                                                                    )
                                                                                )
                                                                                .border(1.dp, colors.border.copy(alpha = 0.65f), composeShape)
                                                                        }
                                                                    )
                                                            ) {
                                                                if (isTitleOnlyMode) {
                                                                    Text(
                                                                        text = itemTitle,
                                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                                            color = MaterialTheme.colorScheme.onBackground,
                                                                            fontWeight = FontWeight.Bold
                                                                        ),
                                                                        textAlign = TextAlign.Center,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                        modifier = Modifier
                                                                            .align(Alignment.Center)
                                                                            .padding(horizontal = 6.dp)
                                                                    )
                                                                } else {
                                                                    if (activeCardStyle == QuickCardStyle.VIBRANT_GRADIENT) {
                                                                        if (!isImageOnlyMode) {
                                                                            Box(
                                                                                modifier = Modifier
                                                                                    .fillMaxSize()
                                                                                    .background(
                                                                                        Brush.verticalGradient(
                                                                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                                                                        )
                                                                                    )
                                                                            )
                                                                        }
                                                                        Icon(
                                                                            imageVector = itemIcon,
                                                                            contentDescription = null,
                                                                            tint = Color.White,
                                                                            modifier = Modifier
                                                                                .size(previewGradientIconSize)
                                                                                .align(Alignment.Center)
                                                                                .offset(y = previewIconOffset)
                                                                        )
                                                                    } else {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(previewIconBoxSize)
                                                                                .align(Alignment.Center)
                                                                                .offset(y = previewIconOffset)
                                                                                .clip(CircleShape)
                                                                                .background(primary.copy(alpha = 0.14f)),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = itemIcon,
                                                                                contentDescription = null,
                                                                                tint = primary,
                                                                                modifier = Modifier.size(previewIconSize)
                                                                            )
                                                                        }
                                                                    }
                                                                    if (!isImageOnlyMode) {
                                                                        Text(
                                                                            text = itemTitle,
                                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                                color = if (activeCardStyle == QuickCardStyle.VIBRANT_GRADIENT) {
                                                                                    Color.White
                                                                                } else {
                                                                                    MaterialTheme.colorScheme.onBackground
                                                                                },
                                                                                fontWeight = FontWeight.SemiBold
                                                                            ),
                                                                            textAlign = if (isCircleShape) TextAlign.Center else TextAlign.Start,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis,
                                                                            modifier = if (isCircleShape) {
                                                                                Modifier
                                                                                    .align(Alignment.BottomCenter)
                                                                                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                                                                            } else {
                                                                                Modifier
                                                                                    .align(Alignment.BottomStart)
                                                                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                                                            }
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

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "Kart Düzeni",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QuickCardLayout.entries.forEach { layoutOption ->
                                            val selected = activeCardLayout == layoutOption
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (selected) primary.copy(alpha = 0.16f)
                                                        else MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                                                    )
                                                    .border(
                                                        width = if (selected) 1.5.dp else 1.dp,
                                                        color = if (selected) primary else colors.border.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { applyCardLayout(layoutOption) }
                                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = layoutOption.title,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (selected) primary else MaterialTheme.colorScheme.onBackground
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "İçerik Modu",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QuickCardContentMode.entries.forEach { modeOption ->
                                            val selected = activeContentMode == modeOption
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (selected) primary.copy(alpha = 0.16f)
                                                        else MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                                                    )
                                                    .border(
                                                        width = if (selected) 1.5.dp else 1.dp,
                                                        color = if (selected) primary else colors.border.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { applyContentMode(modeOption) }
                                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = modeOption.title,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (selected) primary else MaterialTheme.colorScheme.onBackground
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "Kart Şekli",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QuickCardShape.entries.chunked(2).forEach { shapeRow ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                shapeRow.forEach { shapeOption ->
                                                    val selected = activeCardShape == shapeOption
                                                    Row(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(
                                                                if (selected) primary.copy(alpha = 0.16f)
                                                                else MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                                                            )
                                                            .border(
                                                                width = if (selected) 1.5.dp else 1.dp,
                                                                color = if (selected) primary else colors.border.copy(alpha = 0.4f),
                                                                shape = RoundedCornerShape(10.dp)
                                                            )
                                                            .clickable { applyCardShape(shapeOption) }
                                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(18.dp)
                                                                .clip(shapeOption.toComposeShape())
                                                                .background(
                                                                    if (selected) primary else colors.textSecondary.copy(alpha = 0.35f)
                                                                )
                                                        )
                                                        Text(
                                                            text = shapeOption.title,
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (selected) primary else MaterialTheme.colorScheme.onBackground
                                                            ),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "Kart Görünüm Stili",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            QuickCardStyle.THEME_ADAPTIVE to "Temaya Uyumlu",
                                            QuickCardStyle.VIBRANT_GRADIENT to "Renkli Gradyan"
                                        ).forEach { (styleOption, label) ->
                                            val selected = activeCardStyle == styleOption
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (selected) primary.copy(alpha = 0.16f)
                                                        else MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                                                    )
                                                    .border(
                                                        width = if (selected) 1.5.dp else 1.dp,
                                                        color = if (selected) primary else colors.border.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { applyCardStyle(styleOption) }
                                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (selected) primary else MaterialTheme.colorScheme.onBackground
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Kart / Tuş Boyutu Slider
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Tuş / Kart Boyutu",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${(activeCardScale * 100).roundToInt()}%",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = primary
                                                )
                                            )
                                            if (kotlin.math.abs(activeCardScale - 1.0f) > 0.01f) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Sıfırla",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = colors.textSecondary,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable { applyCardScale(1.0f) }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Slider(
                                        value = activeCardScale,
                                        onValueChange = { applyCardScale(it) },
                                        valueRange = 0.50f..1.30f
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Simge Boyutu Slider
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Simge Boyutu",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${(activeIconScale * 100).roundToInt()}%",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = primary
                                                )
                                            )
                                            if (kotlin.math.abs(activeIconScale - 1.0f) > 0.01f) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Sıfırla",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = colors.textSecondary,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable { applyIconScale(1.0f) }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Slider(
                                        value = activeIconScale,
                                        onValueChange = { applyIconScale(it) },
                                        valueRange = 0.70f..1.40f
                                    )
                                }
                            }
                        }

                        // 2. ANA SAYFA ŞARKI ÖNERİLERİ BÖLÜMÜ
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = sectionHeading("ANA SAYFA ŞARKI ÖNERİLERİ", colors.legacyUiMode),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ana sayfada gösterilecek şarkı öneri bölümlerini seçin (birden fazla seçebilirsiniz):",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(HomeFeedType.entries.size) { index ->
                val feedOption = HomeFeedType.entries[index]
                val isSelected = feedOption in homeFeedTypes

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.toggleHomeFeedType(feedOption) }
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
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { viewModel.toggleHomeFeedType(feedOption) },
                            colors = CheckboxDefaults.colors(checkedColor = primary)
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
                    text = sectionHeading("KALDIĞIN YERDEN DEVAM ET", colors.legacyUiMode),
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
                    text = sectionHeading("DÜZENLEME MODU BASILI TUTMA SÜRESİ", colors.legacyUiMode),
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
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Basılı Tutma Süresi",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Ana sayfa düzenleme moduna geçmek için en az 3 sn, en fazla 10 sn",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Text(
                                text = "$homeEditHoldSeconds sn",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = primary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = homeEditHoldSeconds.toFloat(),
                            onValueChange = { viewModel.setHomeEditHoldSeconds(it.toInt().coerceIn(3, 10)) },
                            valueRange = 3f..10f,
                            steps = 6
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = sectionHeading("ANA SAYFA WİDGET'LARI", colors.legacyUiMode),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ana sayfada eklemek/kaldırmak istediğiniz widget'ları seçin (ayrıca Ana Sayfa'da herhangi bir karta basılı tutarak sürükle-bırak ve stil düzenlemesi yapabilirsiniz):",
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
                                HomeWidgetType.RECENT_TRACKS -> Icons.Default.History
                                HomeWidgetType.LISTENING_SUMMARY -> Icons.Default.Speed
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

            }

            if (category == CustomizationCategory.PLAYER_AND_VISUALS) {
                // DİNAMİK ALBÜM RENKLERİ
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sectionHeading("DİNAMİK ALBÜM KAPAĞI RENKLERİ", colors.legacyUiMode),
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

                // --- 1b. Rust Ambient Animated Backdrop Card ---
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hareketli Ortam Arka Planı",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (ambientBackgroundEnabled) "Açık (${ambientBackdropStyle.title} • Rust motoru)" else "Kapalı (Düz tema arka planı)",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Switch(
                                checked = ambientBackgroundEnabled,
                                onCheckedChange = viewModel::setAmbientBackgroundEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = primary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Tema renklerinizden üretilen hafif hareketli ışık efektlerini seçin. Ekstra bitmap kullanmadan doğrudan Rust matematik motoruyla çizildiği için düşük bellek ve pil tüketir.",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        var showFullAmbientPreview by remember { mutableStateOf(false) }
                        if (showFullAmbientPreview) {
                            AmbientBackdropPreviewDialog(
                                initialStyle = ambientBackdropStyle,
                                onApply = { selected ->
                                    viewModel.setAmbientBackdropStyle(selected)
                                    if (!ambientBackgroundEnabled) {
                                        viewModel.setAmbientBackgroundEnabled(true)
                                    }
                                    showFullAmbientPreview = false
                                },
                                onDismiss = { showFullAmbientPreview = false }
                            )
                        }
                        val previewTransition = rememberInfiniteTransition(label = "ambientPreviewShared")
                        val sharedPreviewPhase by previewTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 22_000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "sharedPreviewPhase"
                        )
                        Surface(
                            onClick = { showFullAmbientPreview = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(168.dp)
                                .border(
                                    width = 1.dp,
                                    color = primary.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AmbientBackdropLayer(
                                    style = ambientBackdropStyle,
                                    phaseOverride = sharedPreviewPhase,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Simulated PlasmaWave mini UI overlay
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = colors.card.copy(alpha = 0.78f)
                                        ) {
                                            Text(
                                                text = "Canlı Önizleme • ${ambientBackdropStyle.title}",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { showFullAmbientPreview = true },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Fullscreen,
                                                contentDescription = "Tam ekran önizle",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Tam Ekran", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        repeat(2) { idx ->
                                            Surface(
                                                modifier = Modifier.weight(1f).height(44.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                color = colors.card.copy(alpha = 0.72f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(primary.copy(alpha = if (idx == 0) 0.35f else 0.22f))
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Column {
                                                        Box(
                                                            modifier = Modifier
                                                                .width(58.dp)
                                                                .height(8.dp)
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                                                        )
                                                        Spacer(Modifier.height(4.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .width(38.dp)
                                                                .height(6.dp)
                                                                .clip(RoundedCornerShape(3.dp))
                                                                .background(colors.textSecondary.copy(alpha = 0.5f))
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().height(34.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.card.copy(alpha = 0.85f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Şimdi Çalınıyor • Örnek Parça",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1
                                            )
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AmbientBackdropStyle.entries.chunked(2).forEach { rowStyles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowStyles.forEach { styleOption ->
                                        val isSelected = ambientBackgroundEnabled && ambientBackdropStyle == styleOption
                                        Surface(
                                            onClick = { viewModel.setAmbientBackdropStyle(styleOption) },
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.background,
                                            modifier = Modifier
                                                .weight(1f)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) primary else colors.border.copy(alpha = 0.45f),
                                                    shape = RoundedCornerShape(14.dp)
                                                )
                                        ) {
                                            Column {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(64.dp)
                                                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                                                        .background(MaterialTheme.colorScheme.background)
                                                ) {
                                                    AmbientBackdropLayer(
                                                        style = styleOption,
                                                        phaseOverride = sharedPreviewPhase,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                    if (isSelected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(6.dp)
                                                                .size(20.dp)
                                                                .clip(CircleShape)
                                                                .background(primary),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                                modifier = Modifier.size(13.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                    Text(
                                                        text = styleOption.title,
                                                        style = MaterialTheme.typography.labelLarge.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isSelected) primary else MaterialTheme.colorScheme.onBackground
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = styleOption.subtitle,
                                                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
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

            if (category == CustomizationCategory.HOME_AND_SHORTCUTS) {
                item {
                // Deezer Flow Yerleşimi
                Text(
                    text = sectionHeading("DEEZER FLOW YERLEŞİMİ (AI MÜZİK AKIŞI)", colors.legacyUiMode),
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
                    text = sectionHeading("İNDİRİLENLER SABİTLEME", colors.legacyUiMode),
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

                }
            }

            if (category == CustomizationCategory.PLAYER_AND_VISUALS) {
                item {
                // Albüm Kapağı Hareketi
                Text(
                    text = sectionHeading("ALBÜM KAPAĞI HAREKETİ", colors.legacyUiMode),
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
                    text = sectionHeading("ALBÜM KAPAĞI ÇERÇEVESİ (5 MODEL)", colors.legacyUiMode),
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
                    text = sectionHeading("OYNATICI İLERLEME ÇUBUĞU DESENİ", colors.legacyUiMode),
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
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = sectionHeading("OYNATICI ALT CEP TUŞLARI (2 SABİT TUŞ)", colors.legacyUiMode),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                    )
                    if (pinnedPlayerActions != PlayerActionButton.DEFAULT_PINNED) {
                        TextButton(
                            onClick = { viewModel.resetPinnedPlayerActions() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Varsayılana Sıfırla",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Oynatıcı altındaki cepte her zaman dışarıda kalacak 2 tuşu seçin. Kalan 5 tuş cebi yukarı kaydırdığınızda açılır.",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        PlayerActionButton.entries.forEachIndexed { idx, action ->
                            val pinnedIndex = pinnedPlayerActions.indexOf(action)
                            val isPinned = pinnedIndex >= 0
                            val slotLabel = when (pinnedIndex) {
                                0 -> "Açıkta (Sol)"
                                1 -> "Açıkta (Sağ)"
                                else -> "Cep içinde"
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.togglePinnedPlayerAction(action) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPinned) primary.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.title,
                                        tint = if (isPinned) primary else colors.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = action.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isPinned) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isPinned) primary else MaterialTheme.colorScheme.onBackground
                                        )
                                    )
                                    Text(
                                        text = slotLabel,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (isPinned) primary.copy(alpha = 0.85f) else colors.textSecondary
                                        )
                                    )
                                }
                                if (isPinned) {
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
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                            if (idx < PlayerActionButton.entries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = colors.border.copy(alpha = 0.5f)
                                )
                            }
                        }
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
    }
}

private fun sectionHeading(raw: String, legacyUi: Boolean): String {
    if (legacyUi) return raw
    val trLocale = java.util.Locale("tr", "TR")
    return raw.lowercase(trLocale).replaceFirstChar { it.titlecase(trLocale) }
}

@Composable
private fun AmbientBackdropPreviewDialog(
    initialStyle: AmbientBackdropStyle,
    onApply: (AmbientBackdropStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val styles = AmbientBackdropStyle.entries
    var previewStyle by remember { mutableStateOf(initialStyle) }
    var showMockUi by remember { mutableStateOf(true) }
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val currentIndex = styles.indexOf(previewStyle).coerceAtLeast(0)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AmbientBackdropLayer(
                style = previewStyle,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top bar
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.card.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, colors.border.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${previewStyle.title} (${currentIndex + 1}/${styles.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = previewStyle.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { showMockUi = !showMockUi }) {
                            Icon(
                                imageVector = if (showMockUi) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Arayüz örneğini aç/kapat"
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat")
                        }
                    }
                }

                // Middle optional mock app UI
                if (showMockUi) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("Son Çalınanlar", "Cihazdaki Müzikler").forEach { label ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.card.copy(alpha = 0.76f),
                                    border = BorderStroke(1.dp, colors.border.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(primary.copy(alpha = 0.28f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = colors.card.copy(alpha = 0.84f),
                            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(primary.copy(alpha = 0.30f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Örnek Parça", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text("PlasmaWave Sanatçı", style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary))
                                }
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = primary)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }

                // Bottom style switcher & apply
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.card.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, colors.border.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(styles) { _, option ->
                                FilterChip(
                                    selected = option == previewStyle,
                                    onClick = { previewStyle = option },
                                    label = { Text(option.title) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val prevIdx = if (currentIndex == 0) styles.lastIndex else currentIndex - 1
                                    previewStyle = styles[prevIdx]
                                }
                            ) {
                                Text("Önceki")
                            }
                            OutlinedButton(
                                onClick = {
                                    val nextIdx = (currentIndex + 1) % styles.size
                                    previewStyle = styles[nextIdx]
                                }
                            ) {
                                Text("Sonraki")
                            }
                            Button(
                                onClick = { onApply(previewStyle) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Uygula")
                            }
                        }
                    }
                }
            }
        }
    }
}
