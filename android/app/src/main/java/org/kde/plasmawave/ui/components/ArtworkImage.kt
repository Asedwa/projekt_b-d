package org.kde.plasmawave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.kde.plasmawave.theme.LocalPlasmaColors

/** Displays a cached cover or a neutral placeholder when artwork is unavailable. */
@Composable
fun ArtworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.MusicNote,
    shape: Shape = RoundedCornerShape(6.dp),
    background: Color = LocalPlasmaColors.current.card,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Box(modifier = modifier.clip(shape).background(background), contentAlignment = Alignment.Center) {
        Icon(fallback, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize(0.5f))
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
