package io.github.swidegit.gpscam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Red below 20 dB-Hz, yellow around 30, green from 40: the usual reading of C/N0. */
fun cn0Color(cn0: Double): Color = when {
    cn0 <= 20 -> Palette.badgeBad
    cn0 <= 30 -> lerp(Palette.badgeBad, Palette.badgeWarn, ((cn0 - 20) / 10).toFloat())
    cn0 <= 40 -> lerp(Palette.badgeWarn, Palette.badgeGood, ((cn0 - 30) / 10).toFloat())
    else -> Palette.badgeGood
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.accent, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = Palette.muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

/** Systems and bands in view: count, used, mean C/N0, and whether the fix is dual-frequency. */
@Composable
fun BandStatsCard(sats: List<Satellite>) {
    SectionCard(stringResource(R.string.bands_title)) {
        val dual = dualFrequencyInUse(sats)
        Text(
            stringResource(if (dual) R.string.bands_dual else R.string.bands_single),
            color = if (dual) Palette.good else Palette.muted,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row {
            Text(stringResource(R.string.bands_system), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sats_used), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(64.dp))
            Text("C/N0", style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(72.dp))
        }
        for (b in bandStats(sats)) {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp).height(16.dp).background(Color(b.constellation.argb), RoundedCornerShape(2.dp)))
                Text("${b.constellation.label} ${b.band}", modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text("${b.used}/${b.visible}", fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
                Text(
                    String.format(Locale.US, "%.0f dB-Hz", b.meanCn0),
                    fontFamily = FontFamily.Monospace,
                    color = cn0Color(b.meanCn0),
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

/** Average signal per direction since the last reset: where the sky is open from this seat. */
@Composable
fun SkyMapSection(gps: GpsState, modifier: Modifier) {
    val skyMap by SignalStats.skyMap.collectAsStateWithLifecycle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(stringResource(R.string.skymap_title)) {
            Text(stringResource(R.string.skymap_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            Spacer(Modifier.height(8.dp))
            SignalSkyPlot(skyMap, gps.satellites, Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                listOf(15.0 to "15", 25.0 to "25", 35.0 to "35", 45.0 to "45 dB-Hz").forEach { (v, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(12.dp).height(12.dp).background(cn0Color(v), RoundedCornerShape(3.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            val samples = skyMap.sumOf { it.samples }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(pluralStringResource(R.plurals.skymap_samples, samples, samples), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { SignalStats.reset() }) { Text(stringResource(R.string.reset)) }
            }
        }
    }
}

@Composable
private fun SignalSkyPlot(cells: List<SignalSkyMap.Cell>, sats: List<Satellite>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val gridColor = Palette.muted.copy(alpha = 0.5f)
    val labelStyle = TextStyle(color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val dotColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.padding(12.dp)) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val sectors = 12
        val rings = 3
        for (cell in cells) {
            val outer = r * (90f - cell.ring * 30f) / 90f
            val inner = r * (90f - (cell.ring + 1) * 30f) / 90f
            // Canvas angle 0 is east; azimuth 0 is north, clockwise.
            val start = cell.sector * (360f / sectors) - 90f
            val sweep = 360f / sectors
            val path = Path().apply {
                arcTo(Rect(c, outer), start, sweep, forceMoveTo = true)
                if (inner > 0f) arcTo(Rect(c, inner), start + sweep, -sweep, forceMoveTo = false) else lineTo(c.x, c.y)
                close()
            }
            drawPath(path, cn0Color(cell.meanCn0).copy(alpha = 0.85f))
        }
        for (i in 1..rings) drawCircle(gridColor, r * i / rings, c, style = Stroke(1.dp.toPx()))
        for (s in 0 until sectors) {
            val a = Math.toRadians((s * 360.0 / sectors) - 90)
            drawLine(gridColor, c, Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()), 0.5.dp.toPx())
        }
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, az) ->
            val layout = measurer.measure(label, labelStyle)
            val a = Math.toRadians(az - 90.0)
            val p = Offset(c.x + r * 1.0f * kotlin.math.cos(a).toFloat(), c.y + r * 1.0f * kotlin.math.sin(a).toFloat())
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
        // Where the satellites are right now.
        sats.filter { it.elevationDeg > 0f }.forEach { s ->
            val a = Math.toRadians(s.azimuthDeg - 90.0)
            val rr = r * (90f - s.elevationDeg.coerceIn(0f, 90f)) / 90f
            drawCircle(dotColor, 3.dp.toPx(), Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat()))
        }
    }
}

private fun Rect(center: Offset, radius: Float) = Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))

/** Satellites, accuracy and signal over the trip, the trip's quality summary, and the first-fix history. */
@Composable
fun HistorySection(gps: GpsState, modifier: Modifier) {
    val ttff by TtffStore.records.collectAsStateWithLifecycle()
    val quality by SignalStats.quality.collectAsStateWithLifecycle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val q = remember(quality) { summarizeQuality(quality) }
        SectionCard(stringResource(R.string.quality_title)) {
            if (q == null) {
                Text(stringResource(R.string.quality_empty), color = Palette.muted)
            } else {
                KeyValue(stringResource(R.string.quality_3d), String.format(Locale.US, "%.0f %%", q.fix3dPercent))
                KeyValue(stringResource(R.string.quality_gap), Format.duration(q.longestGapMs))
                KeyValue(stringResource(R.string.quality_sats), String.format(Locale.US, "%.1f", q.meanUsed))
                q.meanCn0?.let { KeyValue(stringResource(R.string.quality_cn0), String.format(Locale.US, "%.1f dB-Hz", it), cn0Color(it)) }
                q.meanAccuracyM?.let { KeyValue(stringResource(R.string.quality_accuracy), String.format(Locale.US, "±%.1f m", it)) }
            }
        }
        TimeChart(stringResource(R.string.chart_sats), quality, { it.utcMs }, { it.used.toDouble() }, "sat", fromZero = true, showRanges = true, modifier = Modifier.fillMaxWidth())
        TimeChart(stringResource(R.string.chart_cn0), quality, { it.utcMs }, { it.meanCn0 }, "dB-Hz", fromZero = true, showRanges = true, modifier = Modifier.fillMaxWidth())
        TimeChart(stringResource(R.string.chart_accuracy), quality, { it.utcMs }, { it.accuracyM }, "m", fromZero = true, showRanges = true, modifier = Modifier.fillMaxWidth())
        SectionCard(stringResource(R.string.ttff_title)) {
            if (ttff.isEmpty()) Text(stringResource(R.string.ttff_empty), color = Palette.muted)
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            ttff.take(10).forEach { KeyValue(format.format(Date(it.utcMs)), String.format(Locale.US, "%.1f s", it.ttffMs / 1000.0)) }
            if (ttff.size >= 3) {
                val avg = ttff.map { it.ttffMs }.average() / 1000.0
                Text(stringResource(R.string.ttff_average, String.format(Locale.US, "%.1f s", avg)), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Raw-measurement details: receiver gain per band (interference), multipath, and time. */
@Composable
fun ReceiverSection(gps: GpsState, modifier: Modifier) {
    val rx by SignalStats.receiver.collectAsStateWithLifecycle()
    // Raw measurements are registered only while this view is open (battery).
    DisposableEffect(Unit) {
        GpsTracker.setMeasurementsWanted(true)
        onDispose { GpsTracker.setMeasurementsWanted(false) }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(stringResource(R.string.interference_title)) {
            when (rx.measurements) {
                MeasurementsStatus.NOT_SUPPORTED -> Text(stringResource(R.string.measurements_unsupported), color = Palette.muted)
                MeasurementsStatus.LOCATION_DISABLED -> Text(stringResource(R.string.gps_disabled), color = Palette.bad)
                else -> {
                    if (rx.agc.isEmpty()) {
                        Text(stringResource(R.string.agc_none), color = Palette.muted)
                    } else {
                        val warn = rx.agc.any { it.suspicious }
                        Text(
                            stringResource(if (warn) R.string.agc_warning else R.string.agc_ok),
                            color = if (warn) Palette.bad else Palette.good,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Row {
                            Text(stringResource(R.string.agc_band), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.agc_now), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(64.dp))
                            Text(stringResource(R.string.agc_usual), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(64.dp))
                        }
                        rx.agc.forEach { b ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(b.name, modifier = Modifier.weight(1f))
                                Text(String.format(Locale.US, "%.1f", b.currentDb), fontFamily = FontFamily.Monospace, color = if (b.suspicious) Palette.bad else Color.Unspecified, modifier = Modifier.width(64.dp))
                                Text(String.format(Locale.US, "%.1f", b.baselineDb), fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
                            }
                        }
                        Text(stringResource(R.string.agc_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (rx.measured > 0) {
                        Spacer(Modifier.height(8.dp))
                        KeyValue(
                            stringResource(R.string.multipath),
                            "${rx.multipath} / ${rx.measured}",
                            if (rx.multipath > rx.measured / 4) Palette.warn else Color.Unspecified,
                        )
                        Text(stringResource(R.string.multipath_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                }
            }
        }
        SectionCard(stringResource(R.string.time_title)) {
            val offset = gps.clockOffsetMs
            KeyValue(
                stringResource(R.string.time_offset),
                offset?.let {
                    val dir = if (abs(it) < 1) "" else if (it > 0) " " + stringResource(R.string.time_ahead) else " " + stringResource(R.string.time_behind)
                    String.format(Locale.US, "%+.0f ms", it) + dir
                } ?: "--",
                if (offset != null && abs(offset) > 1000) Palette.warn else Color.Unspecified,
            )
            KeyValue(stringResource(R.string.time_uncertainty), rx.timeUncertaintyNs?.let { formatNanos(it) } ?: "--")
            KeyValue(stringResource(R.string.time_leap), rx.leapSeconds?.let { "$it s" } ?: "--")
            gps.fix?.let { KeyValue(stringResource(R.string.time_gps), TrackFormat.isoUtc(it.utcMs)) }
            Text(stringResource(R.string.time_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatNanos(ns: Double): String = when {
    ns < 1_000 -> String.format(Locale.US, "%.0f ns", ns)
    ns < 1_000_000 -> String.format(Locale.US, "%.1f µs", ns / 1_000)
    else -> String.format(Locale.US, "%.1f ms", ns / 1_000_000)
}
