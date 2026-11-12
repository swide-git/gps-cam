package io.github.swidegit.gpscam

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
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

/**
 * What the receiver reports right now. Deliberately small and without the growing lists (track,
 * quality samples, sky map), which live in [TripRecorder] and [SignalStats] and are published on
 * their own: every screen collects this, and it changes a couple of times a second.
 */
data class GpsState(
    val running: Boolean = false,
    val providerEnabled: Boolean = true,
    val fix: GpsFix? = null,
    val satellites: List<Satellite> = emptyList(),
    val fixType: FixType = FixType.NONE,
    val dop: Dop? = null,
    val ttffMs: Int? = null,
    /** Acceleration meter state and the latest completed run (shown for a while, also in the video). */
    val meter: MeterStatus = MeterStatus.NeedStop,
    val lastRun: RunResult? = null,
    val lastRunElapsedMs: Long = 0,
    /** Location updates per second, measured: timing precision depends on it. */
    val updateHz: Double? = null,
    /** Phone clock minus GPS time (positive: the phone is ahead). */
    val clockOffsetMs: Double? = null,
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
 * Fixes go on to [TripRecorder], satellite and raw-measurement events to [SignalStats].
 */
object GpsTracker {
    private const val TAG = "GpsTracker"
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    /** Speed derivation for chips without Doppler speed; fed with every fix, trip or not. */
    private val live = TripStats()
    private var meterUnit: SpeedUnit? = null
    private var meter = PerformanceMeter(emptyList())
    private val recentFixes = ArrayDeque<Long>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var manager: LocationManager? = null
    // Raw measurements cost battery on some chips: registered only while a screen wants them.
    private var measurementsWanted = 0
    private var measurementsRegistered = false

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
            SignalStats.onSatellites(sats, _state.value, SystemClock.elapsedRealtime())
            _state.update { it.copy(satellites = sats) }
        }

        override fun onFirstFix(ttffMillis: Int) {
            TtffStore.add(TtffRecord(System.currentTimeMillis(), ttffMillis))
            _state.update { it.copy(ttffMs = ttffMillis) }
        }
        override fun onStopped() = _state.update { it.copy(satellites = emptyList()) }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        // One GSA per constellation per epoch; they share the fix mode and DOP.
        Nmea.parseGsa(message)?.let { gsa -> _state.update { it.copy(fixType = gsa.fixType, dop = gsa.dop) } }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = SignalStats.onMeasurements(event)
        override fun onStatusChanged(status: Int) = SignalStats.onMeasurementsStatus(
            when (status) {
                STATUS_READY -> MeasurementsStatus.OK
                STATUS_NOT_SUPPORTED -> MeasurementsStatus.NOT_SUPPORTED
                STATUS_LOCATION_DISABLED -> MeasurementsStatus.LOCATION_DISABLED
                else -> MeasurementsStatus.UNKNOWN
            }
        )
    }

    @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION first.
    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        TripRecorder.init(context)
        PerformanceStore.init(context)
        TtffStore.init(context)
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
        val t = HandlerThread("gps").apply { start() }
        val h = Handler(t.looper)
        try {
            // minTime 0: whatever rate the chip delivers (1 Hz on most phones, up to 10 Hz on some).
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, t.looper)
            lm.registerGnssStatusCallback(gnssCallback, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.addNmeaListener({ r -> h.post(r) }, nmeaListener)
            } else {
                @Suppress("DEPRECATION")
                lm.addNmeaListener(nmeaListener, h)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot start GPS", e)
            lm.removeUpdates(locationListener)
            t.quitSafely()
            _state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            return
        }
        thread = t
        handler = h
        manager = lm
        updateMeasurementsRegistration()
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
        if (measurementsRegistered) runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
        measurementsRegistered = false
        thread?.quitSafely()
        thread = null
        handler = null
        manager = null
        _state.update { it.copy(running = false, satellites = emptyList(), fixType = FixType.NONE) }
    }

    /** Screens showing raw-measurement data call this with true while visible and false after. */
    @Synchronized
    fun setMeasurementsWanted(wanted: Boolean) {
        measurementsWanted = (measurementsWanted + if (wanted) 1 else -1).coerceAtLeast(0)
        updateMeasurementsRegistration()
    }

    @SuppressLint("MissingPermission")
    private fun updateMeasurementsRegistration() {
        val lm = manager ?: return
        val h = handler ?: return
        val want = measurementsWanted > 0
        if (want == measurementsRegistered) return
        if (want) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.registerGnssMeasurementsCallback({ r -> h.post(r) }, measurementsCallback)
                } else {
                    @Suppress("DEPRECATION")
                    lm.registerGnssMeasurementsCallback(measurementsCallback, h)
                }
                measurementsRegistered = true
            }.onFailure {
                Log.w(TAG, "raw measurements unavailable", it)
                SignalStats.onMeasurementsStatus(MeasurementsStatus.NOT_SUPPORTED)
            }
        } else {
            runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
            measurementsRegistered = false
        }
    }

    private fun onLocation(loc: Location) {
        val elapsedMs = loc.elapsedRealtimeNanos / 1_000_000
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val speedAccuracy = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null
        val fix = Fix(elapsedMs, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else null, accuracy, speedAccuracy)
        val altitude = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        val speed = synchronized(live) {
            live.speedOf(fix).also { live.add(fix) }
        }
        TripRecorder.onFix(fix, TrackPoint(loc.time, elapsedMs, loc.latitude, loc.longitude, altitude, speed, accuracy))

        val gpsFix = GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            speedMs = speed,
            speedAccuracyMs = speedAccuracy,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            altitudeM = altitude,
            accuracyM = accuracy,
            verticalAccuracyM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            elapsedMs = elapsedMs,
            utcMs = loc.time,
        )
        // Acceleration timing uses the receiver's Doppler speed only: derived speeds lag. A unit
        // change swaps the run set, but not in the middle of a run.
        val unit = AppSettings.state.value.unit
        if (unit != meterUnit && meter.status !is MeterStatus.Running) {
            meterUnit = unit
            meter = PerformanceMeter(Runs.forUnit(unit))
        }
        val runs = if (loc.hasSpeed()) meter.add(elapsedMs, loc.speed.toDouble(), loc.time) else emptyList()
        PerformanceStore.add(runs)
        recentFixes.addLast(elapsedMs)
        while (recentFixes.size > 10) recentFixes.removeFirst()
        val hz = if (recentFixes.size >= 3) (recentFixes.size - 1) * 1000.0 / (recentFixes.last() - recentFixes.first()).coerceAtLeast(1) else null
        val offset = clockOffsetMs(loc.time, loc.elapsedRealtimeNanos, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        _state.update {
            it.copy(
                fix = gpsFix, providerEnabled = true, clockOffsetMs = offset,
                meter = meter.status, updateHz = hz,
                lastRun = runs.lastOrNull() ?: it.lastRun,
                lastRunElapsedMs = if (runs.isNotEmpty()) elapsedMs else it.lastRunElapsedMs,
            )
        }
    }
}
