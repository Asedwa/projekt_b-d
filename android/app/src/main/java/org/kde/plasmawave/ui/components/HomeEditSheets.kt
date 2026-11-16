package org.kde.plasmawave.ui.components

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.theme.PlasmaCustomColors
import uniffi.plasmawave_core.FfiHomeWidgetCatalogEntry
import uniffi.plasmawave_core.FfiHomeWidgetItem
import uniffi.plasmawave_core.FfiHomeWidgetPatch
import uniffi.plasmawave_core.FfiHomeWidgetSize
import uniffi.plasmawave_core.FfiHomeWidgetStyle

data class HomeWidgetVisualTokens(
    val containerColor: Color,
    val borderStroke: BorderStroke?,
    val accentColor: Color
)

fun parseHomeHexColorSafe(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(AndroidColor.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

fun resolveHomeWidgetVisuals(
    item: FfiHomeWidgetItem,
    colors: PlasmaCustomColors,
    fallbackAccent: Color
): HomeWidgetVisualTokens {
    val accent = parseHomeHexColorSafe(item.accentHex, fallbackAccent)
    return when (item.style) {
        FfiHomeWidgetStyle.CARD -> HomeWidgetVisualTokens(
            containerColor = colors.card,
            borderStroke = null,
            accentColor = accent
        )
        FfiHomeWidgetStyle.TINTED -> HomeWidgetVisualTokens(
            containerColor = accent.copy(alpha = 0.14f),
            borderStroke = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
            accentColor = accent
        )
        FfiHomeWidgetStyle.MINIMAL -> HomeWidgetVisualTokens(
            containerColor = Color.Transparent,
            borderStroke = BorderStroke(1.dp, colors.border.copy(alpha = 0.7f)),
            accentColor = accent
        )
    }
}

fun homeWidgetKindIcon(kind: String): ImageVector = when (kind) {
    "block_flow_hero" -> Icons.Default.AllInclusive
    "block_resume_playback" -> Icons.Default.PlayCircleOutline
    "block_quick_access" -> Icons.Default.GridView
    "block_feed" -> Icons.AutoMirrored.Filled.QueueMusic
    "widget_stats" -> Icons.Default.BarChart
    "widget_quick_artist" -> Icons.Default.PersonSearch
    "widget_quick_theme" -> Icons.Default.Palette
    "widget_lucky_pick" -> Icons.Default.Casino
    "widget_sleep_timer" -> Icons.Default.Bedtime
    "widget_mood_mix" -> Icons.Default.AutoAwesome
    "widget_recent_tracks" -> Icons.Default.History
    "widget_listening_summary" -> Icons.Default.Speed
    else -> Icons.Default.Widgets
}

fun defaultTitleForHomeKind(kind: String): String = when (kind) {
    "block_flow_hero" -> "Deezer Flow"
    "block_resume_playback" -> "Kaldığın Yerden Devam Et"
    "block_quick_access" -> "Hızlı Erişim"
    "block_feed" -> "Müzik Akışı"
    "widget_stats" -> "İstatistikler"
    "widget_quick_artist" -> "Hızlı Sanatçı"
    "widget_quick_theme" -> "Hızlı Tema"
    "widget_lucky_pick" -> "Şanslı Şarkı"
    "widget_sleep_timer" -> "Uyku Zamanlayıcı"
    "widget_mood_mix" -> "Ruh Hali Miksi"
    "widget_recent_tracks" -> "Son Dinlenenler"
    "widget_listening_summary" -> "Dinleme Özeti"
    else -> "Widget"
}

/**
 * Detects a continuous stationary press lasting [holdDurationMs] (minimum 3000 ms) without
 * interrupting normal taps, track-menu long-clicks (<1s), or LazyColumn scrolling.
 * Once triggered, consumes remaining pointer release events so child clickables don't fire on lift.
 */
fun Modifier.homeEditHoldTrigger(
    enabled: Boolean,
    holdDurationMs: Long,
    onHoldProgress: (Float?) -> Unit,
    onTriggered: () -> Unit
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDuration by rememberUpdatedState(holdDurationMs.coerceIn(3_000L, 10_000L))
    val currentOnProgress by rememberUpdatedState(onHoldProgress)
    val currentOnTriggered by rememberUpdatedState(onTriggered)

    pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!currentEnabled) return@awaitEachGesture

                val pointerId = down.id
                val startPos = down.position
                var triggered = false

                val timerJob = launch {
                    val visualDelayMs = 450L
                    delay(visualDelayMs)
                    val totalMs = currentDuration
                    val startUptime = SystemClock.uptimeMillis()
                    while (isActive) {
                        val elapsed = visualDelayMs + (SystemClock.uptimeMillis() - startUptime)
                        val fraction = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                        currentOnProgress(fraction)
                        if (elapsed >= totalMs) {
                            triggered = true
                            currentOnProgress(null)
                            currentOnTriggered()
                            break
                        }
                        delay(32L)
                    }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (triggered) {
                            event.changes.forEach { if (it.id == pointerId) it.consume() }
                        }
                        val tracked = event.changes.firstOrNull { it.id == pointerId }
                        if (tracked == null || !tracked.pressed || event.changes.count { it.pressed } > 1) {
                            break
                        }
                        if (!triggered && (tracked.position - startPos).getDistance() > touchSlop) {
                            break
                        }
                    }
                } finally {
                    timerJob.cancel()
                    currentOnProgress(null)
                }
            }
        }
    }
}

