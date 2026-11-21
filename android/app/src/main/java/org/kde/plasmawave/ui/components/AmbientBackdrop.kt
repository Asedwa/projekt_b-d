package org.kde.plasmawave.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
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
    );

    companion object {
        fun fromId(id: String?): AmbientBackdropStyle =
            entries.firstOrNull { it.id == id || it.name == id } ?: SILK_AURORA
    }
}

val LocalAmbientBackdropEnabled = compositionLocalOf { false }

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
    val orbs = Array(4) { MutableOrb() }
    val ribbons = Array(3) { MutableRibbon() }
    val rings = Array(10) { MutableRing() }
    var orbCount = 0
    var ribbonCount = 0
    var ringCount = 0
    val ribbonPath = Path()
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
            scratch.ribbonCount = 2
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
        }
    }
}

/**
 * Low-memory, zero-FFI animated ambient backdrop supporting 6 visual styles.
 *
 * Uses zero offscreen bitmaps, zero blur buffers, and pre-allocated frame scratch
 * buffers: normalized orb, ribbon, and ring/particle geometry is computed in-place
 * per draw frame and rendered directly with GPU vector gradients inside Canvas.
 */
@Composable
fun AmbientBackdropLayer(
    style: AmbientBackdropStyle = AmbientBackdropStyle.SILK_AURORA,
    modifier: Modifier = Modifier
) {
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryArgb = MaterialTheme.colorScheme.secondary.toArgb()
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()

    val transition = rememberInfiniteTransition(label = "ambientBackdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientPhase"
    )

    val scratch = remember { AmbientFrameScratch() }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawAmbientBackdrop(
            style = style,
            phase = phase,
            primaryArgb = primaryArgb,
            secondaryArgb = secondaryArgb,
            backgroundArgb = backgroundArgb,
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

    val minDim = minOf(w, h)
    val ribbonPath = scratch.ribbonPath

    // 1. Soft drifting ambient radial glow orbs
    for (i in 0 until scratch.orbCount) {
        val orb = scratch.orbs[i]
        val center = Offset(orb.cx * w, orb.cy * h)
        val radius = (orb.radius * minDim).coerceAtLeast(1f)
        val tint = Color(orb.colorArgb)
        val alpha = orb.alpha.coerceIn(0f, 0.45f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to tint.copy(alpha = alpha),
                    0.45f to tint.copy(alpha = alpha * 0.42f),
                    1.0f to Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }

    // 2. Flowing translucent silk-ribbon / wave / prism bands
    for (i in 0 until scratch.ribbonCount) {
        val ribbon = scratch.ribbons[i]
        val tint = Color(ribbon.colorArgb)
        val alpha = ribbon.alpha.coerceIn(0f, 0.35f)
        val bandPx = ribbon.thickness * minDim

        val start = Offset(ribbon.x0 * w, ribbon.y0 * h)
        val c1 = Offset(ribbon.cx1 * w, ribbon.cy1 * h)
        val c2 = Offset(ribbon.cx2 * w, ribbon.cy2 * h)
        val end = Offset(ribbon.x1 * w, ribbon.y1 * h)

        ribbonPath.reset()
        ribbonPath.moveTo(start.x, start.y)
        ribbonPath.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        ribbonPath.lineTo(end.x, end.y + bandPx)
        ribbonPath.cubicTo(
            c2.x, c2.y + bandPx * 0.85f,
            c1.x, c1.y + bandPx * 0.55f,
            start.x, start.y + bandPx * 0.25f
        )
        ribbonPath.close()

        drawPath(
            path = ribbonPath,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.28f to tint.copy(alpha = alpha * 0.55f),
                    0.65f to tint.copy(alpha = alpha),
                    1.0f to Color.Transparent
                ),
                start = start,
                end = Offset(end.x, end.y + bandPx)
            )
        )

        // Luminous crest along top edge
        ribbonPath.reset()
        ribbonPath.moveTo(start.x, start.y)
        ribbonPath.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        drawPath(
            path = ribbonPath,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.35f to tint.copy(alpha = (alpha * 1.35f).coerceAtMost(0.40f)),
                    0.75f to tint.copy(alpha = (alpha * 1.1f).coerceAtMost(0.35f)),
                    1.0f to Color.Transparent
                ),
                start = start,
                end = end
            ),
            style = Stroke(width = (bandPx * 0.06f).coerceIn(1.5f, 4f), cap = StrokeCap.Round)
        )
    }

    // 3. Concentric neon rings OR glowing starlight bokeh particles
    for (i in 0 until scratch.ringCount) {
        val ring = scratch.rings[i]
        val center = Offset(ring.cx * w, ring.cy * h)
        val radius = (ring.radius * minDim).coerceAtLeast(1.5f)
        val tint = Color(ring.colorArgb)
        val alpha = ring.alpha.coerceIn(0f, 0.45f)
        if (ring.strokeWidth > 0f) {
            val strokePx = (ring.strokeWidth * minDim).coerceIn(1.2f, 5f)
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = strokePx)
            )
        } else {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = (alpha * 1.2f).coerceAtMost(0.65f)),
                        0.35f to tint.copy(alpha = alpha),
                        1.0f to Color.Transparent
                    ),
                    center = center,
                    radius = radius * 2.2f
                ),
                radius = radius * 2.2f,
                center = center
            )
        }
    }
}
