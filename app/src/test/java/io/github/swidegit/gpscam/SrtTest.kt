package io.github.swidegit.gpscam

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTest {
    @Test fun format() {
        val text = Srt.format(
            listOf(
                SrtEntry(0, 1000, "300 km/h"),
                SrtEntry(3_661_000, 3_662_500, "301 km/h\nline 2"),
            ),
        )
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\n300 km/h\n\n2\n01:01:01,000 --> 01:01:02,500\n301 km/h\nline 2\n\n",
            text,
        )
    }
}
