package io.github.swidegit.gpscam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the telemetry box into camera frames (preview and recorded video alike). Text is
 * language-neutral (numbers, units, "SAT"), so recordings read the same whatever the phone language.
 */
class OverlayRenderer {
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 159, 28) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }
    private val chartLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 159, 28)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val chartBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
    }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * [width]x[height] is the buffer; [rotationDegrees] is how the buffer is rotated for display,
     * so the canvas is turned the other way to keep the text upright in the finished video.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, rotationDegrees: Int, gps: GpsState, settings: Settings) {
        canvas.save()
        val (w, h) = when (rotationDegrees) {
            90 -> { canvas.translate(0f, height.toFloat()); canvas.rotate(-90f); height to width }
            180 -> { canvas.translate(width.toFloat(), height.toFloat()); canvas.rotate(180f); width to height }
            270 -> { canvas.translate(width.toFloat(), 0f); canvas.rotate(90f); height to width }
            else -> width to height
        }
        // The camera stream is often 4:3 while the video is cropped to 16:9 after this effect,
        // which cut the box off at the frame edges. Draw only inside the centred 16:9 area.
        val visible = visibleArea(w.toFloat(), h.toFloat(), VIDEO_ASPECT)
        canvas.translate(visible.left, visible.top)
        drawUpright(canvas, visible.width, visible.height, gps, settings)
        canvas.restore()
    }

    private fun drawUpright(canvas: Canvas, w: Float, h: Float, gps: GpsState, settings: Settings, scale: Float = 1f) {
        val unit = settings.unit
        val short = min(w, h)
        val margin = short * 0.035f
        val size = scale * settings.overlaySize.scale
        big.textSize = short * 0.13f * size
        unitPaint.textSize = short * 0.05f * size
        small.textSize = short * 0.032f * size
        box.alpha = settings.overlayOpacity.coerceIn(0, 100) * 255 / 100

        val now = SystemClock.elapsedRealtime()
        val fix = gps.fix
        val fresh = gps.isFresh(now)
        val speedText = fix?.speedMs?.let { Format.speed(it, unit) } ?: "--"
        big.alpha = if (fresh) 255 else 110

        val lines = buildList {
            // Maximum and distance belong to a trip; without one the box shows live values only.
            val parts = mutableListOf<String>()
            if (gps.tripActive) parts += "MAX ${Format.speed(gps.trip.maxSpeedMs, unit)}"
            if (settings.overlaySatellites) parts += "SAT ${gps.usedInFix}/${gps.satellites.size}"
            fix?.accuracyM?.let { parts += "±${it.toInt()} m" }
            if (!fresh) parts += if (fix == null) "NO FIX" else "FIX ${(now - fix.elapsedMs) / 1000} s"
            if (parts.isNotEmpty()) add(parts.joinToString("  "))
            val clock = time.format(Date(fix?.utcMs ?: System.currentTimeMillis()))
            val alt = fix?.altitudeM?.takeIf { settings.overlayAltitude }?.let { "  ALT ${Format.altitude(it, unit)}" } ?: ""
            add(clock + alt)
            val extra = buildList {
                if (settings.overlayHeading) fix?.bearingDeg?.let { add(String.format(Locale.US, "HDG %03.0f° %s", it, Format.compassPoint(it))) }
                if (settings.overlayDistance && gps.tripActive) add("DIST ${Format.distance(gps.trip.distanceM, unit)}")
            }
            if (extra.isNotEmpty()) add(extra.joinToString("  "))
            if (settings.overlayCoordinates && fix != null) add(Format.coordinates(fix.lat, fix.lon))
            // Acceleration timing: live while a run is on, the result for a while after it.
            val meter = gps.meter
            val run = gps.lastRun
            when {
                meter is MeterStatus.Running -> add("TIMER ${formatRunTime(meter.elapsedMs)}")
                run != null && now - gps.lastRunElapsedMs < RESULT_SHOWN_MS ->
                    add("${run.label}  ${formatRunTime(run.timeMs)}" + (run.trapSpeedMs?.let { " @ ${Format.speed(it, unit)} ${unit.label}" } ?: ""))
            }
        }

        val speedWidth = big.measureText(speedText) + margin * 0.4f + unitPaint.measureText(unit.label)
        val lineHeight = small.textSize * 1.3f
        val boxWidth = max(speedWidth, lines.maxOf { small.measureText(it) }) + margin * 2
        if (scale == 1f && boxWidth > w - margin * 2) {
            // Narrow portrait frames: shrink the text until the box fits between the margins.
            return drawUpright(canvas, w, h, gps, settings, (w - margin * 4) / (boxWidth - margin * 2))
        }
        val bigAscent = -big.ascent()
        // Mini chart of the last minute under the speed, as wide as the text.
        val chart = if (settings.overlayChart) recentSpeeds(gps, unit) else emptyList()
        val chartHeight = if (chart.size >= 2) small.textSize * 2.4f else 0f
        val boxHeight = bigAscent + big.descent() * 0.4f + chartHeight + lines.size * lineHeight + margin * 1.4f
        val left = when (settings.overlayCorner) {
            OverlayCorner.BOTTOM_LEFT, OverlayCorner.TOP_LEFT -> margin
            else -> w - margin - boxWidth
        }
        val top = when (settings.overlayCorner) {
            OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT -> margin
            else -> h - margin - boxHeight
        }
        canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), margin * 0.5f, margin * 0.5f, box)

        var y = top + margin * 0.7f + bigAscent
        canvas.drawText(speedText, left + margin, y, big)
        canvas.drawText(unit.label, left + margin + big.measureText(speedText) + margin * 0.4f, y, unitPaint)
        y += big.descent() * 0.4f
        if (chartHeight > 0f) {
            drawChart(canvas, chart, left + margin, y + chartHeight * 0.2f, boxWidth - margin * 2, chartHeight * 0.75f)
            y += chartHeight
        }
        for (line in lines) {
            y += lineHeight
            canvas.drawText(line, left + margin, y - small.descent(), small)
        }
    }

    /** (seconds before now, speed in [unit]) for the last [CHART_WINDOW_MS] of the track. */
    private fun recentSpeeds(gps: GpsState, unit: SpeedUnit): List<Pair<Float, Float>> {
        val track = gps.recent
        val end = track.lastOrNull()?.utcMs ?: return emptyList()
        val out = ArrayList<Pair<Float, Float>>()
        for (i in track.indices.reversed()) {
            val p = track[i]
            if (end - p.utcMs > CHART_WINDOW_MS) break
            val v = p.speedMs ?: continue
            out += ((end - p.utcMs) / 1000f) to unit.fromMetresPerSecond(v).toFloat()
        }
        return out
    }

    private fun drawChart(canvas: Canvas, samples: List<Pair<Float, Float>>, x: Float, y: Float, width: Float, height: Float) {
        val top = niceAxisMax(samples.maxOf { it.second }.toDouble()).toFloat()
        val window = CHART_WINDOW_MS / 1000f
        chartLine.strokeWidth = height * 0.06f
        chartBase.strokeWidth = height * 0.03f
        canvas.drawLine(x, y + height, x + width, y + height, chartBase)
        val path = Path()
        samples.forEachIndexed { i, (ago, v) ->
            val px = x + width * (1f - ago / window)
            val py = y + height * (1f - v / top)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, chartLine)
    }

    companion object {
        const val CHART_WINDOW_MS = 60_000L
        /** Long side over short side of the recorded video (Quality.FHD and its fallbacks are 16:9). */
        const val VIDEO_ASPECT = 16f / 9f
        /** How long a finished acceleration run stays in the video. */
        const val RESULT_SHOWN_MS = 15_000L
    }
}

data class Area(val left: Float, val top: Float, val width: Float, val height: Float)

/** The centred part of a [w]x[h] frame that a crop to [aspect] (long side / short side) keeps. */
fun visibleArea(w: Float, h: Float, aspect: Float): Area {
    val portrait = h > w
    val long = if (portrait) h else w
    val short = if (portrait) w else h
    val keptLong = min(long, short * aspect)
    val keptShort = min(short, long / aspect)
    val vw = if (portrait) keptShort else keptLong
    val vh = if (portrait) keptLong else keptShort
    return Area((w - vw) / 2f, (h - vh) / 2f, vw, vh)
}
