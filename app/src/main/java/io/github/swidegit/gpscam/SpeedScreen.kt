package io.github.swidegit.gpscam

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** Elapsed-realtime clock that ticks twice a second, for fix age and staleness. */
@Composable
fun rememberNow(): Long {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
fun FixBadge(gps: GpsState, now: Long) {
    val fresh = gps.isFresh(now)
    val (label, color) = when {
        !fresh -> stringResource(R.string.fix_none) to Palette.bad
        gps.fixType == FixType.FIX_2D -> stringResource(R.string.fix_2d) to Palette.warn
        else -> stringResource(R.string.fix_3d) to Palette.good
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text("SAT ${gps.usedInFix}/${gps.satellites.size}", fontWeight = FontWeight.Medium)
        gps.fix?.accuracyM?.let { Text("±${it.toInt()} m", color = Palette.muted) }
    }
}

@Composable
fun SpeedScreen(gps: GpsState, settings: Settings, modifier: Modifier) {
    val now = rememberNow()
    val unit = settings.unit
    val fix = gps.fix
    val fresh = gps.isFresh(now)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FixBadge(gps, now)
        if (!gps.providerEnabled) GpsDisabledNotice(Modifier.padding(top = 8.dp))
        gps.error?.let { Text(it, color = Palette.bad) }

        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val size = (maxWidth.value * 0.36f).coerceAtMost(220f)
            Text(
                fix?.speedMs?.let { Format.speed(it, unit) } ?: "--",
                fontSize = size.sp,
                lineHeight = size.sp,
                fontWeight = FontWeight.Bold,
                color = if (fresh) MaterialTheme.colorScheme.onBackground else Palette.muted,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            unit.label,
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.accent,
            modifier = Modifier.clickable { AppSettings.update { it.copy(unit = unit.next()) } }.padding(8.dp),
        )
        fix?.speedAccuracyMs?.let {
            Text("± ${Format.speed(it, unit)} ${unit.label}", color = Palette.muted)
        }
        if (!fresh) {
            Spacer(Modifier.height(8.dp))
            val age = gps.fixAgeMs(now)
            if (age != null) Text(stringResource(R.string.fix_age, age / 1000), color = Palette.warn)
            Text(stringResource(R.string.no_fix_hint), textAlign = TextAlign.Center, color = Palette.muted, modifier = Modifier.widthIn(max = 480.dp))
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val trip = gps.trip
            StatCard(stringResource(R.string.stat_max), "${Format.speed(trip.maxSpeedMs, unit)} ${unit.label}")
            StatCard(stringResource(R.string.stat_avg), "${Format.speed(trip.averageMovingSpeedMs, unit)} ${unit.label}")
            StatCard(stringResource(R.string.stat_distance), Format.distance(trip.distanceM, unit))
            StatCard(stringResource(R.string.stat_moving_time), Format.duration(trip.movingTimeMs))
            StatCard(stringResource(R.string.stat_trip_time), Format.duration(trip.elapsedMs))
            StatCard(stringResource(R.string.stat_altitude), fix?.altitudeM?.let { "${it.toInt()} m" } ?: "--")
            StatCard(
                stringResource(R.string.stat_heading),
                fix?.bearingDeg?.let { String.format(Locale.US, "%.0f° %s", it, Format.compassPoint(it)) } ?: "--",
            )
            StatCard(stringResource(R.string.stat_ttff), gps.ttffMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "--")
        }
        Spacer(Modifier.height(12.dp))
        SpeedChart(gps.track, unit, Modifier.fillMaxWidth().widthIn(max = 640.dp))
        Spacer(Modifier.height(12.dp))
        if (fix != null) {
            Text(stringResource(R.string.stat_position), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            Text(Format.coordinates(fix.lat, fix.lon), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { GpsTracker.endTrip() }) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.end_trip))
        }
        Text(stringResource(R.string.end_trip_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Column(
        Modifier
            .width(156.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
