package org.kde.plasmawave.ui.components

import android.graphics.LinearGradient as AndroidLinearGradient
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class AmbientBackdropStyle(
    val id: String,
    val title: String,
    val subtitle: String
) {
    SILK_AURORA(
        id = "silk_aurora",
        title = "İpek Aurora",
        subtitle = "Üst köşeden süzülen zarif ipek ışık şeritleri"
    ),
    NEBULA_ORBS(
        id = "nebula_orbs",
        title = "Nebula Küreleri",
        subtitle = "Yumuşakça süzülen derin uzay ışık bulutları"
    ),
    OCEAN_WAVES(
        id = "ocean_waves",
        title = "Okyanus Dalgaları",
        subtitle = "Üst ve altta sakin salınan katmanlı dalgalar"
    ),
    CYBER_PULSE(
        id = "cyber_pulse",
        title = "Siber Halkalar",
        subtitle = "Nefes alan konsantrik neon ışık halkaları"
    ),
    STARLIGHT_DRIFT(
        id = "starlight_drift",
        title = "Yıldız Tozu",
        subtitle = "Kuzey ışığı perdesi ve parıldayan ışık zerrecikleri"
    ),
    PRISM_BEAMS(
        id = "prism_beams",
        title = "Prizma Hüzmeleri",
        subtitle = "Üst köşelerden çapraz süzülen sahne ışıkları"
    ),
    AURORA_CURTAINS(
        id = "aurora_curtains",
        title = "Kuzey Perdesi",
        subtitle = "Dikey süzülen katmanlı kuzey ışığı perdeleri"
    ),
    SOLAR_FLARE(
        id = "solar_flare",
        title = "Güneş Patlaması",
        subtitle = "Köşeden yayılan sıcak plazma halkaları"
    ),
    MATRIX_RAIN(
        id = "matrix_rain",
        title = "Dijital Yağmur",
        subtitle = "Aşağı süzülen neon şeritler ve ışık izleri"
    ),
    VELVET_RIBBONS(
        id = "velvet_ribbons",
        title = "Kadife Dalgalar",
        subtitle = "Çapraz kesişen geniş ve yumuşak ışık şeritleri"
    ),
    COSMIC_VORTEX(
        id = "cosmic_vortex",
        title = "Kozmik Girdap",
        subtitle = "Yörüngede dönen spiral küreler ve halkalar"
    ),
    FIREFLY_MEADOW(
        id = "firefly_meadow",
        title = "Ateş Böcekleri",
        subtitle = "Organik yörüngelerde süzülen sıcak parıltılar"
    );

    companion object {
        fun fromId(id: String?): AmbientBackdropStyle =
            entries.firstOrNull { it.id == id || it.name == id } ?: SILK_AURORA
    }
}

val LocalAmbientBackdropEnabled = compositionLocalOf { false }

/**
 * Lightweight scroll-activity bridge so scrolling lists (e.g. HomeScreen LazyColumn) can freeze
 * background ambient shader invalidations during active flings without recomposing parent scopes.
 */
@Stable
class AmbientScrollPauseHolder {
    var isScrolling by mutableStateOf(false)
}

val LocalAmbientScrollPause = compositionLocalOf<AmbientScrollPauseHolder?> { null }

private fun withAlphaArgb(rgb: Int, alpha255: Int): Int =
    (alpha255.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)

internal class CachedColorShaders(val rgb: Int) {
    val orbRadial = AndroidRadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            withAlphaArgb(rgb, 255),
            withAlphaArgb(rgb, 107),
            withAlphaArgb(rgb, 0)
        ),
        floatArrayOf(0f, 0.45f, 1f),
        AndroidShader.TileMode.CLAMP
    )
    val particleRadial = AndroidRadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            0xFFFFFFFF.toInt(),
            withAlphaArgb(rgb, 213),
            withAlphaArgb(rgb, 0)
        ),
        floatArrayOf(0f, 0.35f, 1f),
        AndroidShader.TileMode.CLAMP
    )
    val ribbonFill = AndroidLinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(
            withAlphaArgb(rgb, 0),
            withAlphaArgb(rgb, 140),
            withAlphaArgb(rgb, 255),
            withAlphaArgb(rgb, 0)
        ),
        floatArrayOf(0f, 0.28f, 0.65f, 1f),
        AndroidShader.TileMode.CLAMP
    )
    val ribbonCrest = AndroidLinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(
            withAlphaArgb(rgb, 0),
            withAlphaArgb(rgb, 255),
            withAlphaArgb(rgb, 208),
            withAlphaArgb(rgb, 0)
        ),
        floatArrayOf(0f, 0.35f, 0.75f, 1f),
        AndroidShader.TileMode.CLAMP
    )
}

