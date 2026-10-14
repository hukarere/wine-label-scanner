package winelabel.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import winelabel.ScanResult

/** JSON the user can paste back to the developer: everything needed to turn a bad scan into a test case. */
object DebugReport {
    fun build(result: ScanResult, timings: Map<String, Long>, appVersion: String): String {
        val fields = JSONObject()
        for ((name, f) in result.label.fields()) {
            fields.put(name, if (f == null) JSONObject.NULL else JSONObject().apply {
                put("value", (f.value as? List<*>)?.let { JSONArray(it) } ?: f.value)
                put("confidence", "%.2f".format(f.confidence).toDouble())
                put("source", f.source)
            })
        }
        val lines = JSONArray()
        for (l in result.lines) {
            lines.put(JSONObject().apply {
                put("text", l.text)
                put("confidence", "%.3f".format(l.confidence).toDouble())
                put("height", "%.1f".format(l.height).toDouble())
                put("box", JSONArray(l.box.map { JSONArray(listOf(it.x.toInt(), it.y.toInt())) }))
            })
        }
        return JSONObject().apply {
            put("app", appVersion)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            put("engine", result.engine)
            put("image", "${result.image.width}x${result.image.height}")
            put("ms_total", result.millis)
            put("ms_stages", JSONObject(timings))
            put("raw_text", result.rawText)
            put("fields", fields)
            put("lines", lines)
        }.toString(2)
    }
}
