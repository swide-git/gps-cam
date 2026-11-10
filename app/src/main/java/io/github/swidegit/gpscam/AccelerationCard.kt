package io.github.swidegit.gpscam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

fun formatRunTime(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

/** Standing-start and rolling acceleration times, last and best per run. */
@Composable
fun AccelerationCard(gps: GpsState, unit: SpeedUnit, modifier: Modifier = Modifier) {
    val results by PerformanceStore.results.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.accel_title), style = MaterialTheme.typography.titleMedium, color = Palette.accent, modifier = Modifier.weight(1f))
            gps.updateHz?.let { Text(String.format(Locale.US, "GPS %.0f Hz", it), style = MaterialTheme.typography.labelMedium, color = Palette.muted) }
        }
        val status = when (val m = gps.meter) {
            MeterStatus.NeedStop -> stringResource(R.string.accel_need_stop)
            MeterStatus.Ready -> stringResource(R.string.accel_ready)
            is MeterStatus.Running -> stringResource(R.string.accel_running, formatRunTime(m.elapsedMs))
        }
        Text(status, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Row {
            Text("", modifier = Modifier.weight(1f))
            Text(stringResource(R.string.accel_last), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(96.dp))
            Text(stringResource(R.string.accel_best), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(96.dp))
        }
        for (spec in Runs.forUnit(unit)) {
            val own = results.filter { it.specId == spec.id }
            val last = own.firstOrNull()
            val best = own.minByOrNull { it.timeMs }
            fun cell(r: RunResult?) = r?.let { res ->
                formatRunTime(res.timeMs) + (res.trapSpeedMs?.let { "\n@ ${Format.speed(it, unit)} ${unit.label}" } ?: "")
            } ?: "--"
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(spec.label, modifier = Modifier.weight(1f))
                Text(cell(last), fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
                Text(cell(best), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
            }
        }
        val hz = gps.updateHz
        if (hz != null && hz < 2.0) {
            Text(stringResource(R.string.accel_low_rate), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
        }
        if (results.isNotEmpty()) {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.accel_clear))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.accel_clear_title)) },
            confirmButton = {
                TextButton(onClick = { PerformanceStore.clear(); confirmClear = false }) { Text(stringResource(R.string.accel_clear), color = Palette.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
