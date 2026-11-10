package io.github.swidegit.gpscam

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class GpsFix(
    val lat: Double,
    val lon: Double,
    /** Doppler speed from the receiver, or derived from consecutive positions. */
    val speedMs: Double?,
    val speedAccuracyMs: Double?,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val accuracyM: Double?,
    val verticalAccuracyM: Double?,
    /** SystemClock.elapsedRealtime of the fix, for the "last fix N s ago" display. */
    val elapsedMs: Long,
    /** GPS (UTC) time of the fix. */
    val utcMs: Long,
)

data class GpsState(
    val running: Boolean = false,
    val providerEnabled: Boolean = true,
    val fix: GpsFix? = null,
    val satellites: List<Satellite> = emptyList(),
    val fixType: FixType = FixType.NONE,
    val dop: Dop? = null,
    val ttffMs: Int? = null,
    val trip: TripSnapshot = TripSnapshot(),
    val error: String? = null,
) {
    val usedInFix: Int get() = satellites.count { it.usedInFix }

    /** A fix older than this is shown as stale: tunnels, or the phone away from the window. */
    fun fixAgeMs(now: Long = SystemClock.elapsedRealtime()): Long? = fix?.let { now - it.elapsedMs }
    fun isFresh(now: Long = SystemClock.elapsedRealtime()): Boolean = (fixAgeMs(now) ?: Long.MAX_VALUE) <= STALE_MS

    companion object {
        const val STALE_MS = 5_000L
    }
}

/**
 * Raw GNSS receiver, process-wide. Deliberately GPS_PROVIDER and not the fused provider: the fused
 * provider blends Wi-Fi and cell positions and smooths speed, which is what breaks map apps on fast
 * trains (station Wi-Fi, cell towers far behind). The chip's Doppler speed is accurate at 300 km/h.
 */
object GpsTracker {
    private const val TAG = "GpsTracker"
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private val trip = TripStats()
    private var thread: HandlerThread? = null
    private var manager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = _state.update { it.copy(providerEnabled = true) }
        override fun onProviderDisabled(provider: String) = _state.update { it.copy(providerEnabled = false) }

        // Abstract on Android 10 (minSdk 29); a default method only from Android 11.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sats = (0 until status.satelliteCount).map { i ->
                Satellite(
                    constellation = Constellation.fromType(status.getConstellationType(i)),
                    svid = status.getSvid(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    elevationDeg = status.getElevationDegrees(i),
                    azimuthDeg = status.getAzimuthDegrees(i),
                    usedInFix = status.usedInFix(i),
                    hasEphemeris = status.hasEphemerisData(i),
                    hasAlmanac = status.hasAlmanacData(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                )
            }
            _state.update { it.copy(satellites = sats) }
        }

        override fun onFirstFix(ttffMillis: Int) = _state.update { it.copy(ttffMs = ttffMillis) }
        override fun onStopped() = _state.update { it.copy(satellites = emptyList()) }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        // One GSA per constellation per epoch; they share the fix mode and DOP.
        Nmea.parseGsa(message)?.let { gsa -> _state.update { it.copy(fixType = gsa.fixType, dop = gsa.dop) } }
    }

    @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION first.
    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
        val t = HandlerThread("gps").apply { start() }
        val handler = Handler(t.looper)
        try {
            // minTime 0: whatever rate the chip delivers (1 Hz on most phones, up to 10 Hz on some).
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, t.looper)
            lm.registerGnssStatusCallback(gnssCallback, handler)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.addNmeaListener({ r -> handler.post(r) }, nmeaListener)
            } else {
                @Suppress("DEPRECATION")
                lm.addNmeaListener(nmeaListener, handler)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot start GPS", e)
            lm.removeUpdates(locationListener)
            t.quitSafely()
            _state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            return
        }
        thread = t
        manager = lm
        _state.update {
            it.copy(running = true, error = null, providerEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
        }
    }

    @Synchronized
    fun stop() {
        val lm = manager ?: return
        lm.removeUpdates(locationListener)
        lm.unregisterGnssStatusCallback(gnssCallback)
        lm.removeNmeaListener(nmeaListener)
        thread?.quitSafely()
        thread = null
        manager = null
        _state.update { it.copy(running = false, satellites = emptyList(), fixType = FixType.NONE) }
    }

    fun resetTrip() {
        synchronized(trip) { trip.reset() }
        _state.update { it.copy(trip = TripSnapshot()) }
    }

    private fun onLocation(loc: Location) {
        val elapsedMs = loc.elapsedRealtimeNanos / 1_000_000
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val fix = Fix(elapsedMs, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else null, accuracy)
        val (speed, snapshot) = synchronized(trip) { trip.speedOf(fix) to trip.add(fix) }
        val altitude = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        val gpsFix = GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            speedMs = speed,
            speedAccuracyMs = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            altitudeM = altitude,
            accuracyM = accuracy,
            verticalAccuracyM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            elapsedMs = elapsedMs,
            utcMs = loc.time,
        )
        _state.update { it.copy(fix = gpsFix, trip = snapshot, providerEnabled = true) }
    }
}
