package com.example.chirp.sonar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * A sonar "beam" fanning out from the phone's bottom edge (bottom centre of the view).
 * Pulses ripple outward while [active]; the target blip sits at [distanceM] along the beam.
 */
@Composable
fun RadarView(
    distanceM: Double?,
    maxRangeM: Double,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse",
    )
    val glow by transition.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow",
    )
    val target = distanceM?.let { (it / maxRangeM).coerceIn(0.03, 1.0).toFloat() }
    val blip by animateFloatAsState(target ?: 0f, spring(stiffness = 300f), label = "blip")
    val blipAlpha by animateFloatAsState(if (target != null) 1f else 0f, tween(300), label = "blipAlpha")

    Canvas(modifier.fillMaxWidth().aspectRatio(1.7f)) {
        val origin = Offset(size.width / 2, size.height)
        val radius = minOf(size.width / 2, size.height) * 0.96f
        val sweep = 120f
        val start = 270f - sweep / 2

        // Beam background.
        drawArc(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.04f)), origin, radius,
            ),
            start, sweep, useCenter = true,
            topLeft = Offset(origin.x - radius, origin.y - radius), size = Size(radius * 2, radius * 2),
        )

        // Range rings with labels.
        for (k in 1..4) {
            val r = radius * k / 4
            arc(grid, origin, r, start, sweep, 1.5f)
            val text = measurer.measure("%.1f m".format(maxRangeM * k / 4), TextStyle(fontSize = 10.sp, color = label))
            drawText(text, topLeft = Offset(origin.x + 6f, origin.y - r - text.size.height))
        }
        // Beam edges.
        listOf(start, start + sweep).forEach { a ->
            val rad = Math.toRadians(a.toDouble())
            drawLine(grid, origin, Offset(origin.x + radius * kotlin.math.cos(rad).toFloat(), origin.y + radius * kotlin.math.sin(rad).toFloat()), 1.5f)
        }

        // Outgoing chirps.
        if (active) for (i in 0..1) {
            val p = (pulse + i * 0.5f) % 1f
            arc(accent.copy(alpha = (1f - p) * 0.8f), origin, radius * p, start, sweep, 4f)
        }

        // Target.
        if (blipAlpha > 0f) {
            val c = Offset(origin.x, origin.y - radius * blip)
            drawCircle(accent.copy(alpha = 0.25f * glow * blipAlpha), 34f * glow, c)
            drawCircle(accent.copy(alpha = 0.5f * blipAlpha), 18f, c)
            drawCircle(accent.copy(alpha = blipAlpha), 10f, c)
            // Arc of the echo across the beam: it's a range, not a bearing.
            arc(accent.copy(alpha = 0.6f * blipAlpha), origin, radius * blip, start + 15f, sweep - 30f, 3f)
        }

        // The phone.
        drawCircle(accent, 7f, origin)
    }
}

private fun DrawScope.arc(color: Color, origin: Offset, r: Float, start: Float, sweep: Float, width: Float) {
    if (r <= 0f) return
    drawArc(
        color, start, sweep, useCenter = false,
        topLeft = Offset(origin.x - r, origin.y - r), size = Size(r * 2, r * 2), style = Stroke(width),
    )
}
