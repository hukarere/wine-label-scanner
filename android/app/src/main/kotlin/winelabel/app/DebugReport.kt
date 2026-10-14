package winelabel.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import winelabel.ScanResult

/** JSON the user can paste back to the developer: everything needed to turn a bad scan into a test case. */
object DebugReport {
    fun build(result: ScanResult, info: ScanInfo, appVersion: String): String {
        val fields = JSONObject()
        for ((name, f) in result.label.fields()) {
            fields.put(name, if (f == null) JSONObject.NULL else JSONObject().apply {
                put("value", (f.value as? List<*>)?.let { JSONArray(it) } ?: f.value)
                put("confidence", round(f.confidence, 2))
                put("source", f.source)
            })
        }
        val lines = JSONArray()
        for (l in result.lines) {
            lines.put(JSONObject().apply {
                put("text", l.text)
                put("confidence", round(l.confidence, 3))
                put("height", round(l.height, 1))
                put("box", JSONArray(l.box.map { JSONArray(listOf(it.x.toInt(), it.y.toInt())) }))
            })
        }
        return JSONObject().apply {
            put("app", appVersion)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            put("engine", result.engine)
            put("image", "${result.image.width}x${result.image.height}")
            put("ms_total", result.millis)
            put("ms_stages", JSONObject(info.timings))
            info.detInput?.let { d ->
                put("det_input", "${d.width}x${d.height}")
                put("det_capped", d.capped)
                put("det_max_pixels", info.detMaxPixels)
                put("avail_mem_mb", info.availMemMb)
            }
            put("raw_text", result.rawText)
            put("fields", fields)
            put("lines", lines)
        }.toString(2)
    }

    /** Not via "%.2f".format(): that uses the device locale ("0,58" in ru/de/fr) and toDouble() then throws. */
    private fun round(x: Double, decimals: Int): Double {
        val f = Math.pow(10.0, decimals.toDouble())
        return Math.rint(x * f) / f
    }
}