internal class MutableOrb(
    var cx: Float = 0f,
    var cy: Float = 0f,
    var radius: Float = 0f,
    var colorArgb: Int = 0,
    var alpha: Float = 0f
) {
    fun set(cx: Float, cy: Float, radius: Float, colorArgb: Int, alpha: Float) {
        this.cx = cx
        this.cy = cy
        this.radius = radius
        this.colorArgb = colorArgb
        this.alpha = alpha
    }
}

internal class MutableRibbon(
    var x0: Float = 0f,
    var y0: Float = 0f,
    var cx1: Float = 0f,
    var cy1: Float = 0f,
    var cx2: Float = 0f,
    var cy2: Float = 0f,
    var x1: Float = 0f,
    var y1: Float = 0f,
    var thickness: Float = 0f,
    var colorArgb: Int = 0,
    var alpha: Float = 0f
) {
    fun set(
        x0: Float, y0: Float, cx1: Float, cy1: Float, cx2: Float, cy2: Float,
        x1: Float, y1: Float, thickness: Float, colorArgb: Int, alpha: Float
    ) {
        this.x0 = x0
        this.y0 = y0
        this.cx1 = cx1
        this.cy1 = cy1
        this.cx2 = cx2
        this.cy2 = cy2
        this.x1 = x1
        this.y1 = y1
        this.thickness = thickness
        this.colorArgb = colorArgb
        this.alpha = alpha
    }
}

internal class MutableRing(
    var cx: Float = 0f,
    var cy: Float = 0f,
    var radius: Float = 0f,
    var strokeWidth: Float = 0f,
    var colorArgb: Int = 0,
    var alpha: Float = 0f
) {
    fun set(cx: Float, cy: Float, radius: Float, strokeWidth: Float, colorArgb: Int, alpha: Float) {
        this.cx = cx
        this.cy = cy
        this.radius = radius
        this.strokeWidth = strokeWidth
        this.colorArgb = colorArgb
        this.alpha = alpha
    }
}

internal class AmbientFrameScratch {
    val orbs = Array(5) { MutableOrb() }
    val ribbons = Array(4) { MutableRibbon() }
    val rings = Array(10) { MutableRing() }
    var orbCount = 0
    var ribbonCount = 0
    var ringCount = 0
    val ribbonPath = AndroidPath()
    val shaderMatrix = AndroidMatrix()
    val matrixValues = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.FILL
        color = -0x1
    }
    val crestPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        color = -0x1
    }
    val ringStrokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
    }

    private val shaderCache = arrayOfNulls<CachedColorShaders>(8)
    private var nextEvictSlot = 0

    fun shadersFor(colorArgb: Int): CachedColorShaders {
        val rgb = colorArgb and 0x00FFFFFF
        for (i in shaderCache.indices) {
            val entry = shaderCache[i] ?: break
            if (entry.rgb == rgb) return entry
        }
        val created = CachedColorShaders(rgb)
        for (i in shaderCache.indices) {
            if (shaderCache[i] == null) {
                shaderCache[i] = created
                return created
            }
        }
        val slot = nextEvictSlot
        nextEvictSlot = (nextEvictSlot + 1) % shaderCache.size
        shaderCache[slot] = created
        return created
    }
}

private const val TAU_F = (2.0 * PI).toFloat()
private const val PI_F = PI.toFloat()

private val STARLIGHT_SEEDS = arrayOf(
    floatArrayOf(0.16f, 0.08f, 0.018f, 0.0f),
    floatArrayOf(0.84f, 0.07f, 0.024f, 0.7f),
    floatArrayOf(0.62f, 0.16f, 0.015f, 1.4f),
    floatArrayOf(0.28f, 0.22f, 0.021f, 2.1f),
    floatArrayOf(0.88f, 0.27f, 0.016f, 2.8f),
    floatArrayOf(0.12f, 0.44f, 0.022f, 3.5f),
    floatArrayOf(0.76f, 0.54f, 0.019f, 4.2f),
    floatArrayOf(0.34f, 0.68f, 0.016f, 4.9f),
    floatArrayOf(0.84f, 0.80f, 0.023f, 5.5f),
    floatArrayOf(0.20f, 0.86f, 0.018f, 6.0f)
)

