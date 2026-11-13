package org.kde.plasmawave.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.theme.LocalPlasmaColors
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun CustomSeekbar(
    positionMs: Long,
    durationMs: Long,
    style: SeekbarStyle,
    primaryColor: Color,
    trackColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    showTimeLabels: Boolean = true
) {
    val colors = LocalPlasmaColors.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val totalDuration = durationMs.coerceAtLeast(1L)
    val currentFraction = if (isDragging) {
        dragFraction
    } else {
        (positionMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    }

    val shownMs = if (isDragging) {
        (dragFraction * totalDuration).toLong()
    } else {
        positionMs
    }.coerceIn(0L, totalDuration)

    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying && (style == SeekbarStyle.WAVY || style == SeekbarStyle.NYAN_CAT)) 2f * PI.toFloat() else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing)
        ),
        label = "phase"
    )

    val barPattern = remember {
        floatArrayOf(
            0.35f, 0.55f, 0.85f, 0.65f, 0.95f, 0.75f, 0.45f, 0.60f,
            0.80f, 1.00f, 0.70f, 0.40f, 0.50f, 0.85f, 0.90f, 0.60f,
            0.45f, 0.75f, 0.95f, 0.80f, 0.55f, 0.35f, 0.65f, 0.90f,
            0.75f, 0.50f, 0.80f, 1.00f, 0.65f, 0.45f, 0.70f, 0.85f,
            0.50f, 0.40f, 0.60f, 0.35f
        )
    }

    Column(modifier = modifier) {
        val barHeight = when (style) {
            SeekbarStyle.CLASSIC -> 28.dp
            SeekbarStyle.WAVY -> 32.dp
            SeekbarStyle.DOTS -> 28.dp
            SeekbarStyle.GLOW_CAPSULE -> 28.dp
            SeekbarStyle.BARS -> 36.dp
            SeekbarStyle.NYAN_CAT -> 36.dp
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .pointerInput(totalDuration) {
                    detectTapGestures(
                        onPress = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            val released = tryAwaitRelease()
                            if (released) {
                                onSeekTo((dragFraction * totalDuration).toLong())
                            }
                            isDragging = false
                        }
                    )
                }
                .pointerInput(totalDuration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeekTo((dragFraction * totalDuration).toLong())
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val progressX = (currentFraction * width).coerceIn(0f, width)

            when (style) {
                SeekbarStyle.CLASSIC -> {
                    val trackHeight = 4.dp.toPx()
                    // Background track
                    drawLine(
                        color = trackColor,
                        start = Offset(0f, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round
                    )
                    // Active progress line
                    if (progressX > 0f) {
                        drawLine(
                            color = primaryColor,
                            start = Offset(0f, centerY),
                            end = Offset(progressX, centerY),
                            strokeWidth = trackHeight,
                            cap = StrokeCap.Round
                        )
                    }
                    // Thumb
                    val thumbRadius = if (isDragging) 8.dp.toPx() else 6.dp.toPx()
                    drawCircle(
                        color = primaryColor,
                        radius = thumbRadius,
                        center = Offset(progressX, centerY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius * 0.45f,
                        center = Offset(progressX, centerY)
                    )
                }

                SeekbarStyle.WAVY -> {
                    val waveThickness = 4.dp.toPx()
                    val amplitude = 5.dp.toPx()
                    val wavelength = 28.dp.toPx()

                    // Unplayed straight line
                    if (progressX < width) {
                        drawLine(
                            color = trackColor,
                            start = Offset(progressX, centerY),
                            end = Offset(width, centerY),
                            strokeWidth = waveThickness,
                            cap = StrokeCap.Round
                        )
                    }

                    // Played wavy sine wave
                    if (progressX > 0f) {
                        val path = Path()
                        path.moveTo(0f, centerY)
                        var x = 0f
                        val step = 2f
                        while (x <= progressX) {
                            val waveY = centerY + sin((x / wavelength) * (2 * PI.toFloat()) - phase) * amplitude
                            path.lineTo(x, waveY)
                            x += step
                        }
                        drawPath(
                            path = path,
                            color = primaryColor,
                            style = Stroke(width = waveThickness, cap = StrokeCap.Round)
                        )
                    }

                    // Thumb
                    val thumbY = if (progressX > 0f) {
                        centerY + sin((progressX / wavelength) * (2 * PI.toFloat()) - phase) * amplitude
                    } else centerY

                    val thumbRadius = if (isDragging) 8.dp.toPx() else 6.dp.toPx()
                    drawCircle(
                        color = primaryColor,
                        radius = thumbRadius,
                        center = Offset(progressX, thumbY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius * 0.45f,
                        center = Offset(progressX, thumbY)
                    )
                }

                SeekbarStyle.DOTS -> {
                    // Nothing OS style dot matrix
                    val dotCount = 28
                    val dotRadius = 4.dp.toPx()
                    val step = (width - 2 * dotRadius) / (dotCount - 1).coerceAtLeast(1)

                    for (i in 0 until dotCount) {
                        val cx = dotRadius + i * step
                        val isPassed = cx <= progressX
                        val isCurrent = (cx - progressX).let { it >= -step / 2f && it <= step / 2f }

                        val color = if (isPassed || isCurrent) primaryColor else trackColor
                        val radius = if (isCurrent) dotRadius * 1.35f else dotRadius

                        drawCircle(
                            color = color,
                            radius = radius,
                            center = Offset(cx, centerY)
                        )
                        if (isCurrent) {
                            drawCircle(
                                color = Color.White,
                                radius = radius * 0.5f,
                                center = Offset(cx, centerY)
                            )
                        }
                    }
                }

                SeekbarStyle.GLOW_CAPSULE -> {
                    val capsuleHeight = 14.dp.toPx()
                    val cornerRadius = CornerRadius(capsuleHeight / 2f, capsuleHeight / 2f)
                    val top = centerY - capsuleHeight / 2f

                    // Outer track capsule
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, top),
                        size = Size(width, capsuleHeight),
                        cornerRadius = cornerRadius
                    )

                    // Inner progress capsule
                    if (progressX > capsuleHeight) {
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(0f, top),
                            size = Size(progressX, capsuleHeight),
                            cornerRadius = cornerRadius
                        )
                    } else if (progressX > 0f) {
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(0f, top),
                            size = Size(progressX, capsuleHeight),
                            cornerRadius = CornerRadius(progressX / 2f, progressX / 2f)
                        )
                    }

                    // Indicator thumb pill
                    if (progressX > 0f) {
                        val pillWidth = 5.dp.toPx()
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset((progressX - pillWidth / 2f).coerceAtLeast(0f), top + 2.dp.toPx()),
                            size = Size(pillWidth, capsuleHeight - 4.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }
                }

                SeekbarStyle.BARS -> {
                    // Audio equalizer waveform bars
                    val barCount = 36
                    val barWidth = 3.5.dp.toPx()
                    val gap = (width - (barCount * barWidth)) / (barCount - 1).coerceAtLeast(1)
                    val maxBarHeight = height * 0.75f
                    val minBarHeight = 8.dp.toPx()

                    for (i in 0 until barCount) {
                        val bx = i * (barWidth + gap)
                        val frac = barPattern[i % barPattern.size]
                        val h = minBarHeight + (maxBarHeight - minBarHeight) * frac
                        val by = centerY - h / 2f
                        val isPassed = (bx + barWidth / 2f) <= progressX
                        val color = if (isPassed) primaryColor else trackColor

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(bx, by),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }

                SeekbarStyle.NYAN_CAT -> {
                    val trackHeight = 16.dp.toPx()
                    val trackRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    val top = centerY - trackHeight / 2f

                    // 1. Deep Space Track Background
                    drawRoundRect(
                        color = Color(0xFF081426),
                        topLeft = Offset(0f, top),
                        size = Size(width, trackHeight),
                        cornerRadius = trackRadius
                    )

                    // Twinkling Space Stars across the remaining track
                    val starPositions = floatArrayOf(0.12f, 0.28f, 0.44f, 0.62f, 0.78f, 0.92f)
                    for (i in starPositions.indices) {
                        val sx = width * starPositions[i]
                        val sy = centerY + if (i % 2 == 0) -3.dp.toPx() else 3.dp.toPx()
                        val starAlpha = (0.4f + 0.6f * sin(phase * 3f + i.toFloat() * 1.5f)).coerceIn(0.2f, 1f)
                        val starColor = Color(0xFFE0F7FA).copy(alpha = starAlpha)

                        val starArm = 2.5.dp.toPx()
                        drawLine(starColor, Offset(sx - starArm, sy), Offset(sx + starArm, sy), strokeWidth = 1.2.dp.toPx())
                        drawLine(starColor, Offset(sx, sy - starArm), Offset(sx, sy + starArm), strokeWidth = 1.2.dp.toPx())
                    }

                    // 2. Rainbow Trail behind Nyan Cat
                    if (progressX > 0f) {
                        val rainbow = listOf(
                            Color(0xFFFF0000), // Red
                            Color(0xFFFF9900), // Orange
                            Color(0xFFFFFF00), // Yellow
                            Color(0xFF33FF00), // Green
                            Color(0xFF0099FF), // Cyan/Blue
                            Color(0xFF6633FF)  // Purple
                        )
                        val bandH = trackHeight / rainbow.size

                        for (b in rainbow.indices) {
                            val by = top + b * bandH
                            val color = rainbow[b]

                            val path = Path()
                            path.moveTo(0f, by)

                            var x = 0f
                            val step = 8f
                            while (x <= progressX) {
                                val wave = if (isPlaying) sin(phase * 4f + x * 0.08f) * 1.5.dp.toPx() else 0f
                                path.lineTo(x, by + wave)
                                x += step
                            }
                            path.lineTo(progressX, by + bandH)
                            x = progressX
                            while (x >= 0f) {
                                val wave = if (isPlaying) sin(phase * 4f + x * 0.08f) * 1.5.dp.toPx() else 0f
                                path.lineTo(x, by + bandH + wave)
                                x -= step
                            }
                            path.close()
                            drawPath(path, color)
                        }
                    }

                    // 3. Pixel Nyan Cat Thumb at progressX
                    val catW = 32.dp.toPx()
                    val catH = 22.dp.toPx()
                    val catLeft = (progressX - catW * 0.6f).coerceIn(0f, width - catW)
                    val catBob = if (isPlaying) sin(phase * 8f) * 1.5.dp.toPx() else 0f
                    val catTop = centerY - catH / 2f + catBob

                    // Cookie Pop-Tart Crust
                    val pastryW = catW * 0.68f
                    val pastryH = catH * 0.88f
                    drawRoundRect(
                        color = Color(0xFFFFCC99),
                        topLeft = Offset(catLeft, catTop + catH * 0.06f),
                        size = Size(pastryW, pastryH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )

                    // Pink Strawberry Frosting
                    val fPad = 2.dp.toPx()
                    drawRoundRect(
                        color = Color(0xFFFC9ACB),
                        topLeft = Offset(catLeft + fPad, catTop + catH * 0.06f + fPad),
                        size = Size(pastryW - fPad * 2f, pastryH - fPad * 2f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )

                    // Magenta Sprinkles
                    val sprinkleColor = Color(0xFFFF3399)
                    val sprinkles = listOf(
                        Offset(0.25f, 0.30f),
                        Offset(0.50f, 0.35f),
                        Offset(0.70f, 0.28f),
                        Offset(0.35f, 0.65f),
                        Offset(0.65f, 0.70f)
                    )
                    for (sp in sprinkles) {
                        drawCircle(
                            color = sprinkleColor,
                            radius = 1.2.dp.toPx(),
                            center = Offset(catLeft + pastryW * sp.x, catTop + pastryH * sp.y)
                        )
                    }

                    // Gray Cat Tail (bobbing on left)
                    val tailBob = if (isPlaying) sin(phase * 8f) * 2.dp.toPx() else 0f
                    val tailPath = Path()
                    tailPath.moveTo(catLeft, catTop + pastryH * 0.5f)
                    tailPath.lineTo(catLeft - 6.dp.toPx(), catTop + pastryH * 0.3f + tailBob)
                    tailPath.lineTo(catLeft - 4.dp.toPx(), catTop + pastryH * 0.7f + tailBob)
                    tailPath.close()
                    drawPath(tailPath, Color(0xFF9E9E9E))

                    // Cat Feet
                    val footBob = if (isPlaying) sin(phase * 8f) * 1.5.dp.toPx() else 0f
                    drawRoundRect(Color(0xFF9E9E9E), Offset(catLeft + 3.dp.toPx(), catTop + pastryH + footBob), Size(3.5.dp.toPx(), 3.5.dp.toPx()), CornerRadius(1.dp.toPx(), 1.dp.toPx()))
                    drawRoundRect(Color(0xFF9E9E9E), Offset(catLeft + pastryW * 0.45f, catTop + pastryH - footBob), Size(3.5.dp.toPx(), 3.5.dp.toPx()), CornerRadius(1.dp.toPx(), 1.dp.toPx()))

                    // Gray Cat Head (on the right)
                    val headW = catW * 0.44f
                    val headH = catH * 0.68f
                    val headLeft = catLeft + pastryW * 0.65f
                    val headTop = catTop + catH * 0.12f

                    // Ears
                    val earColor = Color(0xFF9E9E9E)
                    val earPath = Path()
                    earPath.moveTo(headLeft + 2.dp.toPx(), headTop)
                    earPath.lineTo(headLeft + 6.dp.toPx(), headTop - 5.dp.toPx())
                    earPath.lineTo(headLeft + 10.dp.toPx(), headTop)
                    earPath.moveTo(headLeft + headW - 10.dp.toPx(), headTop)
                    earPath.lineTo(headLeft + headW - 6.dp.toPx(), headTop - 5.dp.toPx())
                    earPath.lineTo(headLeft + headW - 2.dp.toPx(), headTop)
                    drawPath(earPath, earColor)

                    // Head Base
                    drawRoundRect(
                        color = Color(0xFF9E9E9E),
                        topLeft = Offset(headLeft, headTop),
                        size = Size(headW, headH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )

                    // Eyes (Black with White glint)
                    drawCircle(Color.Black, radius = 1.6.dp.toPx(), center = Offset(headLeft + headW * 0.38f, headTop + headH * 0.42f))
                    drawCircle(Color.White, radius = 0.6.dp.toPx(), center = Offset(headLeft + headW * 0.35f, headTop + headH * 0.38f))
                    drawCircle(Color.Black, radius = 1.6.dp.toPx(), center = Offset(headLeft + headW * 0.78f, headTop + headH * 0.42f))
                    drawCircle(Color.White, radius = 0.6.dp.toPx(), center = Offset(headLeft + headW * 0.75f, headTop + headH * 0.38f))

                    // Rosy Cheeks
                    drawCircle(Color(0xFFFF9999), radius = 1.8.dp.toPx(), center = Offset(headLeft + headW * 0.18f, headTop + headH * 0.68f))
                    drawCircle(Color(0xFFFF9999), radius = 1.8.dp.toPx(), center = Offset(headLeft + headW * 0.90f, headTop + headH * 0.68f))

                    // Nose / Mouth dot
                    drawCircle(Color(0xFF424242), radius = 0.8.dp.toPx(), center = Offset(headLeft + headW * 0.58f, headTop + headH * 0.55f))
                }
            }
        }

        if (showTimeLabels) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(shownMs.coerceAtLeast(0).toULong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
                Text(
                    text = formatDuration(totalDuration.coerceAtLeast(0).toULong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}
