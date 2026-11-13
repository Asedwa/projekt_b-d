package org.kde.plasmawave.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ArtworkFramedView(
    artworkUrl: String?,
    frame: AlbumCoverFrame,
    accentColor: Color,
    isPlaying: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "frameAnim")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing)
        ),
        label = "rotation"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (isPlaying) 0.95f else 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing)
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (frame) {
            AlbumCoverFrame.MODERN_ROUNDED -> {
                ArtworkImage(
                    url = artworkUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(12.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    background = Color.Black.copy(alpha = 0.25f)
                )
            }

            AlbumCoverFrame.VINYL_RECORD -> {
                // Vinyl Record with spinning LP peeking out to the right
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 1. Vinyl LP Disc (peeking out from the sleeve)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(size * 0.92f)
                            .offset(x = size * 0.10f)
                            .rotate(rotation),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = this.size.minDimension / 2f
                            val c = Offset(r, r)

                            // Outer black vinyl
                            drawCircle(Color(0xFF141416), radius = r, center = c)

                            // Vinyl grooves (concentric etched rings)
                            val grooveColor = Color(0xFF28282D)
                            val highlightColor = Color.White.copy(alpha = 0.08f)
                            for (step in 1..9) {
                                val gr = r * (0.38f + step * 0.06f)
                                drawCircle(grooveColor, radius = gr, center = c, style = Stroke(width = 1.dp.toPx()))
                            }
                            drawCircle(highlightColor, radius = r * 0.72f, center = c, style = Stroke(width = 2.dp.toPx()))
                            drawCircle(highlightColor, radius = r * 0.52f, center = c, style = Stroke(width = 1.5.dp.toPx()))

                            // Center Label disc
                            drawCircle(accentColor, radius = r * 0.34f, center = c)
                            drawCircle(Color.Black.copy(alpha = 0.25f), radius = r * 0.34f, center = c, style = Stroke(width = 2.dp.toPx()))

                            // Spindle hole
                            drawCircle(Color.Black, radius = r * 0.08f, center = c)
                            drawCircle(Color.White.copy(alpha = 0.4f), radius = r * 0.08f, center = c, style = Stroke(width = 1.dp.toPx()))
                        }
                    }

                    // 2. Vinyl Sleeve Jacket (Artwork)
                    ArtworkImage(
                        url = artworkUrl,
                        modifier = Modifier
                            .size(size * 0.88f)
                            .shadow(16.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        background = Color.Black.copy(alpha = 0.30f)
                    )
                }
            }

            AlbumCoverFrame.NEON_GLOW -> {
                // Cyberpunk / Neon Glow frame with pulsed accent borders
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer diffuse glow layer
                    Box(
                        modifier = Modifier
                            .size(size * 0.96f)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        accentColor.copy(alpha = glowAlpha * 0.5f),
                                        accentColor.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Cyber corner cut frame
                    Box(
                        modifier = Modifier
                            .size(size * 0.92f)
                            .border(2.5.dp, accentColor.copy(alpha = glowAlpha), RoundedCornerShape(18.dp))
                            .padding(4.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        ArtworkImage(
                            url = artworkUrl,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            background = Color.Black.copy(alpha = 0.25f)
                        )
                    }

                    // Decorative cyber edge corner markers
                    Canvas(modifier = Modifier.size(size * 0.94f)) {
                        val w = this.size.width
                        val h = this.size.height
                        val cLen = 14.dp.toPx()
                        val strokeW = 3.dp.toPx()
                        val color = Color.White

                        // Top-left bracket
                        drawLine(color, Offset(0f, cLen), Offset(0f, 0f), strokeWidth = strokeW)
                        drawLine(color, Offset(0f, 0f), Offset(cLen, 0f), strokeWidth = strokeW)

                        // Top-right bracket
                        drawLine(color, Offset(w - cLen, 0f), Offset(w, 0f), strokeWidth = strokeW)
                        drawLine(color, Offset(w, 0f), Offset(w, cLen), strokeWidth = strokeW)

                        // Bottom-left bracket
                        drawLine(color, Offset(0f, h - cLen), Offset(0f, h), strokeWidth = strokeW)
                        drawLine(color, Offset(0f, h), Offset(cLen, h), strokeWidth = strokeW)

                        // Bottom-right bracket
                        drawLine(color, Offset(w - cLen, h), Offset(w, h), strokeWidth = strokeW)
                        drawLine(color, Offset(w, h), Offset(w, h - cLen), strokeWidth = strokeW)
                    }
                }
            }

            AlbumCoverFrame.RETRO_CASSETTE -> {
                // Vintage 90s audio cassette tape shell
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(14.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1B1B1E))
                        .border(1.5.dp, Color(0xFF333338), RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Upper cassette artwork label
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.68f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            ArtworkImage(
                                url = artworkUrl,
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(10.dp),
                                background = Color.Black
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Lower cassette spools & magnetic tape window
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.32f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F0F12))
                                .border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left rotating reel
                                CassetteSpool(rotation = rotation, accent = accentColor)

                                // Center tape view ruler
                                Canvas(modifier = Modifier.width(60.dp).height(12.dp)) {
                                    val barW = 1.dp.toPx()
                                    val cWidth = this.size.width
                                    for (i in 0..12) {
                                        val x = i * (cWidth / 12f)
                                        val h = if (i % 3 == 0) 12.dp.toPx() else 6.dp.toPx()
                                        drawLine(Color.White.copy(alpha = 0.35f), Offset(x, 6.dp.toPx() - h / 2f), Offset(x, 6.dp.toPx() + h / 2f), strokeWidth = barW)
                                    }
                                }

                                // Right rotating reel
                                CassetteSpool(rotation = rotation, accent = accentColor)
                            }
                        }
                    }
                }
            }

            AlbumCoverFrame.NOTHING_DOTS -> {
                // Nothing OS inspired industrial dot-matrix frame
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(12.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF111114))
                        .border(1.5.dp, Color(0xFF2C2C32), RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ArtworkImage(
                        url = artworkUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        background = Color.Black.copy(alpha = 0.35f)
                    )

                    // Dot matrix overlay corners & Nothing signature red LED
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = this.size.width
                        val h = this.size.height

                        // Signature Nothing red LED dot at top-right
                        drawCircle(
                            color = Color(0xFFE50914),
                            radius = 3.5.dp.toPx(),
                            center = Offset(w - 6.dp.toPx(), 6.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.8f),
                            radius = 1.2.dp.toPx(),
                            center = Offset(w - 7.dp.toPx(), 5.dp.toPx())
                        )

                        // Dotted grid corner markings
                        val dotColor = Color.White.copy(alpha = 0.5f)
                        val dotR = 1.2.dp.toPx()
                        val step = 5.dp.toPx()

                        // Top-left 3x3 matrix dots
                        for (r in 0..2) {
                            for (c in 0..2) {
                                drawCircle(dotColor, radius = dotR, center = Offset(6.dp.toPx() + c * step, 6.dp.toPx() + r * step))
                            }
                        }
                        // Bottom-left 3x3 matrix dots
                        for (r in 0..2) {
                            for (c in 0..2) {
                                drawCircle(dotColor, radius = dotR, center = Offset(6.dp.toPx() + c * step, h - 6.dp.toPx() - r * step))
                            }
                        }
                        // Bottom-right 3x3 matrix dots
                        for (r in 0..2) {
                            for (c in 0..2) {
                                drawCircle(dotColor, radius = dotR, center = Offset(w - 6.dp.toPx() - c * step, h - 6.dp.toPx() - r * step))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CassetteSpool(rotation: Float, accent: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .rotate(rotation),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(r, r)

            // Outer white plastic spool
            drawCircle(Color.White.copy(alpha = 0.85f), radius = r, center = c)
            drawCircle(Color(0xFF222226), radius = r * 0.72f, center = c)

            // Spool gear teeth (6 teeth)
            val teethColor = Color.White
            val toothW = 2.dp.toPx()
            for (i in 0..5) {
                val angle = i * (Math.PI / 3.0)
                val x1 = (c.x + Math.cos(angle) * r * 0.40f).toFloat()
                val y1 = (c.y + Math.sin(angle) * r * 0.40f).toFloat()
                val x2 = (c.x + Math.cos(angle) * r * 0.72f).toFloat()
                val y2 = (c.y + Math.sin(angle) * r * 0.72f).toFloat()
                drawLine(teethColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = toothW)
            }

            // Center axle hole
            drawCircle(Color(0xFF111114), radius = r * 0.35f, center = c)
        }
    }
}