private fun blendArgb(a: Int, b: Int, t: Float): Int {
    val clamped = t.coerceIn(0f, 1f)
    val inv = 1f - clamped
    val ar = ((a ushr 16) and 0xFF).toFloat()
    val ag = ((a ushr 8) and 0xFF).toFloat()
    val ab = (a and 0xFF).toFloat()
    val br = ((b ushr 16) and 0xFF).toFloat()
    val bg = ((b ushr 8) and 0xFF).toFloat()
    val bb = (b and 0xFF).toFloat()
    val r = (ar * inv + br * clamped).roundToInt().coerceIn(0, 255)
    val g = (ag * inv + bg * clamped).roundToInt().coerceIn(0, 255)
    val bl = (ab * inv + bb * clamped).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun fillAmbientFrame(
    scratch: AmbientFrameScratch,
    style: AmbientBackdropStyle,
    phase: Float,
    primaryArgb: Int,
    secondaryArgb: Int,
    backgroundArgb: Int
) {
    val wrapped = ((phase % 1f) + 1f) % 1f
    val tau = wrapped * TAU_F
    val s1 = sin(tau)
    val c1 = cos(tau)
    val s2 = sin(tau * 2f + 0.85f)
    val c2 = cos(tau * 2f + 0.85f)
    val s3 = sin(tau * 3f + 1.7f)

    val white = 0xFFFFFFFF.toInt()
    val softPrimary = blendArgb(primaryArgb, white, 0.16f)
    val deepPrimary = blendArgb(primaryArgb, backgroundArgb, 0.35f)
    val midAccent = blendArgb(primaryArgb, secondaryArgb, 0.48f)
    val brightAccent = blendArgb(secondaryArgb, white, 0.22f)

    when (style) {
        AmbientBackdropStyle.NEBULA_ORBS -> {
            scratch.orbCount = 4
            scratch.ribbonCount = 0
            scratch.ringCount = 0
            scratch.orbs[0].set(0.75f + 0.14f * c1, 0.12f + 0.07f * s1, 0.82f + 0.06f * s2, softPrimary, 0.26f + 0.04f * s1)
            scratch.orbs[1].set(0.22f - 0.12f * s1, 0.26f + 0.08f * c2, 0.70f + 0.05f * c1, midAccent, 0.20f + 0.03f * c1)
            scratch.orbs[2].set(0.80f - 0.15f * c2, 0.62f + 0.09f * s1, 0.75f + 0.07f * s1, deepPrimary, 0.22f + 0.03f * s2)
            scratch.orbs[3].set(0.24f + 0.14f * s2, 0.88f - 0.06f * c1, 0.78f + 0.05f * c2, brightAccent, 0.18f + 0.03f * c2)
        }
        AmbientBackdropStyle.OCEAN_WAVES -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 3
            scratch.ringCount = 0
            scratch.orbs[0].set(0.50f + 0.10f * s1, 0.04f, 0.88f, softPrimary, 0.18f)
            scratch.orbs[1].set(0.50f - 0.10f * c1, 0.96f, 0.82f, midAccent, 0.16f)
            scratch.ribbons[0].set(
                -0.08f, 0.07f + 0.025f * s1,
                0.30f + 0.05f * c1, 0.01f - 0.03f * c1,
                0.68f - 0.05f * s1, 0.14f + 0.03f * s2,
                1.08f, 0.06f - 0.02f * c1,
                0.10f + 0.015f * s1, softPrimary, 0.20f + 0.03f * c1
            )
            scratch.ribbons[1].set(
                -0.08f, 0.13f - 0.02f * c1,
                0.35f - 0.04f * s2, 0.20f + 0.025f * s1,
                0.72f + 0.04f * c1, 0.07f - 0.025f * c2,
                1.08f, 0.15f + 0.02f * s1,
                0.085f + 0.01f * c2, midAccent, 0.15f + 0.025f * s1
            )
            scratch.ribbons[2].set(
                -0.08f, 0.86f + 0.02f * c2,
                0.34f + 0.05f * s1, 0.80f - 0.025f * c1,
                0.70f - 0.05f * c1, 0.92f + 0.025f * s1,
                1.08f, 0.85f - 0.02f * s2,
                0.095f, deepPrimary, 0.16f
            )
        }
        AmbientBackdropStyle.CYBER_PULSE -> {
            val centerX = 0.50f + 0.03f * s1
            val centerY = 0.13f + 0.02f * c1
            scratch.orbCount = 2
            scratch.ribbonCount = 0
            scratch.ringCount = 4
            scratch.orbs[0].set(centerX, centerY, 0.68f + 0.06f * s1, softPrimary, 0.22f + 0.03f * s1)
            scratch.orbs[1].set(0.78f - 0.08f * c1, 0.90f + 0.03f * s1, 0.65f, midAccent, 0.16f)
            for (i in 0 until 4) {
                val p = (((wrapped + i * 0.25f) % 1f) + 1f) % 1f
                val radius = 0.12f + p * 0.58f
                val fade = (1f - p) * (0.20f + 0.80f * sin(p * PI_F))
                scratch.rings[i].set(
                    centerX,
                    centerY,
                    radius,
                    0.005f + (1f - p) * 0.004f,
                    if (i % 2 == 0) softPrimary else brightAccent,
                    (0.24f * fade).coerceIn(0.01f, 0.26f)
                )
            }
        }
        AmbientBackdropStyle.STARLIGHT_DRIFT -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 1
            scratch.ringCount = STARLIGHT_SEEDS.size
            scratch.orbs[0].set(0.68f + 0.08f * c1, 0.10f + 0.04f * s1, 0.74f, softPrimary, 0.19f)
            scratch.orbs[1].set(0.25f - 0.06f * s1, 0.82f + 0.05f * c1, 0.68f, midAccent, 0.15f)
            scratch.ribbons[0].set(
                0.15f + 0.04f * s1, -0.03f,
                0.42f - 0.05f * c1, 0.14f + 0.03f * s2,
                0.74f + 0.04f * s1, 0.03f - 0.03f * c1,
                1.06f, 0.16f + 0.02f * c2,
                0.09f, softPrimary, 0.15f
            )
            for (idx in STARLIGHT_SEEDS.indices) {
                val seed = STARLIGHT_SEEDS[idx]
                val bx = seed[0]
                val by = seed[1]
                val rad = seed[2]
                val offset = seed[3]
                val twinkle = 0.5f + 0.5f * sin(tau * 2f + offset)
                val dx = 0.03f * cos(tau + offset)
                val dy = 0.025f * sin(tau + offset * 1.3f)
                scratch.rings[idx].set(
                    bx + dx,
                    by + dy,
                    rad * (0.85f + 0.30f * twinkle),
                    0f,
                    if (idx % 2 == 0) brightAccent else softPrimary,
                    0.12f + 0.22f * twinkle
                )
            }
        }
        AmbientBackdropStyle.PRISM_BEAMS -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 3
            scratch.ringCount = 0
            scratch.orbs[0].set(0.82f + 0.06f * c1, 0.04f, 0.76f, softPrimary, 0.21f)
            scratch.orbs[1].set(0.18f - 0.06f * s1, 0.06f, 0.68f, midAccent, 0.17f)
            scratch.ribbons[0].set(
                0.96f, -0.05f,
                0.72f + 0.05f * s1, 0.12f,
                0.45f + 0.06f * c1, 0.24f,
                0.08f + 0.05f * s2, 0.38f,
                0.12f + 0.02f * s1, softPrimary, 0.17f + 0.03f * c1
            )
            scratch.ribbons[1].set(
                0.64f + 0.04f * c1, -0.05f,
                0.52f - 0.04f * s1, 0.10f,
                0.36f + 0.05f * c2, 0.20f,
                -0.04f, 0.28f + 0.03f * s1,
                0.085f + 0.015f * c2, brightAccent, 0.13f + 0.02f * s3
            )
            scratch.ribbons[2].set(
                0.04f, -0.04f,
                0.32f + 0.04f * c1, 0.09f,
                0.64f - 0.04f * s1, 0.18f,
                1.04f, 0.27f + 0.02f * c1,
                0.075f, midAccent, 0.12f
            )
        }
        AmbientBackdropStyle.SILK_AURORA -> {
            scratch.orbCount = 3
            scratch.ribbonCount = 3
            scratch.ringCount = 0
            scratch.orbs[0].set(0.72f + 0.08f * c1, 0.06f + 0.04f * s1, 0.78f + 0.05f * s2, softPrimary, 0.22f + 0.03f * s1)
            scratch.orbs[1].set(0.76f - 0.10f * s1, 0.94f + 0.04f * c1, 0.74f + 0.06f * c2, midAccent, 0.19f + 0.03f * c1)
            scratch.orbs[2].set(0.10f + 0.07f * s2, 0.48f + 0.06f * c1, 0.62f + 0.04f * s1, deepPrimary, 0.13f + 0.02f * s2)
            scratch.ribbons[0].set(
                0.22f + 0.05f * c1, -0.04f,
                0.44f + 0.06f * s1, 0.15f + 0.03f * c1,
                0.68f - 0.05f * c2, 0.02f + 0.04f * s1,
                1.08f, 0.13f + 0.03f * c1,
                0.11f + 0.015f * s1, softPrimary, 0.18f + 0.03f * c1
            )
            scratch.ribbons[1].set(
                0.34f - 0.04f * s1, 0.01f,
                0.56f + 0.05f * c1, 0.19f - 0.03f * s1,
                0.78f + 0.04f * s2, 0.06f + 0.03f * c2,
                1.12f, 0.20f - 0.02f * s1,
                0.075f + 0.012f * c2, midAccent, 0.12f + 0.025f * s1
            )
            scratch.ribbons[2].set(
                -0.10f, 0.72f + 0.03f * s2,
                0.28f + 0.05f * c1, 0.63f - 0.03f * s1,
                0.70f - 0.05f * s1, 0.80f + 0.03f * c2,
                1.10f, 0.69f - 0.025f * c1,
                0.10f + 0.012f * s1, brightAccent, 0.14f + 0.025f * c2
            )
        }
        AmbientBackdropStyle.AURORA_CURTAINS -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 4
            scratch.ringCount = 0
            scratch.orbs[0].set(0.45f + 0.12f * s1, 0.05f, 0.90f, softPrimary, 0.20f + 0.03f * c1)
            scratch.orbs[1].set(0.55f - 0.10f * c1, 0.94f, 0.72f, deepPrimary, 0.14f)
            for (i in 0 until 4) {
                val baseX = 0.16f + i * 0.22f
                val sway = 0.06f * sin(tau + i * 1.1f)
                val sway2 = 0.05f * cos(tau * 2f + i * 0.8f)
                scratch.ribbons[i].set(
                    baseX + sway, -0.05f,
                    baseX - sway2, 0.28f,
                    baseX + sway2, 0.62f,
                    baseX - sway, 1.05f,
                    0.11f + 0.02f * sin(tau + i),
                    if (i % 2 == 0) softPrimary else brightAccent,
                    0.14f + 0.04f * (0.5f + 0.5f * sin(tau + i * 1.4f))
                )
            }
        }
        AmbientBackdropStyle.SOLAR_FLARE -> {
            val sunX = 0.88f + 0.03f * c1
            val sunY = 0.08f + 0.03f * s1
            scratch.orbCount = 3
            scratch.ribbonCount = 1
            scratch.ringCount = 4
            scratch.orbs[0].set(sunX, sunY, 0.78f + 0.06f * s1, brightAccent, 0.26f + 0.04f * s1)
            scratch.orbs[1].set(sunX - 0.12f, sunY + 0.10f, 0.62f, softPrimary, 0.20f)
            scratch.orbs[2].set(0.18f + 0.06f * s1, 0.86f - 0.04f * c1, 0.70f, deepPrimary, 0.15f)
            scratch.ribbons[0].set(
                1.05f, 0.02f,
                0.76f + 0.04f * s1, 0.18f + 0.04f * c1,
                0.48f - 0.05f * c2, 0.34f + 0.03f * s2,
                0.06f, 0.62f + 0.04f * s1,
                0.13f, midAccent, 0.14f
            )
            for (i in 0 until 4) {
                val p = (((wrapped + i * 0.25f) % 1f) + 1f) % 1f
                val fade = (1f - p) * sin(p * PI_F)
                scratch.rings[i].set(
                    sunX,
                    sunY,
                    0.14f + p * 0.72f,
                    0.006f + (1f - p) * 0.005f,
                    if (i % 2 == 0) brightAccent else softPrimary,
                    (0.24f * fade).coerceIn(0.01f, 0.24f)
                )
            }
        }
        AmbientBackdropStyle.MATRIX_RAIN -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 4
            scratch.ringCount = 8
            scratch.orbs[0].set(0.50f, 0.10f + 0.04f * s1, 0.80f, deepPrimary, 0.18f)
            scratch.orbs[1].set(0.50f, 0.90f - 0.04f * c1, 0.75f, softPrimary, 0.14f)
            val cols = floatArrayOf(0.14f, 0.38f, 0.62f, 0.86f)
            for (i in 0 until 4) {
                val x = cols[i] + 0.015f * sin(tau + i)
                val drop = (((wrapped + i * 0.23f) % 1f) + 1f) % 1f
                val headY = -0.15f + drop * 1.30f
                scratch.ribbons[i].set(
                    x, headY - 0.35f,
                    x + 0.01f, headY - 0.20f,
                    x - 0.01f, headY - 0.08f,
                    x, headY,
                    0.022f,
                    if (i % 2 == 0) brightAccent else softPrimary,
                    0.16f + 0.06f * sin(drop * PI_F)
                )
            }
            for (i in 0 until 8) {
                val seed = STARLIGHT_SEEDS[i]
                val drop = (((wrapped * 1.5f + i * 0.125f) % 1f) + 1f) % 1f
                scratch.rings[i].set(
                    seed[0],
                    -0.05f + drop * 1.10f,
                    0.012f + 0.006f * (i % 3),
                    0f,
                    if (i % 2 == 0) brightAccent else softPrimary,
                    0.18f * sin(drop * PI_F).coerceAtLeast(0.05f)
                )
            }
        }
        AmbientBackdropStyle.VELVET_RIBBONS -> {
            scratch.orbCount = 2
            scratch.ribbonCount = 4
            scratch.ringCount = 0
            scratch.orbs[0].set(0.24f + 0.08f * c1, 0.24f + 0.06f * s1, 0.76f, deepPrimary, 0.17f)
            scratch.orbs[1].set(0.76f - 0.08f * s1, 0.76f - 0.06f * c1, 0.76f, midAccent, 0.17f)
            scratch.ribbons[0].set(
                -0.10f, 0.22f + 0.05f * s1,
                0.32f, 0.08f + 0.06f * c1,
                0.68f, 0.38f - 0.06f * s2,
                1.10f, 0.24f + 0.05f * c1,
                0.15f, softPrimary, 0.18f
            )
            scratch.ribbons[1].set(
                -0.10f, 0.56f - 0.05f * c1,
                0.30f, 0.72f - 0.05f * s1,
                0.70f, 0.40f + 0.05f * c2,
                1.10f, 0.54f - 0.04f * s1,
                0.13f, midAccent, 0.15f
            )
            scratch.ribbons[2].set(
                -0.10f, 0.84f + 0.04f * s2,
                0.35f, 0.68f + 0.04f * c1,
                0.65f, 0.94f - 0.04f * s1,
                1.10f, 0.78f + 0.04f * c2,
                0.12f, brightAccent, 0.13f
            )
            scratch.ribbons[3].set(
                0.12f + 0.04f * c1, -0.08f,
                0.40f, 0.32f + 0.05f * s1,
                0.62f, 0.68f - 0.05f * c1,
                0.92f - 0.04f * s1, 1.08f,
                0.10f, deepPrimary, 0.14f
            )
        }
        AmbientBackdropStyle.COSMIC_VORTEX -> {
            val cx = 0.50f
            val cy = 0.46f
            scratch.orbCount = 4
            scratch.ribbonCount = 0
            scratch.ringCount = 3
            for (i in 0 until 4) {
                val angle = tau + i * (PI_F * 0.5f)
                val dist = 0.24f + 0.04f * sin(tau * 2f + i)
                scratch.orbs[i].set(
                    cx + dist * cos(angle),
                    cy + dist * 0.75f * sin(angle),
                    0.56f + 0.06f * sin(tau + i),
                    when (i) {
                        0 -> softPrimary
                        1 -> midAccent
                        2 -> brightAccent
                        else -> deepPrimary
                    },
                    0.20f + 0.03f * cos(tau + i)
                )
            }
            for (i in 0 until 3) {
                val pulse = 0.5f + 0.5f * sin(tau + i * 1.2f)
                scratch.rings[i].set(
                    cx,
                    cy,
                    0.18f + i * 0.13f + 0.02f * pulse,
                    0.004f,
                    if (i % 2 == 0) softPrimary else brightAccent,
                    0.12f + 0.08f * pulse
                )
            }
        }
        AmbientBackdropStyle.FIREFLY_MEADOW -> {
            scratch.orbCount = 3
            scratch.ribbonCount = 0
            scratch.ringCount = STARLIGHT_SEEDS.size
            scratch.orbs[0].set(0.26f + 0.08f * s1, 0.82f + 0.04f * c1, 0.78f, deepPrimary, 0.18f)
            scratch.orbs[1].set(0.76f - 0.08f * c1, 0.76f - 0.04f * s1, 0.72f, midAccent, 0.16f)
            scratch.orbs[2].set(0.50f + 0.06f * s2, 0.18f + 0.05f * c2, 0.68f, softPrimary, 0.15f)
            for (idx in STARLIGHT_SEEDS.indices) {
                val seed = STARLIGHT_SEEDS[idx]
                val offset = seed[3]
                val glow = 0.5f + 0.5f * sin(tau * 2f + offset * 1.5f)
                val x = seed[0] + 0.06f * sin(tau + offset)
                val y = seed[1] + 0.05f * cos(tau * 1.3f + offset * 0.9f)
                scratch.rings[idx].set(
                    x,
                    y,
                    seed[2] * (1.1f + 0.45f * glow),
                    0f,
                    if (idx % 3 == 0) brightAccent else softPrimary,
                    0.14f + 0.24f * glow
                )
            }
        }
    }
}

