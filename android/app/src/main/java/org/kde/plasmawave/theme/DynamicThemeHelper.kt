package org.kde.plasmawave.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ExtractedArtworkColors(
    val dominant: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color
)

object DynamicThemeHelper {
    private val cache = ConcurrentHashMap<String, ExtractedArtworkColors>()

    suspend fun extractColors(context: Context, url: String?): ExtractedArtworkColors? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(96, 96)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap ?: return@withContext null
                val palette = Palette.from(bitmap).generate()

                val dominantInt = palette.dominantSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.vibrantSwatch?.rgb
                    ?: return@withContext null

                val vibrantInt = palette.darkVibrantSwatch?.rgb
                    ?: palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: dominantInt

                val topColor = Color(vibrantInt)
                val bottomColor = Color(
                    red = (topColor.red * 0.22f).coerceIn(0f, 1f),
                    green = (topColor.green * 0.22f).coerceIn(0f, 1f),
                    blue = (topColor.blue * 0.22f).coerceIn(0f, 1f),
                    alpha = 1f
                )
                val accent = palette.vibrantSwatch?.rgb?.let { Color(it) }
                    ?: palette.lightVibrantSwatch?.rgb?.let { Color(it) }
                    ?: topColor

                val extracted = ExtractedArtworkColors(
                    dominant = Color(dominantInt),
                    backgroundTop = topColor,
                    backgroundBottom = bottomColor,
                    accent = accent
                )
                cache[url] = extracted
                extracted
            } catch (_: Exception) {
                null
            }
        }
    }
}
