package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeMode

@Composable
fun AppleThemeIcon(
    mode: AppThemeMode,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = minOf(w, h) / 2f

        when (mode) {
            AppThemeMode.LIGHT -> {
                // Sun icon
                val coreRadius = radius * 0.48f
                drawCircle(color = tint, radius = coreRadius, center = center, style = Fill)
                val rayCount = 8
                val innerR = radius * 0.68f
                val outerR = radius * 0.95f
                for (i in 0 until rayCount) {
                    val angle = (i * (2 * Math.PI / rayCount)).toFloat()
                    val start = Offset(
                        center.x + innerR * kotlin.math.cos(angle),
                        center.y + innerR * kotlin.math.sin(angle)
                    )
                    val end = Offset(
                        center.x + outerR * kotlin.math.cos(angle),
                        center.y + outerR * kotlin.math.sin(angle)
                    )
                    drawLine(color = tint, start = start, end = end, strokeWidth = 1.8f)
                }
            }
            AppThemeMode.DARK -> {
                // Crescent Moon icon
                val moonPath = Path().apply {
                    addArc(
                        oval = androidx.compose.ui.geometry.Rect(
                            center.x - radius * 0.85f,
                            center.y - radius * 0.85f,
                            center.x + radius * 0.85f,
                            center.y + radius * 0.85f
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 240f
                    )
                    quadraticBezierTo(
                        x1 = center.x + radius * 0.2f,
                        y1 = center.y - radius * 0.1f,
                        x2 = center.x,
                        y2 = center.y - radius * 0.85f
                    )
                    close()
                }
                drawPath(path = moonPath, color = tint, style = Fill)
            }
            AppThemeMode.SYSTEM -> {
                // Auto / Split Yin-Yang Style Theme Circle (Apple System Theme Icon)
                drawCircle(
                    color = tint,
                    radius = radius * 0.85f,
                    center = center,
                    style = Stroke(width = 1.6f)
                )
                drawArc(
                    color = tint,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(center.x - radius * 0.85f, center.y - radius * 0.85f),
                    size = Size(radius * 1.7f, radius * 1.7f),
                    style = Fill
                )
            }
        }
    }
}
