package io.github.swidegit.gpscam

/** Bands of the second (modernised) civil frequency: L5, E5a, B2a. */
private val SECOND_FREQUENCY = setOf("L5", "E5a", "B2a")

data class BandStat(val constellation: Constellation, val band: String, val visible: Int, val used: Int, val meanCn0: Double)

/** Satellites grouped by system and band, strongest systems first. */
fun bandStats(sats: List<Satellite>): List<BandStat> =
    sats.filter { it.cn0DbHz > 0f }
        .groupBy { it.constellation to (it.band ?: "?") }
        .map { (key, list) -> BandStat(key.first, key.second, list.size, list.count { it.usedInFix }, list.map { it.cn0DbHz.toDouble() }.average()) }
        .sortedWith(compareBy<BandStat> { it.constellation.ordinal }.thenBy { it.band })

/** True when the fix uses L5 / E5a / B2a signals, i.e. the receiver works dual-frequency. */
fun dualFrequencyInUse(sats: List<Satellite>): Boolean = sats.any { it.usedInFix && it.band in SECOND_FREQUENCY }

/** Mean C/N0 of the [k] strongest satellites used in the fix: the usual single "signal quality" number. */
fun topMeanCn0(sats: List<Satellite>, k: Int = 4): Double? =
    sats.filter { it.usedInFix && it.cn0DbHz > 0f }.map { it.cn0DbHz.toDouble() }.sortedDescending().take(k).takeIf { it.isNotEmpty() }?.average()

/**
 * Average signal strength per direction of the sky, accumulated over time: sectors of azimuth
 * (north first, clockwise) times rings of elevation (horizon first). On a train it shows which
 * side, and how high, the signal gets in.
 */
class SignalSkyMap(val sectors: Int = 12, val rings: Int = 3) {
    private val sum = DoubleArray(sectors * rings)
    private val count = IntArray(sectors * rings)

    data class Cell(val sector: Int, val ring: Int, val meanCn0: Double, val samples: Int)

    fun add(sats: List<Satellite>) {
        for (s in sats) {
            if (s.cn0DbHz <= 0f || s.elevationDeg <= 0f) continue
            val sector = ((((s.azimuthDeg % 360f) + 360f) % 360f) / (360f / sectors)).toInt().coerceIn(0, sectors - 1)
            val ring = (s.elevationDeg / (90f / rings)).toInt().coerceIn(0, rings - 1)
            sum[ring * sectors + sector] += s.cn0DbHz.toDouble()
            count[ring * sectors + sector]++
        }
    }

    fun cells(): List<Cell> = (0 until rings).flatMap { r ->
        (0 until sectors).mapNotNull { s ->
            val i = r * sectors + s
            if (count[i] == 0) null else Cell(s, r, sum[i] / count[i], count[i])
        }
    }

    fun reset() {
        sum.fill(0.0)
        count.fill(0)
    }
}

/** One second of receiver quality, for the charts and the trip quality summary. */
data class QualitySample(
    val utcMs: Long,
    val used: Int,
    val visible: Int,
    val accuracyM: Double?,
    val meanCn0: Double?,
    /** A fresh 3D fix at this moment. */
    val fix3d: Boolean,
    val hasFix: Boolean,
)

data class QualitySummary(val fix3dPercent: Double, val longestGapMs: Long, val meanUsed: Double, val meanCn0: Double?, val meanAccuracyM: Double?)

fun summarizeQuality(samples: List<QualitySample>): QualitySummary? {
    if (samples.size < 2) return null
    var longest = 0L
    var gapStart: Long? = null
    for (s in samples) {
        if (!s.hasFix) {
            if (gapStart == null) gapStart = s.utcMs
            longest = maxOf(longest, s.utcMs - gapStart)
        } else {
            gapStart = null
        }
    }
    return QualitySummary(
        fix3dPercent = samples.count { it.fix3d } * 100.0 / samples.size,
        longestGapMs = longest,
        meanUsed = samples.map { it.used }.average(),
        meanCn0 = samples.mapNotNull { it.meanCn0 }.takeIf { it.isNotEmpty() }?.average(),
        meanAccuracyM = samples.mapNotNull { it.accuracyM }.takeIf { it.isNotEmpty() }?.average(),
    )
}

/** Longest stretch without track points (tunnels), from a saved track. */
fun longestTrackGapMs(points: List<TrackPoint>): Long = points.zipWithNext { a, b -> b.utcMs - a.utcMs }.maxOrNull() ?: 0L

/**
 * Phone clock minus GPS time, in ms (positive: the phone is ahead). The GPS UTC time of a fix is
 * compared with the wall clock at the same instant, using the monotonic clock to undo the
 * delivery delay of the fix.
 */
fun clockOffsetMs(fixUtcMs: Long, fixElapsedNs: Long, nowWallMs: Long, nowElapsedNs: Long): Double {
    val wallAtFix = nowWallMs - (nowElapsedNs - fixElapsedNs) / 1e6
    return wallAtFix - fixUtcMs
}

/**
 * Receiver gain (AGC) per band. The AGC level falls when the receiver sees more noise, so a drop
 * well below its usual level while signals weaken points at interference (jamming, a noisy USB
 * charger, a train's electronics). Indicative only: the absolute values differ between chipsets.
 */
class AgcMonitor(private val window: Int = 300) {
    data class Band(val name: String, val currentDb: Double, val baselineDb: Double, val minDb: Double, val maxDb: Double) {
        val dropDb: Double get() = baselineDb - currentDb
        val suspicious: Boolean get() = dropDb >= SUSPICIOUS_DROP_DB
    }

    private val history = LinkedHashMap<String, ArrayDeque<Double>>()
    private val extremes = HashMap<String, Pair<Double, Double>>()

    fun add(band: String, levelDb: Double) {
        val h = history.getOrPut(band) { ArrayDeque() }
        h.addLast(levelDb)
        while (h.size > window) h.removeFirst()
        val (lo, hi) = extremes[band] ?: (levelDb to levelDb)
        extremes[band] = minOf(lo, levelDb) to maxOf(hi, levelDb)
    }

    fun bands(): List<Band> = history.map { (name, h) ->
        val sorted = h.sorted()
        val (lo, hi) = extremes.getValue(name)
        Band(name, h.last(), sorted[sorted.size / 2], lo, hi)
    }

    fun reset() {
        history.clear()
        extremes.clear()
    }

    companion object {
        const val SUSPICIOUS_DROP_DB = 6.0
    }
}

/** Band name from a carrier frequency regardless of system, for AGC readings. */
fun agcBandName(carrierHz: Double): String {
    val mhz = carrierHz / 1e6
    return when {
        mhz in 1559.0..1610.0 -> "L1/E1/B1"
        mhz in 1164.0..1189.0 -> "L5/E5a/B2a"
        mhz in 1195.0..1219.0 -> "E5b/B2b"
        mhz in 1215.0..1255.0 -> "L2/G2"
        mhz in 1260.0..1300.0 -> "E6/L6/B3"
        else -> String.format(java.util.Locale.US, "%.0f MHz", mhz)
    }
}
