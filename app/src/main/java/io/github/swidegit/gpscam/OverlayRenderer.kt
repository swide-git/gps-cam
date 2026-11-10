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
        big.textSize = short * 0.13f * scale
        unitPaint.textSize = short * 0.05f * scale
        small.textSize = short * 0.032f * scale

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
        if (scale == 1f && boxWidth > w - margin * 2) {
            // Narrow portrait frames: shrink the text until the box fits between the margins.
            return drawUpright(canvas, w, h, gps, settings, (w - margin * 4) / (boxWidth - margin * 2))
        }
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

    companion object {
        /** Long side over short side of the recorded video (Quality.FHD and its fallbacks are 16:9). */
        const val VIDEO_ASPECT = 16f / 9f
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
