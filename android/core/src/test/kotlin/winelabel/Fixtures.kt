package winelabel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import winelabel.model.OcrLine
import winelabel.parse.Text
import java.io.File

/** Access to the fixtures shared with the Python prototype (../python/fixtures). */
object Fixtures {
    val root = File(System.getProperty("winelabel.fixtures") ?: "../../python/fixtures")
    val modelsDir = File(System.getProperty("winelabel.models") ?: "../app/src/main/assets/models")

    fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())

    /** Fixture rows [text, height(, confidence)] stacked top to bottom — same as tests/conftest.py. */
    fun linesFromRows(rows: JsonArray): List<OcrLine> {
        var y = 0.0
        return rows.map { row ->
            val r = row.jsonArray
            val text = r[0].jsonPrimitive.content
            val h = r[1].jsonPrimitive.content.toDouble()
            val conf = if (r.size > 2) r[2].jsonPrimitive.content.toDouble() else 0.98
            val w = maxOf(text.length, 1) * h * 0.55
            OcrLine.rect(text, conf, 0.0, y, w, h).also { y += h * 1.6 }
        }
    }

    /** JSON value -> Kotlin value comparable with WineLabel.values(). */
    fun toKotlin(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.map { toKotlin(it) }
        is JsonObject -> e.mapValues { toKotlin(it.value) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.content.contains('.') -> e.doubleOrNull
            else -> e.longOrNull?.toInt()
        }
    }

    /** Port of winelabel/evaluate.py values_equal. */
    fun valuesEqual(expected: Any?, actual: Any?): Boolean = when {
        expected is List<*> -> actual is List<*> &&
            expected.map { it.toString() }.sorted() == actual.map { it.toString() }.sorted()
        expected is Double || actual is Double ->
            expected != null && actual != null &&
                kotlin.math.abs((expected as Number).toDouble() - (actual as Number).toDouble()) < 0.05
        expected is String && actual is String -> Text.normalize(expected) == Text.normalize(actual)
        else -> expected == actual
    }

    fun obj(e: JsonElement): JsonObject = e.jsonObject
}
