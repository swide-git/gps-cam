package io.github.swidegit.gpscam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val unit: SpeedUnit = SpeedUnit.KMH,
    /** Keep tracking (and the trip statistics) when the app is in the background or the screen is off. */
    val backgroundTracking: Boolean = true,
    val keepScreenOn: Boolean = true,
    val recordAudio: Boolean = true,
    val overlayCoordinates: Boolean = true,
    val overlaySatellites: Boolean = true,
)

object AppSettings {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        val d = Settings()
        _state.value = Settings(
            unit = SpeedUnit.entries.firstOrNull { it.name == p.getString("unit", null) } ?: d.unit,
            backgroundTracking = p.getBoolean("backgroundTracking", d.backgroundTracking),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
            recordAudio = p.getBoolean("recordAudio", d.recordAudio),
            overlayCoordinates = p.getBoolean("overlayCoordinates", d.overlayCoordinates),
            overlaySatellites = p.getBoolean("overlaySatellites", d.overlaySatellites),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        _state.value = s
        prefs?.edit()
            ?.putString("unit", s.unit.name)
            ?.putBoolean("backgroundTracking", s.backgroundTracking)
            ?.putBoolean("keepScreenOn", s.keepScreenOn)
            ?.putBoolean("recordAudio", s.recordAudio)
            ?.putBoolean("overlayCoordinates", s.overlayCoordinates)
            ?.putBoolean("overlaySatellites", s.overlaySatellites)
            ?.apply()
    }
}
