package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun shinkansenTopSpeed() {
        // 320 km/h = 88.89 m/s
        val ms = 320 / 3.6
        assertEquals("320", Format.speed(ms, SpeedUnit.KMH))
        assertEquals("199", Format.speed(ms, SpeedUnit.MPH))
        assertEquals("88.9", Format.speed(ms, SpeedUnit.MS))
        assertEquals("173", Format.speed(ms, SpeedUnit.KNOTS))
    }

    @Test fun distanceFollowsUnit() {
        assertEquals("1.61 mi", Format.distance(2589.988, SpeedUnit.MPH))
        assertEquals("515.4 km", Format.distance(515_400.0, SpeedUnit.KMH))
        assertEquals("1.00 NM", Format.distance(1852.0, SpeedUnit.KNOTS))
    }

    @Test fun duration() {
        assertEquals("0:05", Format.duration(5_000))
        assertEquals("12:34", Format.duration(754_000))
        assertEquals("2:22:00", Format.duration(8_520_000))
    }

    @Test fun coordinatesAndCompass() {
        assertEquals("35.68123° N, 139.76712° E", Format.coordinates(35.68123, 139.76712))
        assertEquals("33.86000° S, 151.21000° W", Format.coordinates(-33.86, -151.21))
        assertEquals("N", Format.compassPoint(359f))
        assertEquals("NE", Format.compassPoint(40f))
        assertEquals("W", Format.compassPoint(-90f))
    }

    @Test fun unitCycle() {
        assertEquals(SpeedUnit.KMH, SpeedUnit.KNOTS.next())
    }
}

class ChartAxisTest {
    @Test fun niceMaximum() {
        org.junit.Assert.assertEquals(10.0, niceAxisMax(3.0), 0.0)
        org.junit.Assert.assertEquals(20.0, niceAxisMax(14.0), 0.0)
        org.junit.Assert.assertEquals(50.0, niceAxisMax(31.0), 0.0)
        org.junit.Assert.assertEquals(100.0, niceAxisMax(51.0), 0.0)
        org.junit.Assert.assertEquals(350.0, niceAxisMax(320.0), 0.0)
    }
}

class AltitudeTest {
    @Test fun feetWithMphAndKnots() {
        org.junit.Assert.assertEquals("1000 m", Format.altitude(1000.0, SpeedUnit.KMH))
        org.junit.Assert.assertEquals("1000 m", Format.altitude(1000.0, SpeedUnit.MS))
        org.junit.Assert.assertEquals("3281 ft", Format.altitude(1000.0, SpeedUnit.MPH))
        org.junit.Assert.assertEquals("3281 ft", Format.altitude(1000.0, SpeedUnit.KNOTS))
    }
}
