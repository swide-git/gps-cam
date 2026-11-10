package io.github.swidegit.gpscam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        drawUpright(canvas, w.toFloat(), h.toFloat(), gps, settings)
        canvas.restore()
    }

    private fun drawUpright(canvas: Canvas, w: Float, h: Float, gps: GpsState, settings: Settings) {
        val unit = settings.unit
        val short = min(w, h)
        val margin = short * 0.035f
        big.textSize = short * 0.13f
        unitPaint.textSize = short * 0.05f
        small.textSize = short * 0.032f

        val now = SystemClock.elapsedRealtime()
        val fix = gps.fix
        val fresh = gps.isFresh(now)
        val speedText = fix?.speedMs?.let { Format.speed(it, unit) } ?: "--"
        big.alpha = if (fresh) 255 else 110

        val lines = buildList {
            val parts = mutableListOf("MAX ${Format.speed(gps.trip.maxSpeedMs, unit)}")
            if (settings.overlaySatellites) parts += "SAT ${gps.usedInFix}/${gps.satellites.size}"
            fix?.accuracyM?.let { parts += "±${it.toInt()} m" }
            if (!fresh) parts += if (fix == null) "NO FIX" else "FIX ${(now - fix.elapsedMs) / 1000} s"
            add(parts.joinToString("  "))
            val clock = time.format(Date(fix?.utcMs ?: System.currentTimeMillis()))
            val alt = fix?.altitudeM?.let { "  ALT ${it.toInt()} m" } ?: ""
            add(clock + alt)
            if (settings.overlayCoordinates && fix != null) add(Format.coordinates(fix.lat, fix.lon))
        }

        val speedWidth = big.measureText(speedText) + margin * 0.4f + unitPaint.measureText(unit.label)
        val lineHeight = small.textSize * 1.3f
        val boxWidth = max(speedWidth, lines.maxOf { small.measureText(it) }) + margin * 2
        val bigAscent = -big.ascent()
        val boxHeight = bigAscent + big.descent() * 0.4f + lines.size * lineHeight + margin * 1.4f
        val left = margin
        val top = h - margin - boxHeight
        canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), margin * 0.5f, margin * 0.5f, box)

        var y = top + margin * 0.7f + bigAscent
        canvas.drawText(speedText, left + margin, y, big)
        canvas.drawText(unit.label, left + margin + big.measureText(speedText) + margin * 0.4f, y, unitPaint)
        y += big.descent() * 0.4f
        for (line in lines) {
            y += lineHeight
            canvas.drawText(line, left + margin, y - small.descent(), small)
        }
    }
}
