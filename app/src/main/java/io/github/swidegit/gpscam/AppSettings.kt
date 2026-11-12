package io.github.swidegit.gpscam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Recording resolution; all are 16:9, which the overlay layout relies on. */
enum class VideoQuality(val label: String) { HD("720p"), FHD("1080p"), UHD("4K") }

enum class OverlayCorner(val label: String) { BOTTOM_LEFT("↙"), BOTTOM_RIGHT("↘"), TOP_LEFT("↖"), TOP_RIGHT("↗") }

enum class OverlaySize(val label: String, val scale: Float) { SMALL("S", 0.75f), MEDIUM("M", 1f), LARGE("L", 1.3f) }

data class Settings(
    val unit: SpeedUnit = SpeedUnit.KMH,
    val keepScreenOn: Boolean = true,
    val recordAudio: Boolean = true,
    val overlayCoordinates: Boolean = true,
    val overlaySatellites: Boolean = true,
    val overlayAltitude: Boolean = true,
    val overlayHeading: Boolean = false,
    val overlayDistance: Boolean = false,
    /** Speed of the last minute as a small line chart in the overlay. */
    val overlayChart: Boolean = true,
    /** Volume up starts and stops recording, volume down takes a photo, on the Camera tab. */
    val volumeKeys: Boolean = true,
    val overlayCorner: OverlayCorner = OverlayCorner.BOTTOM_LEFT,
    val overlaySize: OverlaySize = OverlaySize.MEDIUM,
    /** Opacity of the box behind the overlay text, 0-100. */
    val overlayOpacity: Int = 60,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    /** 30 or 60; falls back to 30 when the camera cannot do 60 at the chosen quality. */
    val frameRate: Int = 30,
    /** Focus fixed at infinity: autofocus on a train window locks onto dirt and reflections. */
    val landscapeFocus: Boolean = false,
)

object AppSettings {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == getString(key, null) } ?: default

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        val d = Settings()
        _state.value = Settings(
            unit = p.enum("unit", d.unit),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
            recordAudio = p.getBoolean("recordAudio", d.recordAudio),
            overlayCoordinates = p.getBoolean("overlayCoordinates", d.overlayCoordinates),
            overlaySatellites = p.getBoolean("overlaySatellites", d.overlaySatellites),
            overlayAltitude = p.getBoolean("overlayAltitude", d.overlayAltitude),
            overlayHeading = p.getBoolean("overlayHeading", d.overlayHeading),
            overlayDistance = p.getBoolean("overlayDistance", d.overlayDistance),
            overlayChart = p.getBoolean("overlayChart", d.overlayChart),
            volumeKeys = p.getBoolean("volumeKeys", d.volumeKeys),
            overlayCorner = p.enum("overlayCorner", d.overlayCorner),
            overlaySize = p.enum("overlaySize", d.overlaySize),
            overlayOpacity = p.getInt("overlayOpacity", d.overlayOpacity),
            videoQuality = p.enum("videoQuality", d.videoQuality),
            frameRate = p.getInt("frameRate", d.frameRate),
            landscapeFocus = p.getBoolean("landscapeFocus", d.landscapeFocus),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        _state.value = s
        prefs?.edit()
            ?.putString("unit", s.unit.name)
            ?.putBoolean("keepScreenOn", s.keepScreenOn)
            ?.putBoolean("recordAudio", s.recordAudio)
            ?.putBoolean("overlayCoordinates", s.overlayCoordinates)
            ?.putBoolean("overlaySatellites", s.overlaySatellites)
            ?.putBoolean("overlayAltitude", s.overlayAltitude)
            ?.putBoolean("overlayHeading", s.overlayHeading)
            ?.putBoolean("overlayDistance", s.overlayDistance)
            ?.putBoolean("overlayChart", s.overlayChart)
            ?.putBoolean("volumeKeys", s.volumeKeys)
            ?.putString("overlayCorner", s.overlayCorner.name)
            ?.putString("overlaySize", s.overlaySize.name)
            ?.putInt("overlayOpacity", s.overlayOpacity)
            ?.putString("videoQuality", s.videoQuality.name)
            ?.putInt("frameRate", s.frameRate)
            ?.putBoolean("landscapeFocus", s.landscapeFocus)
            ?.apply()
    }
}
