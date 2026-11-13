package org.kde.plasmawave.ui.components

enum class SeekbarStyle(
    val title: String,
    val subtitle: String
) {
    CLASSIC(
        title = "Klasik Çizgi",
        subtitle = "Zarif, pürüzsüz minimalist modern ince çizgi"
    ),
    WAVY(
        title = "Dalgalı Ses Eğrisi",
        subtitle = "Android 13+ tarzı canlı dalgalanan sinüs ses eğrisi"
    ),
    DOTS(
        title = "Nothing Noktalı Matrix",
        subtitle = "Nothing OS tarzı retro-fütüristik noktalı gösterge"
    ),
    GLOW_CAPSULE(
        title = "Neon Kapsül",
        subtitle = "İçten aydınlatmalı modern dolgulu neon kapsül"
    ),
    BARS(
        title = "Ses Spektrumu Barları",
        subtitle = "Ekolayzır frekans barları ve ses dalgası deseni"
    )
}