/**
 * Low-memory, zero-FFI animated ambient backdrop supporting 6 visual styles.
 *
 * Uses zero offscreen bitmaps, zero blur buffers, and pre-allocated frame scratch
 * buffers: normalized orb, ribbon, and ring/particle geometry is computed in-place
 * per draw frame, rendered directly with GPU vector gradients inside Canvas,
 * and pauses automatically when covered by full-screen sheets.
 */
private object AmbientPhaseClock {
    private const val CYCLE_NANOS = 22_000_000_000L
    private const val MAX_STEP_NANOS = 50_000_000L
    private var accumulatedNanos: Long = Math.floorMod(System.nanoTime(), CYCLE_NANOS)
    private var lastFrameNanos: Long = 0L

    fun currentPhase(): Float = accumulatedNanos.toFloat() / CYCLE_NANOS.toFloat()

    fun advance(frameTimeNanos: Long): Float {
        if (frameTimeNanos == lastFrameNanos) {
            return currentPhase()
        }
        val prev = lastFrameNanos
        if (prev != 0L) {
            val delta = (frameTimeNanos - prev).coerceIn(0L, MAX_STEP_NANOS)
            accumulatedNanos = (accumulatedNanos + delta) % CYCLE_NANOS
        }
        lastFrameNanos = frameTimeNanos
        return currentPhase()
    }
}

