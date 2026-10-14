package winelabel.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import winelabel.parse.Text
import java.io.File

/**
 * Both engines on the 8 photo fixtures (python/fixtures/images, packaged as test assets),
 * through the app's own code: AndroidImages.load (BitmapFactory + EXIF), Engines (models from
 * the APK assets, onnxruntime-android / ML Kit), Scanner, DebugReport. Catches what the JVM
 * tests can't: ICU regexes, native ONNX Runtime, asset loading, bitmap decoding.
 *
 * Each photo's debug report is written to the app's external files dir:
 *   adb pull /sdcard/Android/data/winelabel.app/files/device-test
 * Optional instrumentation args: -e images a.jpg,b.jpg  -e engines ONNX,MLKIT
 *   -e detMaxPixels N   (force the PP-OCR detector pixel cap instead of the memory budget)
 */
@RunWith(AndroidJUnit4::class)
class DeviceScanTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private val args = InstrumentationRegistry.getArguments()
    private val outDir = File(app.getExternalFilesDir(null), "device-test").apply { mkdirs() }

    private val expected = JSONObject(testAssets.open("expected.json").use { it.readBytes().toString(Charsets.UTF_8) })
    private val images = args.getString("images")?.split(",")?.map { it.trim() }
        ?: expected.keys().asSequence().toList()

    private val forceDetMaxPixels = args.getString("detMaxPixels")?.toInt()

    /** Which fixture folder the APK was built with (-Pphotos); floors are calibrated for "images". */
    private val photoSet = testAssets.open("photoset.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private fun wanted(kind: EngineKind) =
        args.getString("engines")?.split(",")?.map { it.trim().uppercase() }?.contains(kind.name) ?: true

    @Test
    fun onnx() = run(EngineKind.ONNX, fieldFloor = 0.78, textFloor = 0.62)

    /** ML Kit has no floors yet: this records its scores so the two engines can be compared. */
    @Test
    fun mlkit() = run(EngineKind.MLKIT, fieldFloor = 0.0, textFloor = 0.0)

    private fun run(kind: EngineKind, fieldFloor: Double, textFloor: Double) {
        if (!wanted(kind)) return
        val engines = Engines(app)
        val dir = File(outDir, kind.name.lowercase()).apply { mkdirs() }
        var fieldsOk = 0; var fieldsN = 0; var textOk = 0; var textN = 0; var millis = 0L
        val summary = StringBuilder()
        try {
            val engine = engines.get(kind)
            for (name in images) {
                val exp = expected.getJSONObject(name)
                // Through a file Uri, like a picked photo goes through a content Uri.
                val file = File(app.cacheDir, name)
                testAssets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
                val (result, info) = AppScan.scan(app, AndroidImages.load(app, Uri.fromFile(file)), engine, forceDetMaxPixels)
                millis += result.millis
                File(dir, name.substringBeforeLast('.') + ".json")
                    .writeText(DebugReport.build(result, info, "device-test"))

                val actual = result.label.values()
                val det = info.detInput?.let { " det ${it.width}x${it.height}${if (it.capped) " CAPPED" else ""}, free ${info.availMemMb} MB" } ?: ""
                summary.append("## $name (${result.millis} ms, ${result.lines.size} lines$det)\n")
                for (field in exp.keys()) {
                    if (field.startsWith("_")) continue
                    val e = toKotlin(exp.get(field))
                    val ok = valuesEqual(e, actual[field])
                    fieldsOk += if (ok) 1 else 0; fieldsN++
                    if (!ok) summary.append("  BAD $field expected=$e got=${actual[field]}\n")
                }
                val text = result.lines.joinToString(" ") { Text.normalize(it.text) }
                val squashed = text.replace(" ", "")
                val snippets = exp.optJSONArray("_text") ?: JSONArray()
                for (i in 0 until snippets.length()) {
                    val s = snippets.getString(i)
                    val found = s in text || s.replace(" ", "") in squashed
                    textOk += if (found) 1 else 0; textN++
                    if (!found) summary.append("  MISSING \"$s\"\n")
                }
                assertTrue("$name: ${kind.label} read no text", result.lines.isNotEmpty())
            }
        } finally {
            engines.closeAll()
        }
        summary.append("${kind.name} FIELDS $fieldsOk/$fieldsN  TEXT $textOk/$textN  TIME $millis ms\n")
        File(dir, "summary.txt").writeText(summary.toString())
        summary.lines().forEach { Log.i(TAG, it) }
        if (photoSet != "images") return
        assertTrue("${kind.label} field accuracy $fieldsOk/$fieldsN", fieldsOk >= fieldFloor * fieldsN)
        assertTrue("${kind.label} text recall $textOk/$textN", textOk >= textFloor * textN)
    }

    /** JSON value -> value comparable with WineLabel.values() (same as core's test Fixtures.toKotlin). */
    private fun toKotlin(e: Any?): Any? = when (e) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until e.length()).map { toKotlin(e.get(it)) }
        is Int -> e
        is Long -> e.toInt()
        is Number -> e.toDouble()
        else -> e
    }

    /** Port of winelabel/evaluate.py values_equal. */
    private fun valuesEqual(expected: Any?, actual: Any?): Boolean = when {
        expected is List<*> -> actual is List<*> &&
            expected.map { it.toString() }.sorted() == actual.map { it.toString() }.sorted()
        expected is Double || actual is Double ->
            expected != null && actual != null &&
                kotlin.math.abs((expected as Number).toDouble() - (actual as Number).toDouble()) < 0.05
        expected is String && actual is String -> Text.normalize(expected) == Text.normalize(actual)
        else -> expected == actual
    }

    companion object {
        const val TAG = "WineLabelTest"
    }
}
