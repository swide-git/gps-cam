package io.github.swidegit.gpscam

/**
 * A timed run: from one speed to another (0-100 km/h, 100-200 km/h), or a distance from a
 * standstill (quarter mile). Speeds are in m/s.
 */
data class RunSpec(val id: String, val label: String, val fromMs: Double, val toMs: Double? = null, val distanceM: Double? = null) {
    val fromStandstill: Boolean get() = fromMs == 0.0
}

data class RunResult(val specId: String, val label: String, val timeMs: Long, val trapSpeedMs: Double?, val utcMs: Long)

/** What the meter is doing right now, for the Speed screen and the video overlay. */
sealed interface MeterStatus {
    /** Waiting for a standstill before 0-to runs can start. */
    data object NeedStop : MeterStatus
    /** Standing still: the next launch starts the clock. */
    data object Ready : MeterStatus
    data class Running(val elapsedMs: Long) : MeterStatus
}

object Runs {
    private const val KMH = 1 / 3.6
    private const val MPH = 0.44704
    const val QUARTER_MILE_M = 402.336

    fun forUnit(unit: SpeedUnit): List<RunSpec> = if (unit == SpeedUnit.MPH) {
        listOf(
            RunSpec("0-60mph", "0-60 mph", 0.0, 60 * MPH),
            RunSpec("60-130mph", "60-130 mph", 60 * MPH, 130 * MPH),
            RunSpec("quarter", "1/4 mile", 0.0, distanceM = QUARTER_MILE_M),
        )
    } else {
        listOf(
            RunSpec("0-100", "0-100 km/h", 0.0, 100 * KMH),
            RunSpec("100-200", "100-200 km/h", 100 * KMH, 200 * KMH),
            RunSpec("quarter", "1/4 mile", 0.0, distanceM = QUARTER_MILE_M),
        )
    }
}

/**
 * Acceleration timing from GPS speed samples. Crossing times are linearly interpolated between
 * samples, so a 1 Hz receiver still gives times to about a tenth of a second; faster receivers
 * do better. A gap in the samples (tunnel, app in the background) or slowing down cancels a run.
 */
class PerformanceMeter(private val specs: List<RunSpec>) {
    private var prevT: Long? = null
    private var prevV = 0.0
    private var launchT: Double? = null
    private var distanceSinceLaunch = 0.0
    private var peakSinceLaunch = 0.0
    private var standstillSeen = false
    /** Start times of rolling runs (100-200), keyed by spec id. */
    private val rollingStart = HashMap<String, Double>()
    private val done = HashSet<String>()

    var status: MeterStatus = MeterStatus.NeedStop
        private set

    /** Feeds one sample; returns the runs completed by it. */
    fun add(tMs: Long, speedMs: Double, utcMs: Long): List<RunResult> {
        val pt = prevT
        val pv = prevV
        prevT = tMs
        prevV = speedMs
        if (pt == null || tMs - pt > MAX_GAP_MS || tMs <= pt) {
            resetLaunch()
            standstillSeen = speedMs < STOP_MS
            rollingStart.clear()
            status = if (standstillSeen) MeterStatus.Ready else MeterStatus.NeedStop
            return emptyList()
        }
        val results = mutableListOf<RunResult>()
        fun crossing(target: Double): Double = pt + (tMs - pt) * ((target - pv) / (speedMs - pv))

        if (speedMs < STOP_MS) {
            // Standing (again): arm the standing-start runs.
            resetLaunch()
            standstillSeen = true
        } else if (standstillSeen && launchT == null && pv < STOP_MS) {
            launchT = crossing(STOP_MS)
            done.clear()
            distanceSinceLaunch = 0.0
            peakSinceLaunch = speedMs
        }

        val launch = launchT
        if (launch != null) {
            val segment = (pv + speedMs) / 2 * (tMs - pt) / 1000.0
            val before = distanceSinceLaunch
            distanceSinceLaunch += segment
            peakSinceLaunch = maxOf(peakSinceLaunch, speedMs)
            if (speedMs < peakSinceLaunch - ABORT_DROP_MS) {
                // Lifted off or braked: this launch no longer counts.
                resetLaunch()
                standstillSeen = false
            } else {
                for (spec in specs.filter { it.fromStandstill && it.id !in done }) {
                    val target = spec.toMs
                    val dist = spec.distanceM
                    if (target != null && pv < target && speedMs >= target) {
                        results += RunResult(spec.id, spec.label, (crossing(target) - launch).toLong(), null, utcMs)
                        done += spec.id
                    } else if (dist != null && before < dist && distanceSinceLaunch >= dist) {
                        val frac = if (segment > 0) (dist - before) / segment else 1.0
                        val t = pt + (tMs - pt) * frac
                        results += RunResult(spec.id, spec.label, (t - launch).toLong(), pv + (speedMs - pv) * frac, utcMs)
                        done += spec.id
                    }
                }
            }
        }

        for (spec in specs.filter { !it.fromStandstill }) {
            val target = spec.toMs ?: continue
            val start = rollingStart[spec.id]
            when {
                pv < spec.fromMs && speedMs >= spec.fromMs -> rollingStart[spec.id] = crossing(spec.fromMs)
                start != null && speedMs < spec.fromMs -> rollingStart.remove(spec.id)
                start != null && pv < target && speedMs >= target -> {
                    results += RunResult(spec.id, spec.label, (crossing(target) - start).toLong(), null, utcMs)
                    rollingStart.remove(spec.id)
                }
            }
        }

        val running = launchT ?: rollingStart.values.minOrNull()
        status = when {
            running != null && (launchT == null || done.size < specs.count { it.fromStandstill }) ->
                MeterStatus.Running((tMs - running).toLong())
            speedMs < STOP_MS -> MeterStatus.Ready
            else -> MeterStatus.NeedStop
        }
        return results
    }

    private fun resetLaunch() {
        launchT = null
        distanceSinceLaunch = 0.0
        peakSinceLaunch = 0.0
    }

    companion object {
        /** Below this the vehicle counts as standing (about 2 km/h, above GPS jitter). */
        const val STOP_MS = 0.6
        /** Losing this much speed from the peak cancels a standing start (about 10 km/h). */
        const val ABORT_DROP_MS = 2.8
        const val MAX_GAP_MS = 2_500L
    }
}
