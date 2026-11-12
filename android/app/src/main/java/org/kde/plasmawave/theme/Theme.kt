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
    val textDisabled: Color = BreezeTextDisabled
)

val LocalPlasmaColors = staticCompositionLocalOf { PlasmaCustomColors() }

@Composable
fun PlasmaTheme(
    activeTheme: FfiTheme? = null,
    content: @Composable () -> Unit
) {
    val isDark = activeTheme?.isDark ?: true

    val bg = activeTheme?.backgroundColor?.let { parseHexColor(it, BreezeBackground) } ?: BreezeBackground
    val surface = activeTheme?.surfaceColor?.let { parseHexColor(it, BreezeSurface) } ?: BreezeSurface
    val accent = activeTheme?.accentColor?.let { parseHexColor(it, BreezeAccent) } ?: BreezeAccent
    val text = activeTheme?.textColor?.let { parseHexColor(it, BreezeText) } ?: BreezeText
    val card = activeTheme?.cardColor?.let { parseHexColor(it, BreezeCard) } ?: BreezeCard
    val border = activeTheme?.borderColor?.let { parseHexColor(it, BreezeBorder) } ?: BreezeBorder
    val textSecondary = activeTheme?.textSecondaryColor?.let { parseHexColor(it, BreezeTextSecondary) } ?: BreezeTextSecondary

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
        cardPressed = if (isDark) BreezeCardPressed else Color(0xFFDDE1E4),
        border = border,
        textSecondary = textSecondary
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
            typography = BreezeTypography,
            shapes = shapes,
            content = content
        )
    }
}
