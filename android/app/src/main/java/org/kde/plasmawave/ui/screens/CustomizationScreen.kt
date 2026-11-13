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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.ui.MainViewModel
import org.kde.plasmawave.ui.components.AlbumCoverFrame
import org.kde.plasmawave.ui.components.ArtworkFramedView
import org.kde.plasmawave.ui.components.CustomSeekbar
import org.kde.plasmawave.ui.components.SeekbarStyle

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
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary

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
            // 1. Dinamik Albüm Renkleri
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

                // 2. Deezer Flow Yerleşimi
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

                // 3. Albüm Kapağını Kaydırarak Şarkı Değiştir
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

                // 3. Albüm Kapağı Çerçevesi
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

            // 4. Oynatıcı İlerleme Çubuğu Deseni (6 Model, Nyan Cat dahil)
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
