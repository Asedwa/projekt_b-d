package org.kde.plasmawave.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedArtworkColors(
    val dominant: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color
)

object DynamicThemeHelper {
    private val cache = LruCache<String, ExtractedArtworkColors>(64)

    suspend fun extractColors(context: Context, url: String?, calmMode: Boolean = true): ExtractedArtworkColors? {
        if (url.isNullOrBlank()) return null
        val cacheKey = "${url}|${if (calmMode) "calm" else "legacy"}"
        cache.get(cacheKey)?.let { return it }

        return try {
            val bitmap = withContext(Dispatchers.IO) {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(96, 96)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                (result as? BitmapDrawable)?.bitmap
            } ?: return null

            withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()

                val w = bitmap.width
                val h = bitmap.height
                val hsl = FloatArray(3)

                // 1. Detect if artwork edges are dark/black
                val corners = intArrayOf(
                    bitmap.getPixel(0, 0),
                    bitmap.getPixel(w - 1, 0),
                    bitmap.getPixel(0, h - 1),
                    bitmap.getPixel(w - 1, h - 1)
                )
                var edgeLightnessSum = 0f
                for (pixel in corners) {
                    ColorUtils.colorToHSL(pixel, hsl)
                    edgeLightnessSum += hsl[2]
                }
                val avgEdgeLightness = edgeLightnessSum / 4f
                val hasDarkEdges = avgEdgeLightness < 0.12f

                // 2. Dominant color analysis
                val dominantSwatch = palette.dominantSwatch ?: palette.swatches.maxByOrNull { it.population }
                val dominantInt = dominantSwatch?.rgb ?: 0xFF121212.toInt()
                ColorUtils.colorToHSL(dominantInt, hsl)
                val dominantLightness = hsl[2]
                val isDarkArtwork = hasDarkEdges || dominantLightness < 0.14f

                // 3. Find the best vibrant accent candidate
                val swatches = palette.swatches
                val vibrantCandidate = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: swatches.filter {
                        val sHsl = FloatArray(3)
                        ColorUtils.colorToHSL(it.rgb, sHsl)
                        sHsl[1] > 0.25f && sHsl[2] in 0.30f..0.85f
                    }.maxByOrNull { it.population }
                    ?: swatches.maxByOrNull {
                        val sHsl = FloatArray(3)
                        ColorUtils.colorToHSL(it.rgb, sHsl)
                        sHsl[1]
                    }

                // 4. Accent color tuning (Vibrant, high energy, anti-mud)
                val accentInt: Int
                val rawHue: Float
                val rawSat: Float
                if (vibrantCandidate != null) {
                    val aHsl = FloatArray(3)
                    ColorUtils.colorToHSL(vibrantCandidate.rgb, aHsl)
                    rawHue = aHsl[0]
                    rawSat = aHsl[1]
                    val rawLight = aHsl[2]

                    if (rawSat < 0.12f) {
                        // Near monochrome / B&W artwork -> clean crisp light silver
                        accentInt = ColorUtils.HSLToColor(floatArrayOf(0f, 0f, 0.88f))
                    } else {
                        var hue = rawHue
                        var sat = rawSat
                        var light = rawLight

                        // Prevent olive drab / mustard mud: shift yellow range (35°..75°) to warm gold (44°)
                        if (calmMode) {
                            if (hue in 35f..75f) {
                                hue = 42f
                                sat = sat.coerceIn(0.35f, 0.52f)
                                light = 0.58f
                            } else {
                                sat = sat.coerceIn(0.28f, 0.54f)
                                light = light.coerceIn(0.54f, 0.68f)
                            }
                        } else if (hue in 35f..75f) {
                            hue = 44f
                            sat = maxOf(sat, 0.92f)
                            light = 0.58f
                        } else {
                            sat = maxOf(sat, 0.72f)
                            light = light.coerceIn(0.50f, 0.70f)
                        }
                        accentInt = ColorUtils.HSLToColor(floatArrayOf(hue, sat, light))
                    }
                } else {
                    rawHue = 0f
                    rawSat = 0f
                    accentInt = 0xFFFFFFFF.toInt()
                }

                // 5. Top Background tuning (Smooth, atmospheric ambient glow)
                val topBgInt: Int
                if (rawSat >= 0.12f) {
                    var bgHue = rawHue
                    var bgSat = rawSat
                    // Mud prevention for background: shift yellow/olive to warm amber
                    if (calmMode) {
                        if (bgHue in 35f..75f) {
                            bgHue = 38f
                        }
                        bgSat = bgSat.coerceIn(0.18f, 0.42f)
                    } else if (bgHue in 35f..75f) {
                        bgHue = 38f
                        bgSat = bgSat.coerceIn(0.40f, 0.75f)
                    } else {
                        bgSat = bgSat.coerceIn(0.45f, 0.80f)
                    }
                    val bgLight = if (calmMode) {
                        if (isDarkArtwork) 0.13f else 0.17f
                    } else {
                        if (isDarkArtwork) 0.16f else 0.22f
                    }
                    topBgInt = ColorUtils.HSLToColor(floatArrayOf(bgHue, bgSat, bgLight))
                } else {
                    // Monochrome / B&W artwork -> deep neutral charcoal
                    topBgInt = ColorUtils.HSLToColor(floatArrayOf(0f, 0f, 0.12f))
                }

                // 6. Bottom Background (Fade into deep rich tone, avoiding pitch black cutoff)
                val bottomHsl = FloatArray(3)
                ColorUtils.colorToHSL(topBgInt, bottomHsl)
                bottomHsl[2] = if (calmMode) {
                    (bottomHsl[2] * 0.55f).coerceIn(0.07f, 0.10f)
                } else {
                    (bottomHsl[2] * 0.50f).coerceIn(0.07f, 0.13f)
                }
                val bottomBgInt = ColorUtils.HSLToColor(bottomHsl)

                val extracted = ExtractedArtworkColors(
                    dominant = Color(dominantInt),
                    backgroundTop = Color(topBgInt),
                    backgroundBottom = Color(bottomBgInt),
                    accent = Color(accentInt)
                )
                cache.put(cacheKey, extracted)
                extracted
            }
        } catch (_: Exception) {
            null
        }
    }
}
