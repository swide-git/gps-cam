package io.github.swidegit.gpscam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Acceleration results on the device (newest first, at most [MAX]). */
object PerformanceStore {
    private const val MAX = 50
    private var prefs: SharedPreferences? = null
    private val _results = MutableStateFlow<List<RunResult>>(emptyList())
    val results: StateFlow<List<RunResult>> = _results

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("performance", Context.MODE_PRIVATE)
        prefs = p
        _results.value = runCatching {
            val arr = JSONArray(p.getString("results", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RunResult(
                    o.getString("spec"), o.getString("label"), o.getLong("time"),
                    if (o.has("trap")) o.getDouble("trap") else null, o.getLong("utc"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(results: List<RunResult>) {
        if (results.isEmpty()) return
        _results.value = (results + _results.value).take(MAX)
        save()
    }

    /** Removes one result (the first equal one: same run, time and moment). */
    @Synchronized
    fun delete(result: RunResult) {
        val i = _results.value.indexOf(result)
        if (i < 0) return
        _results.value = _results.value.toMutableList().apply { removeAt(i) }
        save()
    }

    @Synchronized
    fun clear() {
        _results.value = emptyList()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _results.value.forEach { r ->
            val o = JSONObject().put("spec", r.specId).put("label", r.label).put("time", r.timeMs).put("utc", r.utcMs)
            r.trapSpeedMs?.let { o.put("trap", it) }
            arr.put(o)
        }
        prefs?.edit()?.putString("results", arr.toString())?.apply()
    }
}