private const val AMBIENT_FRAME_INTERVAL_NANOS = 33_333_333L // ~30 FPS for 22s slow ambient drift

@Composable
fun AmbientBackdropLayer(
    style: AmbientBackdropStyle = AmbientBackdropStyle.SILK_AURORA,
    paused: Boolean = false,
    pauseDuringScroll: Boolean = false,
    phaseOverride: Float? = null,
    primaryOverride: Color? = null,
    secondaryOverride: Color? = null,
    backgroundOverride: Color? = null,
    intensityMultiplier: Float = 1f,
    modifier: Modifier = Modifier
) {
    val primaryArgb = (primaryOverride ?: MaterialTheme.colorScheme.primary).toArgb()
    val secondaryArgb = (secondaryOverride ?: MaterialTheme.colorScheme.secondary).toArgb()
    val backgroundArgb = (backgroundOverride ?: MaterialTheme.colorScheme.background).toArgb()

    val scrollPauseHolder = if (pauseDuringScroll) LocalAmbientScrollPause.current else null
    val animationFrozen = paused || phaseOverride != null || (scrollPauseHolder?.isScrolling == true)
    val phaseState = remember { mutableFloatStateOf(AmbientPhaseClock.currentPhase()) }

    LaunchedEffect(animationFrozen) {
        if (!animationFrozen) {
            var lastEmitNanos = 0L
            while (isActive) {
                withInfiniteAnimationFrameNanos { frameTimeNanos ->
                    if (lastEmitNanos == 0L || frameTimeNanos - lastEmitNanos >= AMBIENT_FRAME_INTERVAL_NANOS) {
                        lastEmitNanos = frameTimeNanos
                        phaseState.floatValue = AmbientPhaseClock.advance(frameTimeNanos)
                    }
                }
            }
        }
    }

    val scratch = remember { AmbientFrameScratch() }
    val canvasModifier = if (phaseOverride == null) {
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (paused || intensityMultiplier <= 0f) 0f else 1f
                compositingStrategy = CompositingStrategy.Offscreen
            }
    } else {
        modifier.fillMaxSize()
    }

    Canvas(modifier = canvasModifier) {
        if (paused || intensityMultiplier <= 0f) return@Canvas
        val currentPhase = phaseOverride ?: phaseState.floatValue
        drawAmbientBackdrop(
            style = style,
            phase = currentPhase,
            primaryArgb = primaryArgb,
            secondaryArgb = secondaryArgb,
            backgroundArgb = backgroundArgb,
            intensityMultiplier = intensityMultiplier,
            scratch = scratch
        )
    }
}

