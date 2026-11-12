package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssStatsTest {
    private fun sat(c: Constellation, cn0: Float, used: Boolean, mhz: Double = 1575.42, el: Float = 45f, az: Float = 0f) =
        Satellite(c, 1, cn0, el, az, used, true, true, mhz * 1e6)

    @Test fun bandsAndDualFrequency() {
        val sats = listOf(
            sat(Constellation.GPS, 40f, true), sat(Constellation.GPS, 30f, false),
            sat(Constellation.GPS, 35f, true, 1176.45), sat(Constellation.GALILEO, 38f, true),
        )
        val stats = bandStats(sats)
        assertEquals(BandStat(Constellation.GPS, "L1", 2, 1, 35.0), stats[0])
        assertEquals(BandStat(Constellation.GPS, "L5", 1, 1, 35.0), stats[1])
        assertEquals("E1", stats[2].band)
        assertTrue(dualFrequencyInUse(sats))
        assertFalse(dualFrequencyInUse(sats.filter { it.band != "L5" }))
    }

    @Test fun topFourMean() {
        val sats = listOf(45f, 40f, 35f, 30f, 10f).map { sat(Constellation.GPS, it, true) } + sat(Constellation.GPS, 50f, false)
        assertEquals(37.5, topMeanCn0(sats)!!, 1e-9)
        assertNull(topMeanCn0(emptyList()))
    }

    @Test fun skyMapSectors() {
        val map = SignalSkyMap()
        map.add(listOf(sat(Constellation.GPS, 40f, true, az = 95f, el = 10f), sat(Constellation.GPS, 20f, true, az = 100f, el = 20f)))
        map.add(listOf(sat(Constellation.GPS, 30f, true, az = 359f, el = 80f)))
        val cells = map.cells()
        assertEquals(SignalSkyMap.Cell(3, 0, 30.0, 2), cells.first { it.ring == 0 })
        assertEquals(SignalSkyMap.Cell(11, 2, 30.0, 1), cells.first { it.ring == 2 })
        map.reset()
        assertTrue(map.cells().isEmpty())
    }

    @Test fun qualitySummary() {
        fun s(t: Long, fix: Boolean) = QualitySample(t * 1000, if (fix) 8 else 0, 12, if (fix) 5.0 else null, if (fix) 38.0 else null, fix, fix)
        val samples = listOf(s(0, true), s(1, true), s(2, false), s(3, false), s(4, false), s(5, true))
        val q = summarizeQuality(samples)!!
        assertEquals(50.0, q.fix3dPercent, 1e-9)
        assertEquals(2000L, q.longestGapMs)
        assertEquals(38.0, q.meanCn0!!, 1e-9)
    }

    @Test fun trackGap() {
        val pts = listOf(0L, 1000, 2000, 62_000, 63_000).map { TrackPoint(it, 0, 0.0, 0.0, null, null, null) }
        assertEquals(60_000L, longestTrackGapMs(pts))
    }

    @Test fun clockOffset() {
        // Fix at GPS 1000 ms, delivered 200 ms later; at delivery the phone clock reads 1700 -> phone ahead by 500 ms.
        assertEquals(500.0, clockOffsetMs(1000, 5_000_000_000, 1700, 5_200_000_000), 1e-6)
    }

    @Test fun agcDropIsFlagged() {
        val m = AgcMonitor()
        repeat(50) { m.add("L1/E1/B1", 30.0) }
        assertFalse(m.bands().single().suspicious)
        m.add("L1/E1/B1", 20.0)
        val b = m.bands().single()
        assertEquals(10.0, b.dropDb, 1e-9)
        assertTrue(b.suspicious)
        assertEquals("L5/E5a/B2a", agcBandName(1176.45e6))
    }
}
