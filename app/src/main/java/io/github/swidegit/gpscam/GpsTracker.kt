package io.github.swidegit.gpscam

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurement
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

enum class MeasurementsStatus { UNKNOWN, OK, NOT_SUPPORTED, LOCATION_DISABLED }

/** Raw-measurement and clock details (Android GnssMeasurement / GnssClock). */
data class ReceiverInfo(
    val measurements: MeasurementsStatus = MeasurementsStatus.UNKNOWN,
    val agc: List<AgcMonitor.Band> = emptyList(),
    /** Measurements flagged by the chip as affected by multipath in the latest epoch. */
    val multipath: Int = 0,
    val measured: Int = 0,
    val leapSeconds: Int? = null,
    val timeUncertaintyNs: Double? = null,
    /** Phone clock minus GPS time (positive: the phone is ahead). */
    val clockOffsetMs: Double? = null,
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
    /** Points of the current trip (for the speed chart); the same points go to [TripStore]. */
    val track: List<TrackPoint> = emptyList(),
    /** Acceleration meter state and the latest completed run (shown for a while, also in the video). */
    val meter: MeterStatus = MeterStatus.NeedStop,
    val lastRun: RunResult? = null,
    val lastRunElapsedMs: Long = 0,
    /** Location updates per second, measured: timing precision depends on it. */
    val updateHz: Double? = null,
    /** Average signal per direction of the sky since the last reset. */
    val skyMap: List<SignalSkyMap.Cell> = emptyList(),
    /** One sample per second of the current trip, for the quality charts. */
    val quality: List<QualitySample> = emptyList(),
    val receiver: ReceiverInfo = ReceiverInfo(),
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
    private const val TRACK_MAX_ACCURACY_M = 50.0
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private val trip = TripStats()
    private var tripId: Long? = null
    private val track = ArrayList<TrackPoint>()
    private var meterUnit: SpeedUnit? = null
    private var meter = PerformanceMeter(emptyList())
    private val recentFixes = ArrayDeque<Long>()
    private val skyMap = SignalSkyMap()
    private val agc = AgcMonitor()
    private val quality = ArrayList<QualitySample>()

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = onMeasurements(event)
        override fun onStatusChanged(status: Int) {
            val s = when (status) {
                STATUS_READY -> MeasurementsStatus.OK
                STATUS_NOT_SUPPORTED -> MeasurementsStatus.NOT_SUPPORTED
                STATUS_LOCATION_DISABLED -> MeasurementsStatus.LOCATION_DISABLED
                else -> MeasurementsStatus.UNKNOWN
            }
            _state.update { it.copy(receiver = it.receiver.copy(measurements = s)) }
        }
    }
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
            val now = SystemClock.elapsedRealtime()
            val current = _state.value
            val sample = QualitySample(
                utcMs = System.currentTimeMillis(),
                used = sats.count { it.usedInFix },
                visible = sats.size,
                accuracyM = current.fix?.accuracyM?.takeIf { current.isFresh(now) },
                meanCn0 = topMeanCn0(sats),
                fix3d = current.isFresh(now) && current.fixType != FixType.FIX_2D,
                hasFix = current.isFresh(now),
            )
            val (cells, samples) = synchronized(quality) {
                skyMap.add(sats)
                quality += sample
                // About six hours at one sample per second.
                if (quality.size > 21_600) quality.subList(0, quality.size - 21_600).clear()
                skyMap.cells() to quality.toList()
            }
            _state.update { it.copy(satellites = sats, skyMap = cells, quality = samples) }
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

    @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION first.
    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        TripStore.init(context)
        PerformanceStore.init(context)
        TtffStore.init(context)
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
            // Raw measurements (AGC, multipath, clock) are optional: many phones do not report them.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.registerGnssMeasurementsCallback({ r -> handler.post(r) }, measurementsCallback)
                } else {
                    @Suppress("DEPRECATION")
                    lm.registerGnssMeasurementsCallback(measurementsCallback, handler)
                }
            }.onFailure { Log.w(TAG, "raw measurements unavailable", it) }
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
        runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
        thread?.quitSafely()
        thread = null
        manager = null
        _state.update { it.copy(running = false, satellites = emptyList(), fixType = FixType.NONE) }
    }

    /** Saves the current trip to the history and starts a new one. */
    fun endTrip() {
        synchronized(trip) {
            tripId?.let { TripStore.endTrip(it, track.toList(), trip.current) }
            trip.reset()
            track.clear()
            tripId = null
        }
        synchronized(quality) { quality.clear() }
        _state.update { it.copy(trip = TripSnapshot(), track = emptyList(), quality = emptyList()) }
    }

    /** Starts the sky signal map and the receiver gain statistics afresh. */
    fun resetSignalStats() {
        synchronized(quality) {
            skyMap.reset()
            agc.reset()
        }
        _state.update { it.copy(skyMap = emptyList(), receiver = it.receiver.copy(agc = emptyList())) }
    }

    private fun onMeasurements(event: GnssMeasurementsEvent) {
        val levels = ArrayList<Pair<String, Double>>()
        if (Build.VERSION.SDK_INT >= 33) {
            event.gnssAutomaticGainControls.forEach { levels += agcBandName(it.carrierFrequencyHz.toDouble()) to it.levelDb }
        }
        if (levels.isEmpty()) {
            // Before Android 13 (and on some chips after it) the AGC level comes per measurement.
            @Suppress("DEPRECATION")
            for (m in event.measurements) {
                if (m.hasAutomaticGainControlLevelDb() && m.hasCarrierFrequencyHz()) {
                    levels += agcBandName(m.carrierFrequencyHz.toDouble()) to m.automaticGainControlLevelDb
                }
            }
        }
        val clock = event.clock
        val bands = synchronized(quality) {
            levels.groupBy({ it.first }, { it.second }).forEach { (band, v) -> agc.add(band, v.average()) }
            agc.bands()
        }
        _state.update {
            it.copy(
                receiver = it.receiver.copy(
                    measurements = MeasurementsStatus.OK,
                    agc = bands,
                    multipath = event.measurements.count { m -> m.multipathIndicator == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED },
                    measured = event.measurements.size,
                    leapSeconds = if (clock.hasLeapSecond()) clock.leapSecond else it.receiver.leapSeconds,
                    timeUncertaintyNs = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null,
                ),
            )
        }
    }

    /** Id of the trip in progress (its start time), or null before the first good fix. */
    fun currentTripId(): Long? = synchronized(trip) { tripId }

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
        val (speed, snapshot, points) = synchronized(trip) {
            val speed = trip.speedOf(fix)
            val snapshot = trip.add(fix)
            val last = track.lastOrNull()
            // One point per second at most, and only usable positions.
            val record = (accuracy ?: Double.MAX_VALUE) <= TRACK_MAX_ACCURACY_M && (last == null || elapsedMs - last.elapsedMs >= 1000)
            if (record) {
                val id = tripId ?: loc.time.also { tripId = it }
                val point = TrackPoint(loc.time, elapsedMs, loc.latitude, loc.longitude, altitude, speed, accuracy)
                track += point
                TripStore.append(id, point)
            }
            Triple(speed, snapshot, if (record) track.toList() else null)
        }
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
        // Acceleration timing uses the receiver's Doppler speed only: derived speeds lag.
        val unit = AppSettings.state.value.unit
        if (unit != meterUnit) {
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
                receiver = it.receiver.copy(clockOffsetMs = offset),
                fix = gpsFix, trip = snapshot, track = points ?: it.track, providerEnabled = true,
                meter = meter.status, updateHz = hz,
                lastRun = runs.lastOrNull() ?: it.lastRun,
                lastRunElapsedMs = if (runs.isNotEmpty()) elapsedMs else it.lastRunElapsedMs,
            )
        }
    }
}
