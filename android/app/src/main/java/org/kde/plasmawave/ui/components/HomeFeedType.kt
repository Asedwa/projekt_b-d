package org.kde.plasmawave.ui.components

enum class HomeFeedType(
    val title: String,
    val subtitle: String,
    val description: String
) {
    FOR_YOU(
        title = "Senin için",
        subtitle = "Deezer • Sevdiğin şarkılar",
        description = "Deezer sevdiğin şarkılar ve öneriler"
    ),
    RECENTLY_PLAYED(
        title = "Son Çalınanlar",
        subtitle = "En son dinlediğin parçalar",
        description = "Bu cihazda ve Deezer'da en son dinlediğin şarkılar"
    ),
    MOST_PLAYED(
        title = "En Çok Dinlenenler",
        subtitle = "En sık dinlediğin hitlerin",
        description = "Tekrar tekrar çaldığın en popüler parçaların"
    )
}
