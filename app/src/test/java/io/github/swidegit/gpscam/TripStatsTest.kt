package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStatsTest {
    // ~0.0008 degrees of latitude = 88.96 m, i.e. 320 km/h over one second.
    private val step = 88.96 / 111_195.0

    @Test fun haversineOneDegreeLatitude() {
        assertEquals(111_195.0, haversineMetres(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test fun movingTrainAccumulates() {
        val t = TripStats()
        for (i in 0..10) t.add(Fix(i * 1000L, 35.0 + i * step, 139.0, 88.9, 5.0))
        val s = t.current
        assertEquals(889.6, s.distanceM, 2.0)
        assertEquals(10_000, s.movingTimeMs)
        assertEquals(88.9, s.maxSpeedMs, 1e-9)
        assertEquals(88.96, s.averageMovingSpeedMs, 0.3)
    }

    @Test fun jitterWhileStandingAddsNothing() {
        val t = TripStats()
        for (i in 0..10) t.add(Fix(i * 1000L, 35.0 + (i % 2) * 0.00003, 139.0, 0.1, 5.0))
        assertEquals(0.0, t.current.distanceM, 0.0)
        assertEquals(0L, t.current.movingTimeMs)
        assertEquals(10_000L, t.current.elapsedMs)
    }

    @Test fun inaccurateFixDoesNotSetMaximum() {
        val t = TripStats()
        t.add(Fix(0, 35.0, 139.0, 50.0, 5.0))
        t.add(Fix(1000, 35.0, 139.0, 400.0, 150.0))
        assertEquals(50.0, t.current.maxSpeedMs, 0.0)
    }

    @Test fun speedDerivedWhenChipReportsNone() {
        val t = TripStats()
        t.add(Fix(0, 35.0, 139.0, null, 5.0))
        val v = t.speedOf(Fix(1000, 35.0 + step, 139.0, null, 5.0))!!
        assertEquals(88.96, v, 0.2)
    }

    @Test fun tunnelGapCountsDistanceButNotTime() {
        val t = TripStats()
        t.add(Fix(0, 35.0, 139.0, 80.0, 5.0))
        t.add(Fix(60_000, 35.0 + 60 * step, 139.0, 80.0, 5.0))
        assertEquals(60 * 88.96, t.current.distanceM, 5.0)
        assertEquals(0L, t.current.movingTimeMs)
    }
}

class SpikeFilterTest {
    @Test fun impossibleAccelerationIsNotMaximum() {
        val t = TripStats()
        t.add(Fix(0, 35.0, 139.0, 80.0, 5.0))
        t.add(Fix(1000, 35.0, 139.0, 140.0, 5.0)) // +60 m/s in 1 s: a glitch
        t.add(Fix(2000, 35.0, 139.0, 81.0, 5.0))
        assertEquals(81.0, t.current.maxSpeedMs, 0.0)
    }

    @Test fun uncertainSpeedIsNotMaximum() {
        val t = TripStats()
        t.add(Fix(0, 35.0, 139.0, 80.0, 5.0, speedAccuracyMs = 0.5))
        t.add(Fix(1000, 35.0, 139.0, 85.0, 5.0, speedAccuracyMs = 9.0))
        assertEquals(80.0, t.current.maxSpeedMs, 0.0)
    }

    @Test fun realAccelerationCounts() {
        val t = TripStats()
        // A supercar: 0 to 100 km/h in about 3 s.
        listOf(0.0, 9.0, 18.0, 27.8).forEachIndexed { i, v -> t.add(Fix(i * 1000L, 35.0, 139.0, v, 5.0, 0.3)) }
        assertEquals(27.8, t.current.maxSpeedMs, 0.0)
    }
}
