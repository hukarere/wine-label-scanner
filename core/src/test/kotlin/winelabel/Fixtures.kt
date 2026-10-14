package winelabel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import winelabel.cli.Evaluate
import winelabel.model.OcrLine
import java.io.File

/** Access to the test fixtures in the repository's top-level fixtures/ folder. */
object Fixtures {
    val root = File(System.getProperty("winelabel.fixtures") ?: "../fixtures")
    val modelsDir = File(System.getProperty("winelabel.models") ?: "../app/src/main/assets/models")

    fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())

    /** Fixture rows [text, height(, confidence)] stacked top to bottom (the JSON cases in fixtures/text). */
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
    fun toKotlin(e: JsonElement): Any? = Evaluate.toKotlin(e)

    fun valuesEqual(expected: Any?, actual: Any?): Boolean = Evaluate.valuesEqual(expected, actual)
}
