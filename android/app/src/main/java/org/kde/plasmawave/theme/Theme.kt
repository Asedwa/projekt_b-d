package org.kde.plasmawave.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import uniffi.plasmawave_core.FfiTheme

data class PlasmaCustomColors(
    val card: Color = BreezeCard,
    val cardPressed: Color = BreezeCardPressed,
    val border: Color = BreezeBorder,
    val divider: Color = BreezeDivider,
    val positive: Color = BreezePositive,
    val warning: Color = BreezeWarning,
    val negative: Color = BreezeNegative,
    val textSecondary: Color = BreezeTextSecondary,
    val textDisabled: Color = BreezeTextDisabled,
    val legacyUiMode: Boolean = false
)

val LocalPlasmaColors = staticCompositionLocalOf { PlasmaCustomColors() }

/**
 * Desaturates vivid/neon theme accents into calmer, product-grade tones while preserving hue identity.
 */
fun desaturateForCalmUi(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1]
    val light = hsl[2]
    if (sat < 0.08f) return color
    // Blues stay slightly crisper (Breeze aesthetic); pinks/magentas/reds are damped more gently
    val maxSat = if (hue in 185f..245f) 0.58f else 0.44f
    val targetSat = if (sat > maxSat) {
        (maxSat - (sat - maxSat) * 0.12f).coerceIn(0.30f, maxSat)
    } else {
        sat.coerceAtLeast(0.26f)
    }
    val targetLight = light.coerceIn(0.54f, 0.66f)
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, targetSat, targetLight)))
}

@Composable
fun PlasmaTheme(
    activeTheme: FfiTheme? = null,
    legacyUiMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = activeTheme?.isDark ?: true
    val isAmoledNothing = activeTheme?.id == "nothing.os"

    val rawBg = activeTheme?.backgroundColor?.let { parseHexColor(it, BreezeBackground) } ?: BreezeBackground
    val rawSurface = activeTheme?.surfaceColor?.let { parseHexColor(it, BreezeSurface) } ?: BreezeSurface
    val rawAccent = activeTheme?.accentColor?.let { parseHexColor(it, BreezeAccent) } ?: BreezeAccent
    val rawText = activeTheme?.textColor?.let { parseHexColor(it, BreezeText) } ?: BreezeText
    val rawCard = activeTheme?.cardColor?.let { parseHexColor(it, BreezeCard) } ?: BreezeCard
    val rawBorder = activeTheme?.borderColor?.let { parseHexColor(it, BreezeBorder) } ?: BreezeBorder
    val rawTextSecondary = activeTheme?.textSecondaryColor?.let { parseHexColor(it, BreezeTextSecondary) } ?: BreezeTextSecondary

    // In calm (new default) dark mode: flat neutral charcoal/black with cards only 1-2 shades lighter and zero color tint
    val bg = if (!legacyUiMode && isDark) {
        if (isAmoledNothing) Color(0xFF0A0A0B) else Color(0xFF131416)
    } else rawBg

    val surface = if (!legacyUiMode && isDark) {
        if (isAmoledNothing) Color(0xFF121214) else Color(0xFF18191B)
    } else rawSurface

    val card = if (!legacyUiMode && isDark) {
        if (isAmoledNothing) Color(0xFF171719) else Color(0xFF1C1D20)
    } else rawCard

    val cardPressed = if (!legacyUiMode && isDark) {
        if (isAmoledNothing) Color(0xFF202023) else Color(0xFF242529)
    } else if (isDark) BreezeCardPressed else Color(0xFFDDE1E4)

    val border = if (!legacyUiMode && isDark) Color(0xFF27282C) else rawBorder
    val text = if (!legacyUiMode && isDark) Color(0xFFF1F2F4) else rawText
    val textSecondary = if (!legacyUiMode && isDark) Color(0xFF9BA0A8) else rawTextSecondary
    val accent = if (!legacyUiMode) desaturateForCalmUi(rawAccent) else rawAccent

    val onAccent = if (accent.luminance() > 0.52f) Color(0xFF1A1D20) else Color.White

    val colorScheme: ColorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = card,
            onSurfaceVariant = textSecondary,
            outline = border
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = card,
            onSurfaceVariant = textSecondary,
            outline = border
        )
    }

    val customColors = PlasmaCustomColors(
        card = card,
        cardPressed = cardPressed,
        border = border,
        divider = if (!legacyUiMode && isDark) Color(0xFF232428) else BreezeDivider,
        textSecondary = textSecondary,
        legacyUiMode = legacyUiMode
    )

    val shapes = if (activeTheme != null) {
        createBreezeShapes(
            activeTheme.smallRadius.toInt(),
            activeTheme.mediumRadius.toInt(),
            activeTheme.largeRadius.toInt()
        )
    } else {
        BreezeDefaultShapes
    }

    CompositionLocalProvider(LocalPlasmaColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (legacyUiMode) LegacyBreezeTypography else BreezeTypography,
            shapes = shapes,
            content = content
        )
    }
}
