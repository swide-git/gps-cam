package io.github.swidegit.gpscam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

private enum class ChartRange(val label: String, val ms: Long?) {
    TEN_MIN("10 min", 10 * 60_000L),
    HOUR("1 h", 60 * 60_000L),
    ALL("All", null),
}

/** Round axis maximum: 10, 20, 50, 100, 150, 200, 250, 300, 350, 400... */
fun niceAxisMax(value: Double): Double {
    if (value <= 10) return 10.0
    val step = when {
        value <= 20 -> 10.0
        value <= 50 -> 25.0
        else -> 50.0
    }
    return ceil(value / step) * step
}

/** Speed over time for the current trip; x is real time, so tunnels show as gaps. */
@Composable
fun SpeedChart(track: List<TrackPoint>, unit: SpeedUnit, modifier: Modifier = Modifier) {
    var range by rememberSaveable { mutableStateOf(ChartRange.TEN_MIN) }
    val measurer = rememberTextMeasurer()
    val gridColor = Palette.muted.copy(alpha = 0.35f)
    val lineColor = Palette.accent
    val labelStyle = TextStyle(color = Palette.muted, fontSize = 11.sp)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.labelLarge, color = Palette.muted, modifier = Modifier.weight(1f).padding(top = 8.dp))
            ChartRange.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
            }
        }
        val end = track.lastOrNull()?.utcMs ?: 0L
        val windowMs = range.ms ?: (end - (track.firstOrNull()?.utcMs ?: end)).coerceAtLeast(60_000L)
        val start = end - windowMs
        val visible = track.filter { it.utcMs >= start && it.speedMs != null }
        // At most ~600 vertices: enough for a phone width, cheap to redraw every second.
        val stride = (visible.size / 600).coerceAtLeast(1)
        val samples = visible.filterIndexed { i, _ -> i % stride == 0 || i == visible.lastIndex }
        val maxShown = niceAxisMax(samples.maxOfOrNull { unit.fromMetresPerSecond(it.speedMs!!) } ?: 0.0)

        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
            val labelWidth = 34.dp.toPx()
            val plotW = size.width - labelWidth
            val plotH = size.height - 14.dp.toPx()
            for (i in 0..2) {
                val v = maxShown * i / 2
                val y = plotH - plotH * i / 2
                drawLine(gridColor, Offset(labelWidth, y), Offset(size.width, y), 1.dp.toPx())
                val layout = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(layout, topLeft = Offset(0f, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
            }
            val startLabel = measurer.measure("-${Format.duration(windowMs)}", labelStyle)
            drawText(startLabel, topLeft = Offset(labelWidth, size.height - startLabel.size.height))
            val unitLabel = measurer.measure(unit.label, labelStyle)
            drawText(unitLabel, topLeft = Offset(size.width - unitLabel.size.width, size.height - unitLabel.size.height))
            if (samples.size < 2) return@Canvas
            val path = Path()
            var previous: TrackPoint? = null
            for (pt in samples) {
                val x = labelWidth + plotW * ((pt.utcMs - start).toFloat() / windowMs)
                val y = plotH - plotH * (unit.fromMetresPerSecond(pt.speedMs!!) / maxShown).toFloat()
                // Break the line across gaps longer than 10 s (tunnels, app in the background).
                if (previous == null || pt.utcMs - previous.utcMs > 10_000) path.moveTo(x, y) else path.lineTo(x, y)
                previous = pt
            }
            drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
