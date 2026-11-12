package io.github.swidegit.gpscam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val CN0_MAX = 50f

private fun signalColor(cn0: Float): Color = when {
    cn0 >= 35f -> Palette.badgeGood
    cn0 >= 25f -> Palette.badgeWarn
    else -> Palette.badgeBad
}

private enum class SatView(val label: Int) { NOW(R.string.view_now), SKY(R.string.view_sky), HISTORY(R.string.view_history), RECEIVER(R.string.view_receiver) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatellitesScreen(gps: GpsState, modifier: Modifier) {
    var view by rememberSaveable { mutableStateOf(SatView.NOW) }
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SatView.entries.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = view == v,
                    onClick = { view = v },
                    shape = SegmentedButtonDefaults.itemShape(i, SatView.entries.size),
                    icon = {},
                    label = { Text(stringResource(v.label), maxLines = 1) },
                )
            }
        }
        val content = Modifier.fillMaxWidth().weight(1f)
        when (view) {
            SatView.NOW -> SatellitesNow(gps, content)
            SatView.SKY -> SkyMapSection(gps, content)
            SatView.HISTORY -> HistorySection(gps, content)
            SatView.RECEIVER -> ReceiverSection(gps, content)
        }
    }
}

@Composable
private fun SatellitesNow(gps: GpsState, modifier: Modifier) {
    val now = rememberNow()
    val sats = gps.satellites.sortedWith(compareByDescending<Satellite> { it.usedInFix }.thenByDescending { it.cn0DbHz })
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FixBadge(gps, now)
                if (!gps.providerEnabled) GpsDisabledNotice(Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                SummaryRow(gps)
                Spacer(Modifier.height(8.dp))
                SkyPlot(sats, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
                Text(stringResource(R.string.legend_used), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                Spacer(Modifier.height(8.dp))
                ConstellationChips(sats)
            }
        }
        if (sats.isEmpty()) {
            item { Text(stringResource(R.string.sats_none), color = Palette.muted, modifier = Modifier.padding(16.dp)) }
        }
        if (sats.isNotEmpty()) item { Column(Modifier.padding(vertical = 8.dp)) { BandStatsCard(sats) } }
        items(sats, key = { "${it.constellation}-${it.svid}-${it.carrierHz}" }) { SatelliteRow(it) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SummaryRow(gps: GpsState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        Labeled(stringResource(R.string.sats_used), "${gps.usedInFix}")
        Labeled(stringResource(R.string.sats_visible), "${gps.satellites.size}")
        Labeled("TTFF", gps.ttffMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "--")
        gps.dop?.let { d ->
            fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
            Labeled(stringResource(R.string.dop), "${f(d.pdop)} / ${f(d.hdop)} / ${f(d.vdop)}")
        }
        gps.fix?.verticalAccuracyM?.let { Labeled(stringResource(R.string.vertical_accuracy), "±${it.toInt()} m") }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConstellationChips(sats: List<Satellite>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sats.groupBy { it.constellation }.toSortedMap().forEach { (c, list) ->
            Text(
                "${c.label} ${list.count { it.usedInFix }}/${list.size}",
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(Color(c.argb), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Polar plot: centre is the zenith, the outer ring the horizon; north up. */
@Composable
private fun SkyPlot(sats: List<Satellite>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val gridColor = Palette.muted.copy(alpha = 0.5f)
    val labelStyle = TextStyle(color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Canvas(modifier.padding(12.dp)) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        listOf(1f, 2f / 3f, 1f / 3f).forEach { drawCircle(gridColor, r * it, c, style = Stroke(1.dp.toPx())) }
        drawLine(gridColor, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        drawLine(gridColor, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, az) ->
            val layout = measurer.measure(label, labelStyle)
            val p = polar(c, r * 0.93f, az)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
        sats.filter { it.elevationDeg > 0f || it.azimuthDeg != 0f }.forEach { s ->
            val p = polar(c, r * (90f - s.elevationDeg.coerceIn(0f, 90f)) / 90f, s.azimuthDeg)
            val color = Color(s.constellation.argb)
            val dot = (4f + 6f * (s.cn0DbHz / CN0_MAX).coerceIn(0f, 1f)).dp.toPx()
            if (s.usedInFix) drawCircle(color, dot, p) else drawCircle(color, dot, p, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun polar(c: Offset, radius: Float, azimuthDeg: Float): Offset {
    val a = Math.toRadians(azimuthDeg.toDouble())
    return Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat())
}

@Composable
private fun SatelliteRow(s: Satellite) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(28.dp).background(Color(s.constellation.argb), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(78.dp)) {
            Text("${s.constellation.shortLabel} ${s.svid}", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Text(s.band ?: "", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        }
        Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))) {
            Box(
                Modifier.fillMaxWidth((s.cn0DbHz / CN0_MAX).coerceIn(0f, 1f)).fillMaxHeight()
                    .background(signalColor(s.cn0DbHz), RoundedCornerShape(5.dp)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "%.0f dB-Hz", s.cn0DbHz), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                String.format(Locale.US, "%.0f° / %.0f°", s.elevationDeg, s.azimuthDeg),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.muted,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(if (s.usedInFix) "✓" else "", color = Palette.good, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
    }
}
