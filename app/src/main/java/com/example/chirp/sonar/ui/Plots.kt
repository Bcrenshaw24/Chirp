package com.example.chirp.sonar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One evenly spaced line spanning the full x axis. */
class Series(val y: DoubleArray, val color: Color, val width: Float = 2f)

/** A vertical line at [x] (0..1 of the axis). */
class Marker(val x: Double, val color: Color, val width: Float = 2f)

@Composable
fun PlotCard(title: String, caption: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun AxisLabels(vararg labels: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun LinePlot(
    series: List<Series>,
    yMin: Double,
    yMax: Double,
    markers: List<Marker> = emptyList(),
    height: Dp = 150.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(height)) {
        frame(grid)
        markers.forEach { m ->
            val x = (m.x.coerceIn(0.0, 1.0) * size.width).toFloat()
            drawLine(m.color, Offset(x, 0f), Offset(x, size.height), m.width)
        }
        series.forEach { s ->
            if (s.y.size < 2) return@forEach
            val path = Path()
            s.y.forEachIndexed { i, v ->
                val x = i.toFloat() / (s.y.size - 1) * size.width
                val y = yPx(v, yMin, yMax)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(s.width))
        }
    }
}

/** Min/max per pixel column: shows every sample's extent without drawing thousands of segments. */
@Composable
fun WaveformPlot(samples: DoubleArray, scale: Double, markers: List<Marker> = emptyList(), height: Dp = 120.dp) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(height)) {
        frame(grid)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        if (samples.isEmpty()) return@Canvas
        val columns = minOf(samples.size, size.width.toInt().coerceAtLeast(1))
        for (c in 0 until columns) {
            val a = c * samples.size / columns
            val b = maxOf(a + 1, (c + 1) * samples.size / columns)
            var lo = samples[a]; var hi = samples[a]
            for (i in a until b) { lo = minOf(lo, samples[i]); hi = maxOf(hi, samples[i]) }
            val x = c.toFloat() / columns * size.width
            drawLine(color, Offset(x, yPx(hi, -scale, scale)), Offset(x, yPx(lo, -scale, scale)), 1.5f)
        }
        markers.forEach { m ->
            val x = (m.x.coerceIn(0.0, 1.0) * size.width).toFloat()
            drawLine(m.color, Offset(x, 0f), Offset(x, size.height), m.width)
        }
    }
}

/** Points (raw nearest echoes) and a line (tracked distance) over a time window. */
@Composable
fun HistoryPlot(points: List<HistoryPoint>, windowNs: Long, maxRangeM: Double, height: Dp = 160.dp) {
    val raw = MaterialTheme.colorScheme.outline
    val tentative = MaterialTheme.colorScheme.tertiary
    val confirmed = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(height)) {
        frame(grid)
        val end = points.lastOrNull()?.timeNs ?: return@Canvas
        fun x(t: Long) = (1f - (end - t).toFloat() / windowNs) * size.width
        points.forEach { p -> p.nearestM?.let { drawCircle(raw, 3f, Offset(x(p.timeNs), yPx(it, 0.0, maxRangeM))) } }
        points.zipWithNext().forEach { (a, b) ->
            if (a.trackedM != null && b.trackedM != null) {
                drawLine(if (b.confirmed) confirmed else tentative,
                    Offset(x(a.timeNs), yPx(a.trackedM, 0.0, maxRangeM)),
                    Offset(x(b.timeNs), yPx(b.trackedM, 0.0, maxRangeM)), 4f)
            }
        }
    }
}

private fun DrawScope.yPx(v: Double, min: Double, max: Double): Float =
    (size.height * (1 - ((v - min) / (max - min)).coerceIn(0.0, 1.0))).toFloat()

private fun DrawScope.frame(color: Color) {
    drawRect(color, style = Stroke(1f))
    for (k in 1..3) drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
}
