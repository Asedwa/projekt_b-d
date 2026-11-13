package org.kde.plasmawave.ui.components

import androidx.annotation.DrawableRes
import org.kde.plasmawave.R

enum class HeaderLogoStyle(
    val title: String,
    val subtitle: String,
    @DrawableRes val drawableRes: Int?
) {
    TWILIGHT(
        title = "Twilight Sparkle",
        subtitle = "My Little Pony karakter logosu",
        drawableRes = R.drawable.ic_header_twilight
    ),
    PINKIE(
        title = "Pinkie Pie",
        subtitle = "My Little Pony neşeli pembe logo",
        drawableRes = R.drawable.ic_header_pinkie
    ),
    SYM_S(
        title = "S Müzik Logosu",
        subtitle = "Symfonium tarzı zarif S arması",
        drawableRes = R.drawable.ic_header_sym_s
    ),
    ORIGINAL(
        title = "Orijinal Başlık",
        subtitle = "Klasik müzik dalgası ikonu ve başlık",
        drawableRes = null
    )
}
