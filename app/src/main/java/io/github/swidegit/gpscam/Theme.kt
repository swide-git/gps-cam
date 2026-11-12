package io.github.swidegit.gpscam

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * GPS Cam colours, the same scheme as Second Display: the UI follows the wallpaper (Material You)
 * on Android 12+, with these orange schemes as the fallback; light or dark with the system.
 * The launcher icon uses the same tones (peach background, orange arc, brown needle).
 */
object Palette {
    /** Status badges and signal bars: fixed, readable with black text in both themes. */
    val badgeGood = Color(0xFF66BB6A)
    val badgeWarn = Color(0xFFFFCA28)
    val badgeBad = Color(0xFFEF5350)
    /** Drawn on top of the camera picture, so independent of the theme. */
    val record = Color(0xFFE53935)
    val overlayAccent = Color(0xFFFF9F43)
    val overlayWarn = Color(0xFFFFCA28)
    val overlayMuted = Color(0xFFA1A1AA)

    val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val bad: Color @Composable get() = MaterialTheme.colorScheme.error
    val warn: Color @Composable get() = if (isDark()) Color(0xFFFFCA28) else Color(0xFF8A6100)
    val good: Color @Composable get() = if (isDark()) Color(0xFF81C784) else Color(0xFF2E7D32)

    @Composable
    private fun isDark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val LightScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF904D00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCC2),
        onPrimaryContainer = Color(0xFF2F1500),
        secondary = Color(0xFF745846),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCC2),
        onSecondaryContainer = Color(0xFF2B1709),
        tertiary = Color(0xFF5E6135),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3E6AF),
        onTertiaryContainer = Color(0xFF1B1D00),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF8F5),
        onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF4DED3),
        onSurfaceVariant = Color(0xFF52443B),
        outline = Color(0xFF85746A),
        outlineVariant = Color(0xFFD7C3B7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1EA),
        surfaceContainer = Color(0xFFFCEBE2),
        surfaceContainerHigh = Color(0xFFF6E5DC),
        surfaceContainerHighest = Color(0xFFF0DFD7)
    )

    val DarkScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFFFFB77C),
        onPrimary = Color(0xFF4E2600),
        primaryContainer = Color(0xFF6F3900),
        onPrimaryContainer = Color(0xFFFFDCC2),
        secondary = Color(0xFFE3BFA8),
        onSecondary = Color(0xFF422B1B),
        secondaryContainer = Color(0xFF5B4130),
        onSecondaryContainer = Color(0xFFFFDCC2),
        tertiary = Color(0xFFC7CA95),
        onTertiary = Color(0xFF30330B),
        tertiaryContainer = Color(0xFF464A20),
        onTertiaryContainer = Color(0xFFE3E6AF),
        background = Color(0xFF19120D),
        onBackground = Color(0xFFF0DFD6),
        surface = Color(0xFF19120D),
        onSurface = Color(0xFFF0DFD6),
        surfaceVariant = Color(0xFF52443B),
        onSurfaceVariant = Color(0xFFD7C3B7),
        outline = Color(0xFF9F8D83),
        outlineVariant = Color(0xFF52443B),
        surfaceContainerLowest = Color(0xFF130D08),
        surfaceContainerLow = Color(0xFF221A15),
        surfaceContainer = Color(0xFF261E19),
        surfaceContainerHigh = Color(0xFF312823),
        surfaceContainerHighest = Color(0xFF3C332D)
    )
}

/** Material You: wallpaper colours on Android 12+, the orange scheme before that; light or dark with the system. */
@Composable
fun GpsCamTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Palette.DarkScheme
        else -> Palette.LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
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
    val PhotoCamera = icon(
        "PhotoCamera",
        "M9,2L7.17,4L4,4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2L9,2zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,15.2a3.2,3.2 0,1 0,0 -6.4a3.2,3.2 0,1 0,0 6.4z",
    )
    val Videocam = icon(
        "Videocam",
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z",
    )
}
