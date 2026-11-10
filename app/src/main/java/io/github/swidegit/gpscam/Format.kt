package io.github.swidegit.gpscam

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Speed unit; distances follow it (km/h and m/s use km, mph uses miles, knots use nautical miles). */
enum class SpeedUnit(val label: String, private val perMetrePerSecond: Double, val distanceLabel: String, private val perMetre: Double) {
    KMH("km/h", 3.6, "km", 0.001),
    MPH("mph", 3600.0 / 1609.344, "mi", 1.0 / 1609.344),
    MS("m/s", 1.0, "km", 0.001),
    KNOTS("kn", 3600.0 / 1852.0, "NM", 1.0 / 1852.0);

    fun fromMetresPerSecond(ms: Double): Double = ms * perMetrePerSecond
    fun distanceFromMetres(m: Double): Double = m * perMetre
    fun next(): SpeedUnit = entries[(ordinal + 1) % entries.size]
}

object Format {
    /** Whole numbers, except m/s below 100, which keeps one decimal (walking speeds are 1-2 m/s). */
    fun speed(ms: Double, unit: SpeedUnit): String {
        val v = unit.fromMetresPerSecond(ms)
        return if (unit == SpeedUnit.MS && v < 100) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()
    }

    fun distance(m: Double, unit: SpeedUnit): String {
        val v = unit.distanceFromMetres(m)
        return String.format(Locale.US, if (v < 100) "%.2f %s" else "%.1f %s", v, unit.distanceLabel)
    }

    /** 0:05, 12:34, 1:02:03. */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000.0).roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun coordinates(lat: Double, lon: Double): String = String.format(
        Locale.US, "%.5f° %s, %.5f° %s",
        abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
    )

    private val compass = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compassPoint(bearingDeg: Float): String {
        val normalized = ((bearingDeg % 360f) + 360f) % 360f
        return compass[((normalized + 22.5f) / 45f).toInt() % 8]
    }
}
