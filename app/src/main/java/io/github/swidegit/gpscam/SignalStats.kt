package io.github.swidegit.gpscam

import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class MeasurementsStatus { UNKNOWN, OK, NOT_SUPPORTED, LOCATION_DISABLED }

/** Raw-measurement details (Android GnssMeasurement / GnssClock). */
data class ReceiverInfo(
    val measurements: MeasurementsStatus = MeasurementsStatus.UNKNOWN,
    val agc: List<AgcMonitor.Band> = emptyList(),
    /** Measurements flagged by the chip as affected by multipath in the latest epoch. */
    val multipath: Int = 0,
    val measured: Int = 0,
    val leapSeconds: Int? = null,
    val timeUncertaintyNs: Double? = null,
)

/**
 * Signal statistics over time: the sky signal map, one quality sample per second and the
 * receiver's raw-measurement details. The two accumulating lists are published every few seconds.
 */
object SignalStats {
    private const val PUBLISH_MS = 5_000L
    private const val MAX_SAMPLES = 21_600 // about six hours at one per second

    private val _skyMap = MutableStateFlow<List<SignalSkyMap.Cell>>(emptyList())
    val skyMap: StateFlow<List<SignalSkyMap.Cell>> = _skyMap

    private val _quality = MutableStateFlow<List<QualitySample>>(emptyList())
    val quality: StateFlow<List<QualitySample>> = _quality

    private val _receiver = MutableStateFlow(ReceiverInfo())
    val receiver: StateFlow<ReceiverInfo> = _receiver

    private val lock = Any()
    private val skyMapAcc = SignalSkyMap()
    private val agc = AgcMonitor()
    private val samples = ArrayList<QualitySample>()
    private var publishedAt = 0L
    // Sample times come from the monotonic clock, anchored once: a change of the phone's wall
    // clock must not make the charts jump.
    private val wallMinusElapsed = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    fun onSatellites(sats: List<Satellite>, gps: GpsState, elapsedMs: Long) {
        val fresh = gps.isFresh(elapsedMs)
        val sample = QualitySample(
            utcMs = wallMinusElapsed + elapsedMs,
            used = sats.count { it.usedInFix },
            visible = sats.size,
            accuracyM = gps.fix?.accuracyM?.takeIf { fresh },
            meanCn0 = topMeanCn0(sats),
            fix3d = fresh && gps.fixType != FixType.FIX_2D,
            hasFix = fresh,
        )
        synchronized(lock) {
            skyMapAcc.add(sats)
            samples += sample
            if (samples.size > MAX_SAMPLES) samples.subList(0, samples.size - MAX_SAMPLES).clear()
            if (elapsedMs - publishedAt >= PUBLISH_MS) {
                _skyMap.value = skyMapAcc.cells()
                _quality.value = samples.toList()
                publishedAt = elapsedMs
            }
        }
    }

    fun onMeasurementsStatus(status: MeasurementsStatus) = _receiver.update { it.copy(measurements = status) }

    fun onMeasurements(event: GnssMeasurementsEvent) {
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
        val bands = synchronized(lock) {
            levels.groupBy({ it.first }, { it.second }).forEach { (band, v) -> agc.add(band, v.average()) }
            agc.bands()
        }
        _receiver.update {
            it.copy(
                measurements = MeasurementsStatus.OK,
                agc = bands,
                multipath = event.measurements.count { m -> m.multipathIndicator == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED },
                measured = event.measurements.size,
                leapSeconds = if (clock.hasLeapSecond()) clock.leapSecond else it.leapSeconds,
                timeUncertaintyNs = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null,
            )
        }
    }

    /** The quality history belongs to a trip: cleared when one starts or ends. */
    fun clearQuality() {
        synchronized(lock) {
            samples.clear()
            publishedAt = 0L
        }
        _quality.value = emptyList()
    }

    /** Starts the sky signal map and the receiver gain statistics afresh. */
    fun reset() {
        synchronized(lock) {
            skyMapAcc.reset()
            agc.reset()
            publishedAt = 0L
        }
        _skyMap.value = emptyList()
        _receiver.update { it.copy(agc = emptyList()) }
    }
}
