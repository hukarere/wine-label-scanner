package winelabel.cli

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
import winelabel.ScanResult
import winelabel.Scanner
import winelabel.ocr.OcrEngine
import winelabel.parse.Text
import java.io.File

/**
 * Accuracy on a folder of labelled photos (`expected.json` next to the images): field accuracy
 * and text-snippet recall. Used by `cli eval`, PpOcrParityTest and (same rules) DeviceScanTest.
 *
 * expected.json: `{ "photo.jpg": { "<field>": value, ..., "_text": ["snippet", ...] } }`.
 * Only the fields listed are scored; snippets are matched in the normalized OCR text.
 */
object Evaluate {
    data class FieldCheck(val expected: Any?, val actual: Any?, val ok: Boolean)

    data class ImageReport(
        val name: String,
        val result: ScanResult,
        val fields: Map<String, FieldCheck>,
        val missingText: List<String>,
        val textTotal: Int,
    )

    data class Report(val images: List<ImageReport>) {
        val fieldsOk get() = images.sumOf { im -> im.fields.values.count { it.ok } }
        val fieldsTotal get() = images.sumOf { it.fields.size }
        val textOk get() = images.sumOf { it.textTotal - it.missingText.size }
        val textTotal get() = images.sumOf { it.textTotal }
        val millis get() = images.sumOf { it.result.millis }

        fun perField(): Map<String, Pair<Int, Int>> {
            val out = linkedMapOf<String, Pair<Int, Int>>()
            for (im in images) for ((name, c) in im.fields) {
                val (ok, n) = out[name] ?: (0 to 0)
                out[name] = (ok + if (c.ok) 1 else 0) to n + 1
            }
            return out
        }
    }

    fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())

    fun expected(dir: File): Map<String, JsonObject> =
        readJson(dir.resolve("expected.json")).jsonObject.mapValues { it.value.jsonObject }

    fun run(dir: File, engine: OcrEngine, only: Set<String>? = null): Report {
        val images = expected(dir).filterKeys { only == null || it in only }.map { (name, exp) ->
            check(name, exp, Scanner.scan(Desktop.loadImage(dir.resolve(name)), engine))
        }
        return Report(images)
    }

    fun check(name: String, exp: JsonObject, result: ScanResult): ImageReport {
        val actual = result.label.values()
        val fields = exp.filterKeys { !it.startsWith("_") }.mapValues { (field, e) ->
            val want = toKotlin(e)
            FieldCheck(want, actual[field], valuesEqual(want, actual[field]))
        }
        val text = result.lines.joinToString(" ") { Text.normalize(it.text) }
        val squashed = text.replace(" ", "")
        val snippets = exp["_text"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        // A snippet counts as found even if OCR merged or split the words differently.
        val missing = snippets.filter { it !in text && it.replace(" ", "") !in squashed }
        return ImageReport(name, result, fields, missing, snippets.size)
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

    /** Lists compare as sets, numbers within 0.05, strings after normalization (case, accents). */
    fun valuesEqual(expected: Any?, actual: Any?): Boolean = when {
        expected is List<*> -> actual is List<*> &&
            expected.map { it.toString() }.sorted() == actual.map { it.toString() }.sorted()
        expected is Double || actual is Double ->
            expected != null && actual != null &&
                kotlin.math.abs((expected as Number).toDouble() - (actual as Number).toDouble()) < 0.05
        expected is String && actual is String -> Text.normalize(expected) == Text.normalize(actual)
        else -> expected == actual
    }

    /** Every OCR line (text, confidence, box) and the fields per photo, to diff pipeline changes. */
    fun dump(report: Report, out: File) {
        out.mkdirs()
        for (im in report.images) {
            val r = im.result
            out.resolve("${im.name}.txt").writeText(r.lines.joinToString("\n") { l ->
                "${l.text}\t${l.confidence}\t" + l.box.joinToString(" ") { "${it.x},${it.y}" }
            } + "\n" + r.label.values() + "\n")
        }
    }

    fun print(report: Report) {
        for (im in report.images) {
            println("\n## ${im.name} (${im.result.millis} ms)")
            for ((name, c) in im.fields) {
                println("  ${if (c.ok) "ok " else "BAD"} ${name.padEnd(15)} expected=${c.expected} got=${c.actual}")
            }
            if (im.missingText.isNotEmpty()) {
                println("  text missed ${im.missingText.size}/${im.textTotal}: ${im.missingText}")
            }
        }
        println("\n=== Field accuracy ===")
        for ((name, s) in report.perField()) println("  ${name.padEnd(15)} ${s.first}/${s.second}")
        println("  ${"ALL FIELDS".padEnd(15)} ${report.fieldsOk}/${report.fieldsTotal}")
        println("  ${"TEXT RECALL".padEnd(15)} ${report.textOk}/${report.textTotal}")
        println("  ${"TIME".padEnd(15)} ${"%.1f".format(java.util.Locale.ROOT, report.millis / 1000.0)} s")
    }
}
