package io.github.swidegit.gpscam

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object Palette {
    val accent = Color(0xFFFF9F1C)
    val good = Color(0xFF66BB6A)
    val warn = Color(0xFFFFCA28)
    val bad = Color(0xFFEF5350)
    val muted = Color(0xFF8A9099)
}

/** Always dark: the app is used at night and in dim train carriages. */
@Composable
fun GpsCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.Black,
            background = Color(0xFF101215),
            surface = Color(0xFF101215),
            surfaceContainer = Color(0xFF1A1D22),
            surfaceContainerHigh = Color(0xFF22262C),
        ),
        content = content,
    )
}

/** The few Material icons missing from material-icons-core (path data from Material Icons, Apache 2.0). */
object AppIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

    val Speed = icon(
        "Speed",
        "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1a10,10 0,0 0,-0.27 -10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z",
    )
    val GpsFixed = icon(
        "GpsFixed",
        "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11A8.994,8.994 0,0 0,13 3.06L13,1h-2v2.06A8.994,8.994 0,0 0,3.06 11L1,11v2h2.06A8.994,8.994 0,0 0,11 20.94L11,23h2v-2.06A8.994,8.994 0,0 0,20.94 13L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
    val History = icon(
        "History",
        "M13,3a9,9 0,0 0,-9 9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42A8.954,8.954 0,0 0,13 21a9,9 0,0 0,0 -18zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
    )
    val Videocam = icon(
        "Videocam",
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z",
    )
}