@Composable
fun HomeEditHoldIndicatorPill(
    progress: Float,
    holdSeconds: Int,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val remainingSec = ((1f - progress) * holdSeconds).coerceAtLeast(0f)

    Surface(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 6.dp),
        color = colors.card.copy(alpha = 0.96f),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Düzenleme modu için basılı tut (${String.format("%.1f", remainingSec)} sn)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
                color = primary,
                trackColor = colors.border
            )
        }
    }
}

@Composable
fun HomeEditableItemShell(
    item: FfiHomeWidgetItem,
    title: String,
    indexInLayout: Int,
    totalCount: Int,
    isEditMode: Boolean,
    holdDurationMs: Long,
    allowHoldOnShell: Boolean,
    listState: LazyListState,
    isDraggingThis: Boolean,
    dragOffsetY: Float,
    onHoldProgress: (Float?) -> Unit,
    onEnterEditMode: () -> Unit,
    onDragStart: () -> Unit,
    onDragDeltaY: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCustomize: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    val accent = parseHomeHexColorSafe(item.accentHex, primary)

    // Keep references fresh across pointerInput suspensions without restarting pointerInput mid-drag.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDeltaY by rememberUpdatedState(onDragDeltaY)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val holdMod = if (!isEditMode && allowHoldOnShell) {
        Modifier.homeEditHoldTrigger(
            enabled = true,
            holdDurationMs = holdDurationMs,
            onHoldProgress = onHoldProgress,
            onTriggered = onEnterEditMode
        )
    } else {
        Modifier
    }

    val dragTransformMod = Modifier
        .zIndex(if (isDraggingThis) 10f else 0f)
        .graphicsLayer {
            if (isDraggingThis) {
                translationY = dragOffsetY
                scaleX = 1.018f
                scaleY = 1.018f
                alpha = 0.94f
            }
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(dragTransformMod)
            .then(holdMod)
            .then(
                if (isEditMode) {
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            width = if (isDraggingThis) 2.dp else 1.dp,
                            color = if (isDraggingThis) accent else accent.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .background(colors.card.copy(alpha = 0.22f))
                        .padding(top = 6.dp, bottom = 6.dp)
                } else {
                    Modifier
                }
            )
    ) {
        if (isEditMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                // Drag handle (dedicated touch zone so LazyColumn scroll stays conflict-free)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.cardPressed.copy(alpha = 0.7f))
                        .pointerInput(item.instanceId, listState) {
                            detectVerticalDragGestures(
                                onDragStart = { currentOnDragStart() },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    currentOnDragDeltaY(dragAmount)
                                },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragEnd() }
                            )
                        }
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Sürükle",
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    homeWidgetKindIcon(item.kind),
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.customTitle?.takeIf { it.isNotBlank() } ?: title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Deterministic Up/Down step buttons for single-handed fine reordering
                IconButton(
                    onClick = onMoveUp,
                    enabled = indexInLayout > 0,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Yukarı taşı",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = indexInLayout < totalCount - 1,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Aşağı taşı",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onCustomize,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Özelleştir",
                        tint = accent,
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Kaldır",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        content()
    }
}

