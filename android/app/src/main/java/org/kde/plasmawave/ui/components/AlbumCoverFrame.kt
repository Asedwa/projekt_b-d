package org.kde.plasmawave.ui.components

enum class AlbumCoverFrame(
    val title: String,
    val subtitle: String
) {
    MODERN_ROUNDED(
        title = "Modern Yuvarlak",
        subtitle = "Zarif kavisli modern minimalist köşeler"
    ),
    VINYL_RECORD(
        title = "Vinil Plak",
        subtitle = "Kılıfından çıkan dönen otantik siyah vinil plak"
    ),
    NEON_GLOW(
        title = "Neon Siber Işıltı",
        subtitle = "Müziğin rengiyle parıldayan neon siber çerçeve"
    ),
    RETRO_CASSETTE(
        title = "Retro Kaset",
        subtitle = "90'lar analog manyetik kaset ve dönen makara pencereleri"
    ),
    NOTHING_DOTS(
        title = "Nothing Dot-Matrix",
        subtitle = "Nothing OS tarzı noktalı endüstriyel fütüristik çerçeve"
    )
}
