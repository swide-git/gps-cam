package io.github.swidegit.gpscam

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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

private data class Exported(val uri: Uri, val format: ExportFormat)

@Composable
fun TripsScreen(gps: GpsState, settings: Settings, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trips by TripStore.trips.collectAsStateWithLifecycle()
    var exported by remember { mutableStateOf<Exported?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TripSummary?>(null) }
    val unit = settings.unit

    fun export(tripId: Long, points: () -> List<TrackPoint>, format: ExportFormat) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { TripStore.export(context, tripId, points(), format) }
            failed = uri == null
            exported = uri?.let { Exported(it, format) }
        }
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val current = GpsTracker.currentTripId()
            Card {
                Text(stringResource(R.string.trip_current), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (current == null || gps.track.size < 2) {
                    Text(stringResource(R.string.trip_current_empty), color = Palette.muted)
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
            Card {
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

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_trip_title)) },
            text = { Text(stringResource(R.string.delete_trip_body)) },
            confirmButton = {
                TextButton(onClick = { TripStore.delete(t.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = Palette.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(14.dp),
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
