package io.github.swidegit.gpscam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TtffRecord(val utcMs: Long, val ttffMs: Int)

/** Time-to-first-fix history on the device (newest first, at most [MAX]). */
object TtffStore {
    private const val MAX = 30
    private var prefs: SharedPreferences? = null
    private val _records = MutableStateFlow<List<TtffRecord>>(emptyList())
    val records: StateFlow<List<TtffRecord>> = _records

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ttff", Context.MODE_PRIVATE)
        prefs = p
        _records.value = runCatching {
            val arr = JSONArray(p.getString("records", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> TtffRecord(o.getLong("utc"), o.getInt("ttff")) } }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(record: TtffRecord) {
        _records.value = (listOf(record) + _records.value).take(MAX)
        val arr = JSONArray()
        _records.value.forEach { arr.put(JSONObject().put("utc", it.utcMs).put("ttff", it.ttffMs)) }
        prefs?.edit()?.putString("records", arr.toString())?.apply()
    }
}
