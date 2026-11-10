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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        !fresh -> stringResource(R.string.fix_none) to Palette.badgeBad
        gps.fixType == FixType.FIX_2D -> stringResource(R.string.fix_2d) to Palette.badgeWarn
        else -> stringResource(R.string.fix_3d) to Palette.badgeGood
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
    val trip = gps.trip
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!gps.providerEnabled) GpsDisabledNotice(Modifier.fillMaxWidth())
        gps.error?.let { Text(it, color = Palette.bad) }

        // Hero card, like the status card in Second Display.
        val onHero = MaterialTheme.colorScheme.onPrimaryContainer
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FixBadge(gps, now)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val size = (maxWidth.value * 0.36f).coerceAtMost(200f)
                Text(
                    fix?.speedMs?.let { Format.speed(it, unit) } ?: "--",
                    fontSize = size.sp,
                    lineHeight = size.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (fresh) onHero else onHero.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                unit.label,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable { AppSettings.update { it.copy(unit = unit.next()) } }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            fix?.speedAccuracyMs?.let {
                Text("± ${Format.speed(it, unit)} ${unit.label}", color = onHero.copy(alpha = 0.75f))
            }
            if (!fresh) {
                Spacer(Modifier.height(8.dp))
                val age = gps.fixAgeMs(now)
                if (age != null) Text(stringResource(R.string.fix_age, age / 1000), color = onHero, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.no_fix_hint), textAlign = TextAlign.Center, color = onHero.copy(alpha = 0.8f), modifier = Modifier.widthIn(max = 480.dp))
            }
        }

        TileRow(
            Tile(stringResource(R.string.stat_max), "${Format.speed(trip.maxSpeedMs, unit)} ${unit.label}"),
            Tile(stringResource(R.string.stat_avg), "${Format.speed(trip.averageMovingSpeedMs, unit)} ${unit.label}"),
        )
        TileRow(
            Tile(stringResource(R.string.stat_distance), Format.distance(trip.distanceM, unit)),
            Tile(stringResource(R.string.stat_moving_time), Format.duration(trip.movingTimeMs)),
        )
        SpeedChart(gps.track, unit, Modifier.fillMaxWidth())
        TileRow(
            Tile(stringResource(R.string.stat_altitude), fix?.altitudeM?.let { "${it.toInt()} m" } ?: "--"),
            Tile(stringResource(R.string.stat_heading), fix?.bearingDeg?.let { String.format(Locale.US, "%.0f° %s", it, Format.compassPoint(it)) } ?: "--"),
        )
        TileRow(
            Tile(stringResource(R.string.stat_trip_time), Format.duration(trip.elapsedMs)),
            Tile(stringResource(R.string.stat_ttff), gps.ttffMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "--"),
        )
        if (fix != null) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
            ) {
                Text(stringResource(R.string.stat_position), style = MaterialTheme.typography.labelLarge, color = Palette.muted)
                Text(Format.coordinates(fix.lat, fix.lon), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalButton(onClick = { GpsTracker.endTrip() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.end_trip))
            }
            Text(stringResource(R.string.end_trip_hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

private data class Tile(val label: String, val value: String)

/** Two tonal tiles side by side, as in the Second Display dashboard. */
@Composable
private fun TileRow(left: Tile, right: Tile) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(left, right).forEach { t ->
            Column(
                Modifier.weight(1f)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(t.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                Text(t.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
