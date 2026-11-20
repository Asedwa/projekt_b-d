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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import uniffi.plasmawave_core.computeAmbientBackdropFrame

val LocalAmbientBackdropEnabled = compositionLocalOf { false }

/**
 * Low-memory, Rust-computed animated ambient aurora & silk-ribbon backdrop.
 *
 * Uses zero offscreen bitmaps and zero blur buffers: Rust computes compact
 * normalized orb and ribbon geometry per draw frame, and Compose renders them
 * directly with GPU vector gradients inside Canvas (zero recompositions).
 */
@Composable
fun AmbientBackdropLayer(modifier: Modifier = Modifier) {
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb().toUInt()
    val secondaryArgb = MaterialTheme.colorScheme.secondary.toArgb().toUInt()
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb().toUInt()

    val transition = rememberInfiniteTransition(label = "rustAmbientBackdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientPhase"
    )

    val ribbonPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val frame = computeAmbientBackdropFrame(
            phase = phase,
            primaryArgb = primaryArgb,
            secondaryArgb = secondaryArgb,
            backgroundArgb = backgroundArgb
        )

        val minDim = minOf(w, h)

        // 1. Soft drifting ambient radial glow orbs
        for (orb in frame.orbs) {
            val center = Offset(orb.cx * w, orb.cy * h)
            val radius = (orb.radius * minDim).coerceAtLeast(1f)
            val tint = Color(orb.colorArgb.toInt())
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

        // 2. Flowing translucent silk-ribbon aurora waves (header highlight)
        for (ribbon in frame.ribbons) {
            val tint = Color(ribbon.colorArgb.toInt())
            val alpha = ribbon.alpha.coerceIn(0f, 0.35f)
            val bandPx = ribbon.thickness * minDim

            val start = Offset(ribbon.x0 * w, ribbon.y0 * h)
            val c1 = Offset(ribbon.cx1 * w, ribbon.cy1 * h)
            val c2 = Offset(ribbon.cx2 * w, ribbon.cy2 * h)
            val end = Offset(ribbon.x1 * w, ribbon.y1 * h)

            // Filled translucent silk ribbon body
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

            // Crisp luminous silk crest along the top edge of the ribbon
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
    }
}
