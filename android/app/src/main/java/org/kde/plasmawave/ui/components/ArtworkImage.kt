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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import org.kde.plasmawave.theme.LocalPlasmaColors

private fun bucketArtworkPx(rawPx: Int): Int = when {
    rawPx <= 160 -> 160
    rawPx <= 320 -> 320
    rawPx <= 512 -> 512
    else -> 1000
}

/** Displays a cached cover or a neutral placeholder when artwork is unavailable. */
@Composable
fun ArtworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.MusicNote,
    shape: Shape = RoundedCornerShape(6.dp),
    background: Color = LocalPlasmaColors.current.card,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackTitle: String? = null,
    fallbackArtist: String? = null,
    sizeHint: Dp? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetPx = if (sizeHint != null) {
        bucketArtworkPx(with(density) { sizeHint.roundToPx() })
    } else 0
    val initialUrl = url?.takeUnless(ArtworkResolver::isPlaceholderOrBlank)
    var resolvedFallback by remember(initialUrl, fallbackTitle, fallbackArtist) {
        mutableStateOf(
            if (initialUrl == null && !fallbackTitle.isNullOrBlank()) {
                ArtworkResolver.getCached(context, fallbackTitle, fallbackArtist)
            } else null
        )
    }
    val effectiveUrl = initialUrl ?: resolvedFallback
    var failed by remember(effectiveUrl) { mutableStateOf(false) }

    val needsFallback = (initialUrl == null || failed) &&
        !fallbackTitle.isNullOrBlank() &&
        resolvedFallback == null &&
        !ArtworkResolver.isKnownMissing(fallbackTitle, fallbackArtist)
    if (needsFallback) {
        LaunchedEffect(initialUrl, failed, fallbackTitle, fallbackArtist) {
            resolvedFallback = ArtworkResolver.resolve(context, fallbackTitle, fallbackArtist)
        }
    }

    val displayUrl = if (!failed) effectiveUrl else resolvedFallback?.takeIf { it != effectiveUrl }

    Box(modifier = modifier.clip(shape).background(background), contentAlignment = Alignment.Center) {
        if (displayUrl.isNullOrBlank()) {
            Icon(fallback, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize(0.5f))
        } else {
            val request = remember(displayUrl, targetPx, context) {
                ImageRequest.Builder(context)
                    .data(displayUrl)
                    .diskCacheKey(displayUrl)
                    .apply {
                        if (targetPx > 0) {
                            size(targetPx)
                            precision(Precision.INEXACT)
                            memoryCacheKey("$displayUrl@$targetPx")
                        } else {
                            memoryCacheKey(displayUrl)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                alignment = Alignment.Center,
                contentScale = contentScale,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
