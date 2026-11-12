package org.kde.plasmawave.theme

import androidx.compose.ui.graphics.Color

// Default Breeze Dark Palette tokens
val BreezeBackground = Color(0xFF1B1E20)
val BreezeSurface = Color(0xFF202326)
val BreezeSurfaceVariant = Color(0xFF292C30)
val BreezeCard = Color(0xFF2A2E32)
val BreezeCardPressed = Color(0xFF31363B)
val BreezeAccent = Color(0xFF3DAEE9)
val BreezeAccentMuted = Color(0xFF1D5A7A)
val BreezeOnAccent = Color(0xFFFCFCFC)
val BreezeText = Color(0xFFFCFCFC)
val BreezeTextSecondary = Color(0xFFA1A9B1)
val BreezeTextDisabled = Color(0xFF6E757C)
val BreezeBorder = Color(0xFF3B4045)
val BreezeDivider = Color(0xFF2F3337)
val BreezePositive = Color(0xFF27AE60)
val BreezeWarning = Color(0xFFF67400)
val BreezeNegative = Color(0xFFDA4453)

fun parseHexColor(hexString: String, defaultColor: Color): Color {
    return try {
        val s = hexString.trim().removePrefix("#")
        when (s.length) {
            6 -> {
                val colorInt = s.toLong(16) or 0x00000000FF000000L
                Color(colorInt)
            }
            8 -> {
                val colorInt = s.toLong(16)
                Color(colorInt)
            }
            3 -> {
                val r = s.substring(0, 1).repeat(2)
                val g = s.substring(1, 2).repeat(2)
                val b = s.substring(2, 3).repeat(2)
                val colorInt = "$r$g$b".toLong(16) or 0x00000000FF000000L
                Color(colorInt)
            }
            else -> defaultColor
        }
    } catch (_: Exception) {
        defaultColor
    }
}
