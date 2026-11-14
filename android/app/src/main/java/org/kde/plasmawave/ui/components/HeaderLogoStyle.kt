package org.kde.plasmawave.ui.components

import androidx.annotation.DrawableRes

enum class HeaderLogoStyle(
    val title: String,
    val subtitle: String,
    @DrawableRes val drawableRes: Int?
) {
    ORIGINAL(
        title = "Klasik (Orijinal)",
        subtitle = "Klasik müzik dalgası ikonu ve başlık",
        drawableRes = null
    ),
    CUSTOM(
        title = "Özel PNG Logosu",
        subtitle = "Kendi yükleyip kırptığınız PNG logosu",
        drawableRes = null
    )
}
