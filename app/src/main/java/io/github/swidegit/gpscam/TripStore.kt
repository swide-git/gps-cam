package io.github.swidegit.gpscam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val extension: String, val mime: String) {
    GPX("gpx", "application/gpx+xml"),
    CSV("csv", "text/csv"),
}

/**
 * Trip history in the app's private storage: one points file per trip (<id>.csv, appended while
 * driving) and index.json with the summaries. Exports go to Documents/GPSCam via MediaStore.
 */
object TripStore {
    private const val TAG = "TripStore"
    private var dir: File? = null
    private var writer: FileWriter? = null
    private var writerId: Long? = null
    private var unflushed = 0

    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, "trips").apply { mkdirs() }
        dir = d
        val list = readIndex(d).toMutableList()
        // Points files without a summary: trips the process did not end (killed, crashed).
        val known = list.map { it.id }.toSet()
        d.listFiles { f -> f.name.endsWith(".csv") }?.forEach { f ->
            val id = f.nameWithoutExtension.toLongOrNull() ?: return@forEach
            if (id !in known) {
                val points = readPoints(f)
                if (points.size >= 2) list += TrackFormat.summarize(id, points) else f.delete()
            }
        }
        _trips.value = list.sortedByDescending { it.id }
        writeIndex(d, _trips.value)
    }

    @Synchronized
    fun append(tripId: Long, point: TrackPoint) {
        val d = dir ?: return
        try {
            if (writerId != tripId) {
                writer?.close()
                writer = FileWriter(File(d, "$tripId.csv"), true)
                writerId = tripId
            }
            writer!!.write(TrackFormat.storageLine(point) + "\n")
            // At most ~10 s of track lost if the process dies.
            if (++unflushed >= 10) {
                writer!!.flush()
                unflushed = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot append point", e)
        }
    }

    /** Closes the current trip and adds it to the history; trips with fewer than two points are dropped. */
    @Synchronized
    fun endTrip(tripId: Long, points: List<TrackPoint>, stats: TripSnapshot) {
        val d = dir ?: return
        if (writerId == tripId) {
            runCatching { writer?.close() }
            writer = null
            writerId = null
            unflushed = 0
        }
        if (points.size < 2) {
            File(d, "$tripId.csv").delete()
            return
        }
        val summary = TripSummary(tripId, points.last().utcMs, stats.distanceM, stats.maxSpeedMs, stats.movingTimeMs, points.size)
        _trips.value = (listOf(summary) + _trips.value.filter { it.id != tripId }).sortedByDescending { it.id }
        writeIndex(d, _trips.value)
    }

    @Synchronized
    fun delete(tripId: Long) {
        val d = dir ?: return
        File(d, "$tripId.csv").delete()
        _trips.value = _trips.value.filter { it.id != tripId }
        writeIndex(d, _trips.value)
    }

    @Synchronized
    fun points(tripId: Long): List<TrackPoint> {
        if (writerId == tripId) writer?.flush()
        return dir?.let { readPoints(File(it, "$tripId.csv")) } ?: emptyList()
    }

    /** Writes the trip to Documents/GPSCam; returns the new document, or null on failure. */
    fun export(context: Context, tripId: Long, points: List<TrackPoint>, format: ExportFormat): Uri? {
        val name = "GPSCam_trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(tripId))
        val text = when (format) {
            ExportFormat.GPX -> TrackFormat.gpx(name, points)
            ExportFormat.CSV -> TrackFormat.csv(points)
        }
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.${format.extension}")
                put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/GPSCam")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            uri
        }.onFailure { Log.e(TAG, "export failed", it) }.getOrNull()
    }

    private fun readPoints(f: File): List<TrackPoint> =
        if (f.exists()) f.readLines().mapNotNull { TrackFormat.parseStorageLine(it) } else emptyList()

    private fun readIndex(d: File): List<TripSummary> = runCatching {
        val arr = JSONArray(File(d, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TripSummary(
                o.getLong("id"), o.getLong("end"), o.getDouble("distance"), o.getDouble("max"),
                o.getLong("moving"), o.getInt("points"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(d: File, list: List<TripSummary>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("end", it.endUtcMs).put("distance", it.distanceM)
                    .put("max", it.maxSpeedMs).put("moving", it.movingTimeMs).put("points", it.points)
            )
        }
        runCatching {
            val tmp = File(d, "index.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(d, "index.json"))
        }.onFailure { Log.e(TAG, "cannot write index", it) }
    }
}
