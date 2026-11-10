package io.github.swidegit.gpscam

import java.util.Locale

data class SrtEntry(val startMs: Long, val endMs: Long, val text: String)

/** SubRip subtitles: a per-second telemetry track next to the video, readable by VLC and video editors. */
object Srt {
    fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }

    fun format(entries: List<SrtEntry>): String = buildString {
        entries.forEachIndexed { i, e ->
            append(i + 1).append('\n')
            append(timestamp(e.startMs)).append(" --> ").append(timestamp(e.endMs)).append('\n')
            append(e.text.trim()).append("\n\n")
        }
    }
}
