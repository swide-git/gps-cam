package io.github.swidegit.gpscam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One recorded trip point. [elapsedMs] is SystemClock.elapsedRealtime, only meaningful within one boot. */
data class TrackPoint(
    val utcMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMs: Double?,
    val accuracyM: Double?,
)

data class TripSummary(
    /** UTC start time in ms; also the trip id and the name of its points file. */
    val id: Long,
    val endUtcMs: Long,
    val distanceM: Double,
    val maxSpeedMs: Double,
    val movingTimeMs: Long,
    val points: Int,
) {
    val durationMs: Long get() = endUtcMs - id
    val averageMovingSpeedMs: Double get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
}

/** Track formats: the app's private storage line format, and the GPX / CSV exports. */
object TrackFormat {
    private fun opt(v: Double?) = v?.toString() ?: ""

    fun storageLine(p: TrackPoint): String =
        "${p.utcMs},${p.elapsedMs},${p.lat},${p.lon},${opt(p.altitudeM)},${opt(p.speedMs)},${opt(p.accuracyM)}"

    fun parseStorageLine(line: String): TrackPoint? {
        val f = line.trim().split(',')
        if (f.size != 7) return null
        return TrackPoint(
            utcMs = f[0].toLongOrNull() ?: return null,
            elapsedMs = f[1].toLongOrNull() ?: return null,
            lat = f[2].toDoubleOrNull() ?: return null,
            lon = f[3].toDoubleOrNull() ?: return null,
            altitudeM = f[4].toDoubleOrNull(),
            speedMs = f[5].toDoubleOrNull(),
            accuracyM = f[6].toDoubleOrNull(),
        )
    }

    fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun num(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

    /** GPX 1.1 track; the speed goes into a standard-free <extensions> element most tools ignore safely. */
    fun gpx(name: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="GPS Cam" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("""      <trkpt lat="${num(p.lat, 7)}" lon="${num(p.lon, 7)}">""")
            p.altitudeM?.let { append("<ele>").append(num(it, 1)).append("</ele>") }
            append("<time>").append(isoUtc(p.utcMs)).append("</time>")
            p.speedMs?.let { append("<extensions><speed>").append(num(it, 2)).append("</speed></extensions>") }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun csv(points: List<TrackPoint>): String = buildString {
        append("time_utc,latitude,longitude,altitude_m,speed_m_s,speed_km_h,accuracy_m\n")
        for (p in points) {
            append(isoUtc(p.utcMs)).append(',')
            append(num(p.lat, 7)).append(',').append(num(p.lon, 7)).append(',')
            append(p.altitudeM?.let { num(it, 1) } ?: "").append(',')
            append(p.speedMs?.let { num(it, 2) } ?: "").append(',')
            append(p.speedMs?.let { num(it * 3.6, 1) } ?: "").append(',')
            append(p.accuracyM?.let { num(it, 1) } ?: "").append('\n')
        }
    }

    /** Replays a stored track through [TripStats], for trips whose summary was never written. */
    fun summarize(id: Long, points: List<TrackPoint>): TripSummary {
        val stats = TripStats()
        points.forEach { stats.add(Fix(it.utcMs, it.lat, it.lon, it.speedMs, it.accuracyM)) }
        val s = stats.current
        return TripSummary(id, points.lastOrNull()?.utcMs ?: id, s.distanceM, s.maxSpeedMs, s.movingTimeMs, points.size)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
