package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTest {
    private val specs = Runs.forUnit(SpeedUnit.KMH)

    /** Constant acceleration [a] m/s² from a standstill, sampled at [hz]; speeds capped at [vMax]. */
    private fun launch(meter: PerformanceMeter, a: Double, hz: Int, seconds: Int, vMax: Double = 1e9): List<RunResult> {
        val results = mutableListOf<RunResult>()
        // Two seconds standing still first.
        for (i in 0 until 2 * hz) results += meter.add(i * 1000L / hz, 0.0, 0)
        val t0 = 2000L
        for (i in 0..seconds * hz) {
            val t = i.toDouble() / hz
            results += meter.add(t0 + (t * 1000).toLong(), minOf(a * t, vMax), 0)
        }
        return results
    }

    @Test fun zeroToHundredAt1Hz() {
        // 100 km/h = 27.78 m/s; at 8 m/s² that takes 3.47 s (plus the 0.075 s to the 0.6 m/s launch threshold).
        val r = launch(PerformanceMeter(specs), 8.0, 1, 10).first { it.specId == "0-100" }
        assertEquals(3472.0 - 75, r.timeMs.toDouble(), 30.0)
    }

    @Test fun hundredToTwoHundredAt10Hz() {
        // 27.78 -> 55.56 m/s at 5 m/s²: 5.56 s.
        val r = launch(PerformanceMeter(specs), 5.0, 10, 14).first { it.specId == "100-200" }
        assertEquals(5556.0, r.timeMs.toDouble(), 20.0)
    }

    @Test fun quarterMileWithTrapSpeed() {
        // s = a t² / 2 -> t = sqrt(2 * 402.336 / 6) = 11.58 s, v = 69.5 m/s.
        val r = launch(PerformanceMeter(specs), 6.0, 10, 14).first { it.specId == "quarter" }
        assertEquals(11581.0 - 100, r.timeMs.toDouble(), 60.0)
        assertEquals(69.5, r.trapSpeedMs!!, 0.5)
    }

    @Test fun noStandstillNoStandingStart() {
        val meter = PerformanceMeter(specs)
        val results = (0..20).flatMap { meter.add(it * 1000L, 10.0 + it * 2, 0) }
        assertTrue(results.none { it.specId == "0-100" || it.specId == "quarter" })
        // 10 -> 50 m/s passes 100 km/h, so the rolling 100-200 run is timing (no standstill needed).
        assertTrue(meter.status is MeterStatus.Running)
    }

    @Test fun brakingCancelsTheRun() {
        val meter = PerformanceMeter(specs)
        for (i in 0..2) meter.add(i * 1000L, 0.0, 0)
        val speeds = listOf(5.0, 10.0, 15.0, 8.0, 14.0, 20.0, 28.0, 35.0)
        val results = speeds.flatMapIndexed { i, v -> meter.add(3000L + i * 1000L, v, 0) }
        assertTrue(results.none { it.specId == "0-100" })
    }

    @Test fun gapResets() {
        val meter = PerformanceMeter(specs)
        for (i in 0..2) meter.add(i * 1000L, 0.0, 0)
        meter.add(3000, 5.0, 0)
        val results = meter.add(10_000, 30.0, 0) // 7 s without data
        assertTrue(results.isEmpty())
        assertEquals(MeterStatus.NeedStop, meter.status)
    }

    @Test fun mphRuns() {
        val r = launch(PerformanceMeter(Runs.forUnit(SpeedUnit.MPH)), 8.0, 10, 8).first { it.specId == "0-60mph" }
        // 60 mph = 26.82 m/s -> 3.35 s, minus the launch threshold.
        assertEquals(3353.0 - 75, r.timeMs.toDouble(), 20.0)
    }
}
