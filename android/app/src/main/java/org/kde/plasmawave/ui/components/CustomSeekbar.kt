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
        targetValue = if (isPlaying && style == SeekbarStyle.WAVY) 2f * PI.toFloat() else 0f,
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
