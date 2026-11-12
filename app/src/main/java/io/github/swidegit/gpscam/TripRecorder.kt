package io.github.swidegit.gpscam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TripState(
    /** True while a trip is being recorded (started by the user, or automatically when enabled). */
    val active: Boolean = false,
    val trip: TripSnapshot = TripSnapshot(),
    /** Id of the trip in progress (its start time), or null before the first good fix. */
    val tripId: Long? = null,
)

/**
 * The trip in progress: statistics, the persisted track and the last hour of points. The point
 * lists are published as copies, but only every few seconds: copying a long track on every fix
 * was the single biggest cost after hours of driving, and the charts do not need it faster.
 */
object TripRecorder {
    private const val TRACK_MAX_ACCURACY_M = 50.0
    private const val RECENT_MS = 60 * 60_000L
    private const val TRACK_PUBLISH_MS = 5_000L
    private const val RECENT_PUBLISH_MS = 2_000L

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state

    /** Points of the current trip (the same points go to [TripStore]); empty while no trip runs. */
    private val _track = MutableStateFlow<List<TrackPoint>>(emptyList())
    val track: StateFlow<List<TrackPoint>> = _track

    /** The last hour of usable points, in memory only: speed charts without a trip. */
    private val _recent = MutableStateFlow<List<TrackPoint>>(emptyList())
    val recent: StateFlow<List<TrackPoint>> = _recent

    private val lock = Any()
    private val trip = TripStats()
    private var tripId: Long? = null
    private var active = false
    private val trackPoints = ArrayList<TrackPoint>()
    private val recentPoints = ArrayList<TrackPoint>()
    private var trackPublishedAt = 0L
    private var recentPublishedAt = 0L

    fun init(context: Context) {
        TripStore.init(context)
        synchronized(lock) {
            if (AppSettings.state.value.autoTrip) active = true
            _state.value = TripState(active, trip.current, tripId)
        }
    }

    /** Starts recording a trip; it is saved to the history by [endTrip]. */
    fun startTrip() {
        synchronized(lock) {
            trip.reset()
            trackPoints.clear()
            tripId = null
            active = true
            publishAll()
        }
        SignalStats.clearQuality()
    }

    /**
     * Saves the current trip to the history. With automatic recording a new trip starts at once;
     * otherwise nothing is recorded until the next [startTrip].
     */
    fun endTrip() {
        val auto = AppSettings.state.value.autoTrip
        synchronized(lock) {
            tripId?.let { TripStore.endTrip(it, trackPoints.toList(), trip.current) }
            trip.reset()
            trackPoints.clear()
            tripId = null
            active = auto
            publishAll()
        }
        SignalStats.clearQuality()
    }

    /** Feeds one fix; [point] carries the derived speed. Returns whether a trip is active. */
    fun onFix(fix: Fix, point: TrackPoint): Boolean = synchronized(lock) {
        if (!active && AppSettings.state.value.autoTrip) active = true
        val snapshot = if (active) trip.add(fix) else TripSnapshot()
        val usable = (fix.accuracyM ?: Double.MAX_VALUE) <= TRACK_MAX_ACCURACY_M
        val now = fix.elapsedMs
        val lastRecent = recentPoints.lastOrNull()
        if (usable && (lastRecent == null || now - lastRecent.elapsedMs >= 1000)) {
            recentPoints += point
            while (recentPoints.isNotEmpty() && point.utcMs - recentPoints.first().utcMs > RECENT_MS) recentPoints.removeAt(0)
            if (now - recentPublishedAt >= RECENT_PUBLISH_MS) {
                _recent.value = recentPoints.toList()
                recentPublishedAt = now
            }
        }
        val last = trackPoints.lastOrNull()
        if (active && usable && (last == null || now - last.elapsedMs >= 1000)) {
            val id = tripId ?: point.utcMs.also { tripId = it }
            trackPoints += point
            TripStore.append(id, point)
            if (now - trackPublishedAt >= TRACK_PUBLISH_MS) {
                _track.value = trackPoints.toList()
                trackPublishedAt = now
            }
        }
        _state.value = TripState(active, snapshot, tripId)
        active
    }

    private fun publishAll() {
        _state.value = TripState(active, trip.current, tripId)
        _track.value = trackPoints.toList()
        _recent.value = recentPoints.toList()
        trackPublishedAt = 0L
        recentPublishedAt = 0L
    }
}
