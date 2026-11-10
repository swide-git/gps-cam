package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssTest {
    @Test fun bands() {
        assertEquals("L1", bandName(Constellation.GPS, 1575.42e6))
        assertEquals("L5", bandName(Constellation.GPS, 1176.45e6))
        assertEquals("E1", bandName(Constellation.GALILEO, 1575.42e6))
        assertEquals("E5a", bandName(Constellation.GALILEO, 1176.45e6))
        assertEquals("B1I", bandName(Constellation.BEIDOU, 1561.098e6))
        assertEquals("G1", bandName(Constellation.GLONASS, 1602.5625e6))
        assertEquals("L1", bandName(Constellation.QZSS, 1575.42e6))
        assertNull(bandName(Constellation.GPS, 1400e6))
    }

    @Test fun constellationTypes() {
        assertEquals(Constellation.QZSS, Constellation.fromType(4))
        assertEquals(Constellation.UNKNOWN, Constellation.fromType(42))
    }

    @Test fun gsa() {
        val s = "\$GNGSA,A,3,80,71,73,79,69,,,,,,,,1.83,1.09,1.47*17"
        assertTrue(Nmea.checksumValid(s))
        val gsa = Nmea.parseGsa(s)!!
        assertEquals(FixType.FIX_3D, gsa.fixType)
        assertEquals(1.83, gsa.dop.pdop!!, 1e-9)
        assertEquals(1.09, gsa.dop.hdop!!, 1e-9)
        assertEquals(1.47, gsa.dop.vdop!!, 1e-9)
    }

    @Test fun gsaWithSystemIdAndBadChecksum() {
        val s = "\$GPGSA,A,2,05,13,,,,,,,,,,,9.9,9.9,1.0,1*21"
        val good = s.substring(0, s.lastIndexOf('*') + 1) + checksum(s)
        assertEquals(FixType.FIX_2D, Nmea.parseGsa(good)!!.fixType)
        assertFalse(Nmea.checksumValid(s.replaceAfter('*', "00")))
        assertNull(Nmea.parseGsa("\$GPGGA,123519,4807.038,N*47"))
    }

    private fun checksum(s: String): String {
        var sum = 0
        for (i in 1 until s.lastIndexOf('*')) sum = sum xor s[i].code
        return "%02X".format(sum)
    }
}
