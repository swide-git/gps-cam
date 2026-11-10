package io.github.swidegit.gpscam

import kotlin.math.abs

/** GNSS systems as reported by android.location.GnssStatus.getConstellationType. */
enum class Constellation(val label: String, val shortLabel: String, val argb: Long) {
    GPS("GPS", "GPS", 0xFF4FC3F7),
    GLONASS("GLONASS", "GLO", 0xFFEF5350),
    GALILEO("Galileo", "GAL", 0xFF7986CB),
    BEIDOU("BeiDou", "BDS", 0xFFFFB74D),
    QZSS("QZSS", "QZS", 0xFFBA68C8),
    SBAS("SBAS", "SBS", 0xFF90A4AE),
    IRNSS("NavIC", "NAV", 0xFF81C784),
    UNKNOWN("?", "?", 0xFF757575);

    companion object {
        // Values of GnssStatus.CONSTELLATION_*; literals keep this usable in JVM unit tests.
        fun fromType(type: Int): Constellation = when (type) {
            1 -> GPS
            2 -> SBAS
            3 -> GLONASS
            4 -> QZSS
            5 -> BEIDOU
            6 -> GALILEO
            7 -> IRNSS
            else -> UNKNOWN
        }
    }
}

data class Satellite(
    val constellation: Constellation,
    val svid: Int,
    val cn0DbHz: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierHz: Double?,
) {
    val band: String? get() = carrierHz?.let { bandName(constellation, it) }
}

/** Signal band from the carrier frequency; null when it matches no known GNSS band. */
fun bandName(constellation: Constellation, carrierHz: Double): String? {
    val mhz = carrierHz / 1e6
    fun near(f: Double, tol: Double = 2.0) = abs(mhz - f) <= tol
    return when {
        // GLONASS FDMA L1 spans about 1598-1606 MHz.
        constellation == Constellation.GLONASS && mhz in 1597.0..1607.0 -> "G1"
        constellation == Constellation.GLONASS && mhz in 1241.0..1250.0 -> "G2"
        near(1575.42) -> when (constellation) {
            Constellation.GALILEO -> "E1"
            Constellation.BEIDOU -> "B1C"
            else -> "L1"
        }
        near(1561.098) -> "B1I"
        near(1176.45) -> when (constellation) {
            Constellation.GALILEO -> "E5a"
            Constellation.BEIDOU -> "B2a"
            else -> "L5"
        }
        near(1207.14) -> if (constellation == Constellation.GALILEO) "E5b" else "B2"
        near(1227.60) -> "L2"
        near(1278.75) -> if (constellation == Constellation.QZSS) "L6" else "E6"
        near(1268.52) -> "B3"
        else -> null
    }
}

enum class FixType { NONE, FIX_2D, FIX_3D }

data class Dop(val pdop: Double?, val hdop: Double?, val vdop: Double?)

data class GsaSentence(val fixType: FixType, val dop: Dop)

/** Minimal NMEA 0183 parsing: only GSA, which carries the fix mode and the DOP values Android does not expose. */
object Nmea {
    fun checksumValid(sentence: String): Boolean {
        val s = sentence.trim()
        val star = s.lastIndexOf('*')
        if (!s.startsWith("$") || star < 0 || star + 3 > s.length) return false
        val expected = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return false
        var sum = 0
        for (i in 1 until star) sum = sum xor s[i].code
        return sum == expected
    }

    /** $xxGSA,A,3,sv1..sv12,PDOP,HDOP,VDOP[,systemId]*CS */
    fun parseGsa(sentence: String): GsaSentence? {
        val s = sentence.trim()
        if (s.length < 7 || s.substring(3, 6) != "GSA" || !checksumValid(s)) return null
        val fields = s.substring(1, s.lastIndexOf('*')).split(',')
        if (fields.size < 18) return null
        val fixType = when (fields[2]) {
            "2" -> FixType.FIX_2D
            "3" -> FixType.FIX_3D
            else -> FixType.NONE
        }
        return GsaSentence(fixType, Dop(fields[15].toDoubleOrNull(), fields[16].toDoubleOrNull(), fields[17].toDoubleOrNull()))
    }
}
