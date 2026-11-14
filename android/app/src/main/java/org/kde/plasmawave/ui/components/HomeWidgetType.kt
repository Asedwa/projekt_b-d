package org.kde.plasmawave.ui.components

enum class HomeWidgetType(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultEnabled: Boolean
) {
    STATS(
        id = "widget_stats",
        title = "Şarkı ve Dinleme İstatistikleri",
        subtitle = "Toplam dinleme sürenizi ve en çok dinlediğiniz şarkıların sayaçlarını gösterir",
        defaultEnabled = true
    ),
    QUICK_ARTIST(
        id = "widget_quick_artist",
        title = "Hızlıca Sanatçıya Git",
        subtitle = "En çok dinlediğiniz sanatçılara tek dokunuşla ulaşın ve şarkılarını çalın",
        defaultEnabled = true
    ),
    QUICK_THEME(
        id = "widget_quick_theme",
        title = "Hızlıca Tema Değiştir",
        subtitle = "Ana sayfadan çıkmadan Twilight, Pinkie ve Custom temalarınız arasında geçiş yapın",
        defaultEnabled = true
    ),
    LUCKY_PICK(
        id = "widget_lucky_pick",
        title = "Şanslı Şarkı Seçici (Şansımı Dene)",
        subtitle = "Kitaplığınızdan rastgele bir şarkı önerir ve tek tuşla başlatır",
        defaultEnabled = true
    ),
    SLEEP_TIMER(
        id = "widget_sleep_timer",
        title = "Hızlı Uyku Zamanlayıcısı",
        subtitle = "15 dk, 30 dk, 45 dk veya şarkı sonu için hızlı uyku zamanlayıcısı",
        defaultEnabled = false
    ),
    MOOD_MIX(
        id = "widget_mood_mix",
        title = "Ruh Haline Göre Anında Miks",
        subtitle = "Enerjik, Gece Sürüşü, Odaklanma ve Rahatlatıcı modlarına göre anında liste başlatır",
        defaultEnabled = false
    )
}
