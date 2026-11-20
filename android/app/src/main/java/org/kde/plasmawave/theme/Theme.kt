package org.kde.plasmawave.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import uniffi.plasmawave_core.FfiTheme
import uniffi.plasmawave_core.computeAdaptiveTextPalette

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

// Yeni (sade) mod vurgu ve yüzey ayarları — buradan ince ayar yapılabilir
private const val CALM_ACCENT_SAT_MIN = 0.58f
private const val CALM_ACCENT_SAT_MAX = 0.76f
private const val CALM_ACCENT_BLUE_SAT_MAX = 0.82f
private const val CALM_ACCENT_LIGHT_MIN = 0.54f
private const val CALM_ACCENT_LIGHT_MAX = 0.64f

private const val CALM_BG_SAT = 0.30f               // Zemin doygunluğu (0.25–0.35)
private const val CALM_SURFACE_SAT = 0.32f          // Yüzey / Nav bar / Mini player doygunluğu (0.25–0.35)
private const val CALM_CARD_SAT = 0.35f             // Kart doygunluğu (0.30–0.40)
private const val CALM_CARD_PRESSED_SAT = 0.38f     // Basılı kart doygunluğu (0.30–0.40)
private const val CALM_BORDER_SAT = 0.28f
private const val CALM_TEXT_SEC_SAT = 0.18f

private const val CALM_BG_LIGHTNESS = 0.09f         // Zemin açıklığı (0.07–0.10)
private const val CALM_SURFACE_LIGHTNESS = 0.125f   // Nav bar & Mini player açıklığı (kartlarla aynı/1 kademe koyu)
private const val CALM_CARD_LIGHTNESS = 0.16f       // Kart açıklığı (0.13–0.18)
private const val CALM_CARD_PRESSED_LIGHTNESS = 0.21f
private const val CALM_BORDER_LIGHTNESS = 0.23f
private const val CALM_DIVIDER_LIGHTNESS = 0.19f
private const val CALM_TEXT_SEC_LIGHTNESS = 0.70f

/**
 * Keeps theme accents lively and distinct while preventing harsh neon glare in calm UI mode.
 */
fun desaturateForCalmUi(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1]
    val light = hsl[2]
    if (sat < 0.08f) return color
    val maxSat = if (hue in 185f..245f) CALM_ACCENT_BLUE_SAT_MAX else CALM_ACCENT_SAT_MAX
    val targetSat = sat.coerceIn(CALM_ACCENT_SAT_MIN, maxSat)
    val targetLight = light.coerceIn(CALM_ACCENT_LIGHT_MIN, CALM_ACCENT_LIGHT_MAX)
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, targetSat, targetLight)))
}

private fun pickSurfaceHueAndChroma(card: Color, surface: Color, bg: Color, accent: Color): Pair<Float, Boolean> {
    val hsl = FloatArray(3)
    for (candidate in arrayOf(card, surface, bg, accent)) {
        ColorUtils.colorToHSL(candidate.toArgb(), hsl)
        if (hsl[1] >= 0.05f) return hsl[0] to true
    }
    return 0f to false
}

private fun tintedCalmDarkSurface(hue: Float, hasChroma: Boolean, sat: Float, light: Float, amoledNothing: Boolean): Color {
    if (amoledNothing) {
        val amoledLight = (light - 0.05f).coerceAtLeast(0.03f)
        return Color(ColorUtils.HSLToColor(floatArrayOf(0f, 0f, amoledLight)))
    }
    val effectiveSat = if (hasChroma) sat else 0f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, effectiveSat, light)))
}

@Composable
fun PlasmaTheme(
    activeTheme: FfiTheme? = null,
    legacyUiMode: Boolean = false,
    ambientBackgroundEnabled: Boolean = false,
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

    val accent = if (!legacyUiMode) desaturateForCalmUi(rawAccent) else rawAccent
    val (calmSurfaceHue, hasSurfaceChroma) = pickSurfaceHueAndChroma(rawCard, rawSurface, rawBg, accent)

    // In calm (new default) dark mode: preserve legacy theme's surface hue (e.g. Twilight indigo/purple)
    val bg = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_BG_SAT, CALM_BG_LIGHTNESS, isAmoledNothing)
    } else rawBg

    val surface = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_SURFACE_SAT, CALM_SURFACE_LIGHTNESS, isAmoledNothing)
    } else rawSurface

    val card = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_CARD_SAT, CALM_CARD_LIGHTNESS, isAmoledNothing)
    } else rawCard

    val cardPressed = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_CARD_PRESSED_SAT, CALM_CARD_PRESSED_LIGHTNESS, isAmoledNothing)
    } else if (isDark) BreezeCardPressed else Color(0xFFDDE1E4)

    val border = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_BORDER_SAT, CALM_BORDER_LIGHTNESS, isAmoledNothing)
    } else rawBorder
    val divider = if (!legacyUiMode && isDark) {
        tintedCalmDarkSurface(calmSurfaceHue, hasSurfaceChroma, CALM_BORDER_SAT, CALM_DIVIDER_LIGHTNESS, isAmoledNothing)
    } else BreezeDivider

    val adaptivePalette = remember(bg, card, accent, ambientBackgroundEnabled) {
        computeAdaptiveTextPalette(
            backgroundArgb = bg.toArgb().toUInt(),
            cardArgb = card.toArgb().toUInt(),
            primaryArgb = accent.toArgb().toUInt(),
            ambientEnabled = ambientBackgroundEnabled
        )
    }

    val text = Color(adaptivePalette.onBackgroundArgb.toInt())
    val textSecondary = Color(adaptivePalette.onBackgroundSecondaryArgb.toInt())
    val onCard = Color(adaptivePalette.onCardArgb.toInt())
    val onAccent = Color(adaptivePalette.onAccentArgb.toInt())

    val colorScheme: ColorScheme = if (adaptivePalette.isDarkBackground) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = text,
            surface = surface,
            onSurface = onCard,
            surfaceVariant = card,
            onSurfaceVariant = textSecondary,
            surfaceContainerLowest = bg,
            surfaceContainerLow = surface,
            surfaceContainer = card,
            surfaceContainerHigh = cardPressed,
            surfaceContainerHighest = cardPressed,
            outline = border
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = text,
            surface = surface,
            onSurface = onCard,
            surfaceVariant = card,
            onSurfaceVariant = textSecondary,
            outline = border
        )
    }

    val customColors = PlasmaCustomColors(
        card = card,
        cardPressed = cardPressed,
        border = border,
        divider = divider,
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

    CompositionLocalProvider(
        LocalPlasmaColors provides customColors,
        LocalContentColor provides text
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (legacyUiMode) LegacyBreezeTypography else BreezeTypography,
            shapes = shapes,
            content = content
        )
    }
}
