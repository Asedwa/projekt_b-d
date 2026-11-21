package org.kde.plasmawave.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector

enum class PlayerActionButton(
    val title: String,
    val shortLabel: String,
    val icon: ImageVector
) {
    REPEAT("Tekrarla", "Tekrarla", Icons.Default.Repeat),
    SHUFFLE("Karıştır", "Karıştır", Icons.Default.Shuffle),
    MIX("Şarkı Miksi", "Miks", Icons.Default.Radio),
    SLEEP_TIMER("Uyku Zamanlayıcı", "Uyku", Icons.Default.Bedtime),
    FAVORITE("Beğen", "Beğen", Icons.Default.Favorite),
    ADD_TO_PLAYLIST("Listeye Ekle", "Listeye", Icons.AutoMirrored.Filled.PlaylistAdd),
    OPTIONS("Seçenekler", "Seçenek", Icons.Default.GraphicEq);

    companion object {
        val DEFAULT_PINNED: List<PlayerActionButton> = listOf(REPEAT, SHUFFLE)
    }
}