internal fun DrawScope.drawAmbientBackdrop(
    style: AmbientBackdropStyle,
    phase: Float,
    primaryArgb: Int,
    secondaryArgb: Int,
    backgroundArgb: Int,
    intensityMultiplier: Float = 1f,
    scratch: AmbientFrameScratch
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    fillAmbientFrame(
        scratch = scratch,
        style = style,
        phase = phase,
        primaryArgb = primaryArgb,
        secondaryArgb = secondaryArgb,
        backgroundArgb = backgroundArgb
    )

    val gain = intensityMultiplier.coerceIn(0f, 2.2f)
    val minDim = minOf(w, h)
    val ribbonPath = scratch.ribbonPath
    val matrix = scratch.shaderMatrix
    val matVals = scratch.matrixValues
    val fillPaint = scratch.fillPaint
    val crestPaint = scratch.crestPaint
    val ringStrokePaint = scratch.ringStrokePaint

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas

        // 1. Soft drifting ambient radial glow orbs (zero-alloc unit RadialGradient + Matrix)
        for (i in 0 until scratch.orbCount) {
            val orb = scratch.orbs[i]
            val alpha = (orb.alpha * gain).coerceIn(0f, 0.58f)
            val paintAlpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            if (paintAlpha <= 0) continue

            val cx = orb.cx * w
            val cy = orb.cy * h
            val radius = (orb.radius * minDim).coerceAtLeast(1f)
            val shader = scratch.shadersFor(orb.colorArgb).orbRadial

            matrix.setScale(radius, radius)
            matrix.postTranslate(cx, cy)
            shader.setLocalMatrix(matrix)

            fillPaint.shader = shader
            fillPaint.alpha = paintAlpha
            native.drawCircle(cx, cy, radius, fillPaint)
        }

        // 2. Flowing translucent silk-ribbon / wave / prism bands (zero-alloc unit LinearGradient + Matrix)
        for (i in 0 until scratch.ribbonCount) {
            val ribbon = scratch.ribbons[i]
            val alpha = (ribbon.alpha * gain).coerceIn(0f, 0.48f)
            val fillAlpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            if (fillAlpha <= 0) continue

            val bandPx = ribbon.thickness * minDim
            val x0 = ribbon.x0 * w
            val y0 = ribbon.y0 * h
            val cx1 = ribbon.cx1 * w
            val cy1 = ribbon.cy1 * h
            val cx2 = ribbon.cx2 * w
            val cy2 = ribbon.cy2 * h
            val x1 = ribbon.x1 * w
            val y1 = ribbon.y1 * h

            val shaders = scratch.shadersFor(ribbon.colorArgb)

            val fillDx = x1 - x0
            val fillDy = (y1 + bandPx) - y0
            if (fillDx * fillDx + fillDy * fillDy > 1e-3f) {
                ribbonPath.reset()
                ribbonPath.moveTo(x0, y0)
                ribbonPath.cubicTo(cx1, cy1, cx2, cy2, x1, y1)
                ribbonPath.lineTo(x1, y1 + bandPx)
                ribbonPath.cubicTo(
                    cx2, cy2 + bandPx * 0.85f,
                    cx1, cy1 + bandPx * 0.55f,
                    x0, y0 + bandPx * 0.25f
                )
                ribbonPath.close()

                matVals[0] = fillDx
                matVals[1] = -fillDy
                matVals[2] = x0
                matVals[3] = fillDy
                matVals[4] = fillDx
                matVals[5] = y0
                shaderSetAffine(shaders.ribbonFill, matrix, matVals)

                fillPaint.shader = shaders.ribbonFill
                fillPaint.alpha = fillAlpha
                native.drawPath(ribbonPath, fillPaint)
            }

            // Luminous crest along top edge
            val crestDx = x1 - x0
            val crestDy = y1 - y0
            val crestAlpha = ((alpha * 1.35f).coerceAtMost(0.55f) * 255f).roundToInt().coerceIn(0, 255)
            if (crestAlpha > 0 && crestDx * crestDx + crestDy * crestDy > 1e-3f) {
                ribbonPath.reset()
                ribbonPath.moveTo(x0, y0)
                ribbonPath.cubicTo(cx1, cy1, cx2, cy2, x1, y1)

                matVals[0] = crestDx
                matVals[1] = -crestDy
                matVals[2] = x0
                matVals[3] = crestDy
                matVals[4] = crestDx
                matVals[5] = y0
                shaderSetAffine(shaders.ribbonCrest, matrix, matVals)

                crestPaint.strokeWidth = (bandPx * 0.06f).coerceIn(1.5f, 4f)
                crestPaint.shader = shaders.ribbonCrest
                crestPaint.alpha = crestAlpha
                native.drawPath(ribbonPath, crestPaint)
            }
        }

        // 3. Concentric neon rings OR glowing starlight bokeh particles
        for (i in 0 until scratch.ringCount) {
            val ring = scratch.rings[i]
            val cx = ring.cx * w
            val cy = ring.cy * h
            val radius = (ring.radius * minDim).coerceAtLeast(1.5f)
            val alpha = (ring.alpha * gain).coerceIn(0f, 0.58f)
            if (ring.strokeWidth > 0f) {
                val strokeAlpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                if (strokeAlpha <= 0) continue
                ringStrokePaint.strokeWidth = (ring.strokeWidth * minDim).coerceIn(1.2f, 5f)
                ringStrokePaint.color = withAlphaArgb(ring.colorArgb, strokeAlpha)
                native.drawCircle(cx, cy, radius, ringStrokePaint)
            } else {
                val particleAlpha = ((alpha * 1.2f).coerceAtMost(0.65f) * 255f).roundToInt().coerceIn(0, 255)
                if (particleAlpha <= 0) continue
                val outerRadius = radius * 2.2f
                val shader = scratch.shadersFor(ring.colorArgb).particleRadial

                matrix.setScale(outerRadius, outerRadius)
                matrix.postTranslate(cx, cy)
                shader.setLocalMatrix(matrix)

                fillPaint.shader = shader
                fillPaint.alpha = particleAlpha
                native.drawCircle(cx, cy, outerRadius, fillPaint)
            }
        }
    }
}

private fun shaderSetAffine(shader: AndroidShader, matrix: AndroidMatrix, values: FloatArray) {
    matrix.setValues(values)
    shader.setLocalMatrix(matrix)
}
