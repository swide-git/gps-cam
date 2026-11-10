package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    private val p = TrackPoint(1_794_300_000_000, 123, 35.6812345, 139.7671234, 42.5, 88.9, 4.0)

    @Test fun storageRoundTrip() {
        assertEquals(p, TrackFormat.parseStorageLine(TrackFormat.storageLine(p)))
        val noOptional = p.copy(altitudeM = null, speedMs = null, accuracyM = null)
        assertEquals(noOptional, TrackFormat.parseStorageLine(TrackFormat.storageLine(noOptional)))
        assertNull(TrackFormat.parseStorageLine("garbage"))
    }

    @Test fun gpx() {
        val gpx = TrackFormat.gpx("Tokyo <-> Osaka", listOf(p))
        assertTrue(gpx.contains("""<trkpt lat="35.6812345" lon="139.7671234"><ele>42.5</ele><time>2026-11-10T08:40:00Z</time>"""))
        assertTrue(gpx.contains("<speed>88.90</speed>"))
        assertTrue(gpx.contains("<name>Tokyo &lt;-&gt; Osaka</name>"))
    }

    @Test fun csv() {
        assertEquals(
            "time_utc,latitude,longitude,altitude_m,speed_m_s,speed_km_h,accuracy_m\n" +
                "2026-11-10T08:40:00Z,35.6812345,139.7671234,42.5,88.90,320.0,4.0\n",
            TrackFormat.csv(listOf(p)),
        )
    }

    @Test fun summarizeReplaysStats() {
        val step = 88.96 / 111_195.0
        val pts = (0..10).map { TrackPoint(1000L * it, 0, 35.0 + it * step, 139.0, null, 88.9, 5.0) }
        val s = TrackFormat.summarize(0, pts)
        assertEquals(889.6, s.distanceM, 2.0)
        assertEquals(88.9, s.maxSpeedMs, 1e-9)
        assertEquals(10_000L, s.durationMs)
        assertEquals(11, s.points)
    }
}
