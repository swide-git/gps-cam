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

class VisibleAreaTest {
    @Test fun portraitFourThreeCroppedToSixteenNine() {
        // 1440x1920 upright buffer (4:3) -> the 16:9 video keeps 1080x1920 in the middle.
        assertEquals(Area(180f, 0f, 1080f, 1920f), visibleArea(1440f, 1920f, 16f / 9f))
    }

    @Test fun landscapeFourThree() {
        assertEquals(Area(0f, 180f, 1920f, 1080f), visibleArea(1920f, 1440f, 16f / 9f))
    }

    @Test fun alreadySixteenNine() {
        assertEquals(Area(0f, 0f, 1080f, 1920f), visibleArea(1080f, 1920f, 16f / 9f))
    }
}