@Composable
fun HomeEditToolbar(
    canUndo: Boolean,
    onAddWidgetClick: () -> Unit,
    onUndoClick: () -> Unit,
    onResetClick: () -> Unit,
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = colors.card.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.45f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            FilledTonalButton(
                onClick = onAddWidgetClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ekle", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }

            OutlinedButton(
                onClick = onUndoClick,
                enabled = canUndo,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Geri Al", style = MaterialTheme.typography.labelMedium)
            }

            IconButton(
                onClick = onResetClick,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = "Varsayılana sıfırla",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onDoneClick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Bitti", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHomeWidgetBottomSheet(
    catalog: List<FfiHomeWidgetCatalogEntry>,
    activeLayout: List<FfiHomeWidgetItem>,
    onAddKind: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary
    var selectedFilter by remember { mutableStateOf("all") }

    val countsByKind = remember(activeLayout) {
        activeLayout.filter { it.enabled }.groupingBy { it.kind }.eachCount()
    }
    val filteredCatalog = remember(catalog, selectedFilter) {
        when (selectedFilter) {
            "widget" -> catalog.filter { it.category == "widget" }
            "block" -> catalog.filter { it.category == "block" }
            else -> catalog
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Ana Sayfaya Öğe Ekle",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "Rust widget kataloğundan yeni kartlar veya ana bölümler ekleyin.",
                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "all" to "Tümü",
                    "widget" to "Widget'lar",
                    "block" to "Ana Bloklar"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 430.dp)
            ) {
                items(filteredCatalog, key = { it.kind }) { entry ->
                    val count = countsByKind[entry.kind] ?: 0
                    val canAddMore = entry.allowMultiple || count == 0

                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(primary.copy(alpha = 0.16f))
                            ) {
                                Icon(
                                    homeWidgetKindIcon(entry.kind),
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        entry.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (count > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(primary.copy(alpha = 0.18f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "${count}x",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }
                                }
                                Text(
                                    entry.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            if (canAddMore) {
                                FilledTonalButton(
                                    onClick = { onAddKind(entry.kind) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (count > 0) "Tekrar" else "Ekle")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {},
                                    enabled = false,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ekli")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ACCENT_PRESETS = listOf(
    "#3DAEE9", // Breeze
    "#ED438D", // Twilight Pink
    "#FF2E93", // Pinkie
    "#8B5CF6", // Violet
    "#10B981", // Emerald
    "#F59E0B", // Amber
    "#EF4444", // Coral Red
    "#38BDF8"  // Sky Blue
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeHomeWidgetBottomSheet(
    item: FfiHomeWidgetItem,
    catalogEntry: FfiHomeWidgetCatalogEntry?,
    onApplyPatch: (FfiHomeWidgetPatch) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val primary = MaterialTheme.colorScheme.primary

    var titleText by remember(item.instanceId) { mutableStateOf(item.customTitle ?: "") }
    var selectedSize by remember(item.instanceId) { mutableStateOf(item.size) }
    var selectedStyle by remember(item.instanceId) { mutableStateOf(item.style) }
    var showSubtitle by remember(item.instanceId) { mutableStateOf(item.showSubtitle) }
    var maxItemsFloat by remember(item.instanceId) { mutableFloatStateOf(item.maxItems.toFloat()) }
    var selectedAccentHex by remember(item.instanceId) { mutableStateOf(item.accentHex) }

    fun pushPatch(
        size: FfiHomeWidgetSize = selectedSize,
        style: FfiHomeWidgetStyle = selectedStyle,
        sub: Boolean = showSubtitle,
        maxItems: Int = maxItemsFloat.roundToInt(),
        title: String = titleText,
        accentHex: String? = selectedAccentHex
    ) {
        val cleanTitle = title.trim()
        onApplyPatch(
            FfiHomeWidgetPatch(
                enabled = null,
                size = size,
                style = style,
                maxItems = maxItems.toUByte(),
                showSubtitle = sub,
                clearCustomTitle = cleanTitle.isEmpty(),
                customTitle = cleanTitle.takeIf { it.isNotEmpty() },
                clearAccentHex = accentHex == null,
                accentHex = accentHex
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            pushPatch()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    homeWidgetKindIcon(item.kind),
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        catalogEntry?.title ?: defaultTitleForHomeKind(item.kind),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "Kartı kişisel zevkinize göre özelleştirin",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                    )
                }
            }

            // Custom title
            OutlinedTextField(
                value = titleText,
                onValueChange = {
                    if (it.length <= 48) {
                        titleText = it
                    }
                },
                label = { Text("Özel Başlık (İsteğe Bağlı)") },
                placeholder = { Text(catalogEntry?.title ?: defaultTitleForHomeKind(item.kind)) },
                singleLine = true,
                trailingIcon = {
                    if (titleText.isNotEmpty()) {
                        IconButton(onClick = {
                            titleText = ""
                            pushPatch(title = "")
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Başlığı sıfırla")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Size / density
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Boyut ve Yoğunluk",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        FfiHomeWidgetSize.COMPACT to "Kompakt",
                        FfiHomeWidgetSize.NORMAL to "Normal",
                        FfiHomeWidgetSize.EXPANDED to "Geniş"
                    ).forEach { (sz, label) ->
                        val active = selectedSize == sz
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedSize = sz
                                pushPatch(size = sz)
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Visual style
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Kart Görünüm Stili",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        FfiHomeWidgetStyle.CARD to "Tema Kartı",
                        FfiHomeWidgetStyle.TINTED to "Vurgu Tonlu",
                        FfiHomeWidgetStyle.MINIMAL to "Minimal"
                    ).forEach { (st, label) ->
                        val active = selectedStyle == st
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedStyle = st
                                pushPatch(style = st)
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Item count slider (if supported)
            if (catalogEntry?.supportsItemCount == true) {
                val minI = catalogEntry.minItems.toInt().coerceAtLeast(1)
                val maxI = catalogEntry.maxItemsLimit.toInt().coerceAtLeast(minI + 1)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Gösterilecek Öğe Sayısı",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${maxItemsFloat.roundToInt()} öğe",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Slider(
                        value = maxItemsFloat.coerceIn(minI.toFloat(), maxI.toFloat()),
                        onValueChange = { maxItemsFloat = it },
                        onValueChangeFinished = {
                            pushPatch(maxItems = maxItemsFloat.roundToInt().coerceIn(minI, maxI))
                        },
                        valueRange = minI.toFloat()..maxI.toFloat(),
                        steps = (maxI - minI - 1).coerceAtLeast(0)
                    )
                }
            }

            // Subtitle visibility
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .clickable {
                        showSubtitle = !showSubtitle
                        pushPatch(sub = showSubtitle)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Alt Açıklama Satırı",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "Kart başlığının altındaki yardımcı açıklamayı göster",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                    )
                }
                Switch(
                    checked = showSubtitle,
                    onCheckedChange = {
                        showSubtitle = it
                        pushPatch(sub = it)
                    }
                )
            }

            // Accent color picker
            if (catalogEntry?.supportsAccent != false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Özel Kart Vurgu Rengi",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp)
                    ) {
                        // Reset to theme default
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.cardPressed)
                                .border(
                                    width = if (selectedAccentHex == null) 2.dp else 1.dp,
                                    color = if (selectedAccentHex == null) primary else colors.border,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedAccentHex = null
                                    pushPatch(accentHex = null)
                                }
                        ) {
                            Icon(
                                Icons.Default.AutoMode,
                                contentDescription = "Tema rengi",
                                tint = primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        ACCENT_PRESETS.forEach { hex ->
                            val selected = selectedAccentHex.equals(hex, ignoreCase = true)
                            val col = parseHomeHexColorSafe(hex, primary)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (selected) 2.5.dp else 0.dp,
                                        color = if (selected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        selectedAccentHex = hex
                                        pushPatch(accentHex = hex)
                                    }
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    pushPatch()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Kaydet ve Kapat", fontWeight = FontWeight.Bold)
            }
        }
    }
}
