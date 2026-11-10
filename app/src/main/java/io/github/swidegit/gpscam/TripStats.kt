package io.github.swidegit.gpscam

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS position, decoupled from android.location.Location so the statistics are unit-testable. */
data class Fix(
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    /** Doppler speed from the receiver, null when the chip did not report one. */
    val speedMs: Double?,
    val accuracyM: Double?,
    /** 1-sigma uncertainty of [speedMs] reported by the chip, when known. */
    val speedAccuracyMs: Double? = null,
)

data class TripSnapshot(
    val distanceM: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val movingTimeMs: Long = 0,
    val elapsedMs: Long = 0,
) {
    val averageMovingSpeedMs: Double get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
}

fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_008.8
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * Trip statistics. Jitter while standing still must not add distance, and single bad fixes
 * (tunnels, station roofs) must not set the maximum speed: the maximum needs a good position,
 * a confident speed and an acceleration that a vehicle can actually reach.
 */
class TripStats {
    private var first: Fix? = null
    private var last: Fix? = null
    private var snapshot = TripSnapshot()
    private var lastSpeed: Double? = null

    val current: TripSnapshot get() = snapshot

    fun reset() {
        first = null
        last = null
        lastSpeed = null
        snapshot = TripSnapshot()
    }

    private fun plausible(fix: Fix, speed: Double, prev: Fix?): Boolean {
        if ((fix.speedAccuracyMs ?: 0.0) > MAX_SPEED_ACCURACY_MS) return false
        val prevSpeed = lastSpeed ?: return true
        val dt = (fix.elapsedMs - (prev ?: return true).elapsedMs) / 1000.0
        // After a gap (tunnel) the previous speed says nothing about this one.
        if (dt <= 0.0 || dt > MAX_ACCEL_WINDOW_S) return true
        return (speed - prevSpeed) / dt <= MAX_ACCELERATION_MS2
    }

    /** Speed of [fix]: the receiver's Doppler speed, or derived from the previous fix when missing. */
    fun speedOf(fix: Fix): Double? = fix.speedMs ?: last?.let { prev ->
        val dt = (fix.elapsedMs - prev.elapsedMs) / 1000.0
        if (dt in 0.2..MAX_GAP_S) haversineMetres(prev.lat, prev.lon, fix.lat, fix.lon) / dt else null
    }

    fun add(fix: Fix): TripSnapshot {
        val speed = speedOf(fix)
        val prev = last
        val start = first ?: fix.also { first = it }
        val accurate = (fix.accuracyM ?: Double.MAX_VALUE) <= MAX_ACCURACY_M
        var s = snapshot.copy(elapsedMs = fix.elapsedMs - start.elapsedMs)
        if (prev != null && accurate && (prev.accuracyM ?: Double.MAX_VALUE) <= MAX_ACCURACY_M) {
            val dtMs = fix.elapsedMs - prev.elapsedMs
            val moving = (speed ?: 0.0) >= MOVING_MS
            if (moving && dtMs > 0) {
                s = s.copy(distanceM = s.distanceM + haversineMetres(prev.lat, prev.lon, fix.lat, fix.lon))
                // After a long gap (tunnel) the distance is still real, but the time is not known to be moving time.
                if (dtMs <= MAX_GAP_S * 1000) s = s.copy(movingTimeMs = s.movingTimeMs + dtMs)
            }
        }
        if (speed != null && speed > s.maxSpeedMs && accurate && plausible(fix, speed, prev)) s = s.copy(maxSpeedMs = speed)
        last = fix
        lastSpeed = speed
        snapshot = s
        return s
    }

    companion object {
        const val MAX_ACCURACY_M = 30.0
        const val MOVING_MS = 0.7
        const val MAX_GAP_S = 10.0
        /** Speeds less certain than this (1 sigma) do not count for the maximum. */
        const val MAX_SPEED_ACCURACY_MS = 3.0
        /** About 1.1 g: beyond the fastest road cars, far beyond any train. */
        const val MAX_ACCELERATION_MS2 = 11.0
        const val MAX_ACCEL_WINDOW_S = 5.0
    }
}
