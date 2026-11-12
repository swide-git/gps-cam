package io.github.swidegit.gpscam

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

data class Exported(val uri: Uri, val format: ExportFormat)

@Composable
fun TripsScreen(gps: GpsState, settings: Settings, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trips by TripStore.trips.collectAsStateWithLifecycle()
    var exported by remember { mutableStateOf<Exported?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TripSummary?>(null) }
    var openTrip by remember { mutableStateOf<TripSummary?>(null) }
    val unit = settings.unit

    fun export(tripId: Long, points: () -> List<TrackPoint>, format: ExportFormat) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { TripStore.export(context, tripId, points(), format) }
            failed = uri == null
            exported = uri?.let { Exported(it, format) }
        }
    }

    // A trip deleted from its detail page closes the page.
    openTrip?.takeIf { t -> trips.any { it.id == t.id } }?.let { t ->
        BackHandler { openTrip = null }
        TripDetail(
            trip = t,
            unit = unit,
            modifier = modifier,
            exported = exported,
            failed = failed,
            onBack = { openTrip = null },
            onExport = { format -> export(t.id, { TripStore.points(t.id) }, format) },
            onShare = { e -> share(context, e.uri, e.format) },
            onDelete = { confirmDelete = t },
        )
        confirmDelete?.let { DeleteDialog(it, onDone = { confirmDelete = null }) }
        return
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val current = GpsTracker.currentTripId()
            Card {
                Text(stringResource(R.string.trip_current), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!gps.tripActive) {
                    Text(stringResource(R.string.trip_idle), color = Palette.muted)
                    FilledTonalButton(onClick = { GpsTracker.startTrip() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.start_trip))
                    }
                } else if (current == null || gps.track.size < 2) {
                    Text(stringResource(R.string.trip_current_empty), color = Palette.muted)
                    TextButton(onClick = { GpsTracker.endTrip() }) { Text(stringResource(R.string.end_trip)) }
                } else {
                    TripFacts(current, gps.track.last().utcMs, gps.trip.distanceM, gps.trip.maxSpeedMs, gps.trip.averageMovingSpeedMs, gps.track.size, unit)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { GpsTracker.endTrip() }) { Text(stringResource(R.string.end_trip)) }
                        OutlinedButton(onClick = { export(current, { gps.track }, ExportFormat.GPX) }) { Text("GPX") }
                        OutlinedButton(onClick = { export(current, { gps.track }, ExportFormat.CSV) }) { Text("CSV") }
                    }
                }
            }
        }
        exported?.let { e ->
            item {
                Card {
                    Text(stringResource(R.string.export_saved, e.format.extension.uppercase()))
                    TextButton(onClick = { share(context, e.uri, e.format) }) { Text(stringResource(R.string.share)) }
                }
            }
        }
        if (failed) item { Text(stringResource(R.string.export_failed), color = Palette.bad) }
        item {
            Text(stringResource(R.string.trip_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            if (trips.isEmpty()) Text(stringResource(R.string.trip_history_empty), color = Palette.muted)
        }
        items(trips, key = { it.id }) { t ->
            Card(onClick = { exported = null; failed = false; openTrip = t }) {
                TripFacts(t.id, t.endUtcMs, t.distanceM, t.maxSpeedMs, t.averageMovingSpeedMs, t.points, unit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { export(t.id, { TripStore.points(t.id) }, ExportFormat.GPX) }) { Text("GPX") }
                    OutlinedButton(onClick = { export(t.id, { TripStore.points(t.id) }, ExportFormat.CSV) }) { Text("CSV") }
                    TextButton(onClick = { confirmDelete = t }) { Text(stringResource(R.string.delete), color = Palette.bad) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    confirmDelete?.let { DeleteDialog(it, onDone = { confirmDelete = null }) }
}

@Composable
private fun DeleteDialog(t: TripSummary, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.delete_trip_title)) },
        text = { Text(stringResource(R.string.delete_trip_body)) },
        confirmButton = {
            TextButton(onClick = { TripStore.delete(t.id); onDone() }) { Text(stringResource(R.string.delete), color = Palette.bad) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One saved trip: statistics, speed and altitude over time, export and delete. */
@Composable
private fun TripDetail(
    trip: TripSummary,
    unit: SpeedUnit,
    modifier: Modifier,
    exported: Exported?,
    failed: Boolean,
    onBack: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onShare: (Exported) -> Unit,
    onDelete: () -> Unit,
) {
    val points by produceState<List<TrackPoint>?>(null, trip.id) {
        value = withContext(Dispatchers.IO) { TripStore.points(trip.id) }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close)) }
            Column {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.id)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.trip_points, trip.points), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
        }
        DetailTiles(
            stringResource(R.string.stat_distance) to Format.distance(trip.distanceM, unit),
            stringResource(R.string.trip_duration) to Format.duration(trip.durationMs),
        )
        DetailTiles(
            stringResource(R.string.stat_max) to "${Format.speed(trip.maxSpeedMs, unit)} ${unit.label}",
            stringResource(R.string.stat_avg) to "${Format.speed(trip.averageMovingSpeedMs, unit)} ${unit.label}",
        )
        val track = points
        if (track == null) {
            Text(stringResource(R.string.trip_loading), color = Palette.muted)
        } else {
            val altitudes = track.mapNotNull { it.altitudeM }
            DetailTiles(
                stringResource(R.string.stat_moving_time) to Format.duration(trip.movingTimeMs),
                stringResource(R.string.trip_altitude_range) to
                    if (altitudes.isEmpty()) "--" else
                        "${unit.altitudeFromMetres(altitudes.min()).roundToInt()}–${Format.altitude(altitudes.max(), unit)}",
            )
            val accuracies = track.mapNotNull { it.accuracyM }
            DetailTiles(
                stringResource(R.string.trip_longest_gap) to Format.duration(longestTrackGapMs(track)),
                stringResource(R.string.quality_accuracy) to
                    if (accuracies.isEmpty()) "--" else String.format(java.util.Locale.US, "±%.1f m", accuracies.average()),
            )
            SpeedChart(track, unit, Modifier.fillMaxWidth(), showRanges = false)
            if (altitudes.size >= 2) {
                TrackChart(stringResource(R.string.stat_altitude), track, unit.altitudeLabel, { p -> p.altitudeM?.let { unit.altitudeFromMetres(it) } }, fromZero = false, showRanges = false, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(ExportFormat.GPX) }) { Text("GPX") }
            OutlinedButton(onClick = { onExport(ExportFormat.CSV) }) { Text("CSV") }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = Palette.bad) }
        }
        exported?.let { e ->
            Card {
                Text(stringResource(R.string.export_saved, e.format.extension.uppercase()))
                TextButton(onClick = { onShare(e) }) { Text(stringResource(R.string.share)) }
            }
        }
        if (failed) Text(stringResource(R.string.export_failed), color = Palette.bad)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DetailTiles(left: Pair<String, String>, right: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(left, right).forEach { (label, value) ->
            Column(
                Modifier.weight(1f).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
    ) { content() }
}

@Composable
private fun TripFacts(startUtc: Long, endUtc: Long, distanceM: Double, maxMs: Double, avgMs: Double, points: Int, unit: SpeedUnit) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startUtc))
    Text(date, fontWeight = FontWeight.Medium)
    Text(
        "${Format.distance(distanceM, unit)} · ${Format.duration(endUtc - startUtc)} · " +
            "max ${Format.speed(maxMs, unit)} · avg ${Format.speed(avgMs, unit)} ${unit.label}",
        color = Palette.muted,
    )
    Text(stringResource(R.string.trip_points, points), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
}

private fun share(context: Context, uri: Uri, format: ExportFormat) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
