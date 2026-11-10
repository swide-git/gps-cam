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
fun SpeedChart(track: List<TrackPoint>, unit: SpeedUnit, modifier: Modifier = Modifier, showRanges: Boolean = true) {
    TrackChart(
        title = stringResource(R.string.chart_title),
        track = track,
        unitLabel = unit.label,
        valueOf = { p -> p.speedMs?.let { unit.fromMetresPerSecond(it) } },
        fromZero = true,
        showRanges = showRanges,
        modifier = modifier,
    )
}

/**
 * A value of the track over real time. [fromZero] starts the axis at 0 (speed); otherwise it
 * spans the data (altitude). Without [showRanges] the whole track is shown.
 */
@Composable
fun TrackChart(
    title: String,
    track: List<TrackPoint>,
    unitLabel: String,
    valueOf: (TrackPoint) -> Double?,
    fromZero: Boolean,
    showRanges: Boolean,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable { mutableStateOf(if (showRanges) ChartRange.TEN_MIN else ChartRange.ALL) }
    val measurer = rememberTextMeasurer()
    val gridColor = Palette.muted.copy(alpha = 0.35f)
    val lineColor = Palette.accent
    val labelStyle = TextStyle(color = Palette.muted, fontSize = 11.sp)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.muted, modifier = Modifier.weight(1f).padding(top = 8.dp))
            if (showRanges) {
                ChartRange.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                }
            }
        }
        val end = track.lastOrNull()?.utcMs ?: 0L
        val windowMs = range.ms ?: (end - (track.firstOrNull()?.utcMs ?: end)).coerceAtLeast(60_000L)
        val start = end - windowMs
        val visible = track.filter { it.utcMs >= start && valueOf(it) != null }
        // At most ~600 vertices: enough for a phone width, cheap to redraw every second.
        val stride = (visible.size / 600).coerceAtLeast(1)
        val samples = visible.filterIndexed { i, _ -> i % stride == 0 || i == visible.lastIndex }
        val values = samples.map { valueOf(it)!! }
        val (axisMin, axisMax) = if (fromZero) {
            0.0 to niceAxisMax(values.maxOrNull() ?: 0.0)
        } else {
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            val pad = ((hi - lo) * 0.1).coerceAtLeast(5.0)
            kotlin.math.floor((lo - pad) / 10) * 10 to kotlin.math.ceil((hi + pad) / 10) * 10
        }
        val span = (axisMax - axisMin).coerceAtLeast(1e-6)

        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
            val labelWidth = 38.dp.toPx()
            val plotW = size.width - labelWidth
            val plotH = size.height - 14.dp.toPx()
            for (i in 0..2) {
                val v = axisMin + span * i / 2
                val y = plotH - plotH * i / 2
                drawLine(gridColor, Offset(labelWidth, y), Offset(size.width, y), 1.dp.toPx())
                val layout = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(layout, topLeft = Offset(0f, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
            }
            val startLabel = measurer.measure("-${Format.duration(windowMs)}", labelStyle)
            drawText(startLabel, topLeft = Offset(labelWidth, size.height - startLabel.size.height))
            val unitText = measurer.measure(unitLabel, labelStyle)
            drawText(unitText, topLeft = Offset(size.width - unitText.size.width, size.height - unitText.size.height))
            if (samples.size < 2) return@Canvas
            val path = Path()
            var previous: TrackPoint? = null
            samples.forEachIndexed { i, pt ->
                val x = labelWidth + plotW * ((pt.utcMs - start).toFloat() / windowMs)
                val y = plotH - plotH * ((values[i] - axisMin) / span).toFloat()
                // Break the line across gaps longer than 10 s (tunnels, app in the background).
                if (previous == null || pt.utcMs - previous!!.utcMs > 10_000) path.moveTo(x, y) else path.lineTo(x, y)
                previous = pt
            }
            drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
