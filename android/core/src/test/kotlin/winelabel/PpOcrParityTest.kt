package winelabel

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import winelabel.model.RgbImage
import winelabel.ocr.PpOcrEngine
import winelabel.parse.SequenceMatcher
import winelabel.parse.Text
import java.io.File
import javax.imageio.ImageIO

/**
 * The Kotlin OCR pipeline on the 8 real photos in python/fixtures/images:
 * same accuracy floors as tests/test_images.py, and OCR text close to the Python
 * pipeline's output (golden JSON from `winelabel scan --json --no-enhance`).
 *
 * `-Pphotos=<folder>` runs it on another photo folder under fixtures/ (e.g. the local-only
 * vinmonopolet set): scores are printed, but floors and golden parity apply only to `images`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PpOcrParityTest {
    private val engine by lazy {
        val m = Fixtures.modelsDir
        PpOcrEngine(
            m.resolve("PP-OCRv6_det_small.onnx").readBytes(),
            m.resolve("ch_ppocr_mobile_v2.0_cls_mobile.onnx").readBytes(),
            m.resolve("PP-OCRv6_rec_small.onnx").readBytes(),
            m.resolve("ppocrv6_keys.txt").readText(),
        )
    }

    private val photos = System.getProperty("winelabel.photos") ?: "images"
    private val isDefaultSet = photos == "images"

    private val results by lazy {
        val dir = Fixtures.root.resolve(photos)
        val expected = Fixtures.readJson(dir.resolve("expected.json")).jsonObject
        expected.keys.associateWith { name -> Scanner.scan(load(dir.resolve(name)), engine) }
            .also { dump(it) }
    }

    /** -Pdump=<dir>: write every OCR line (text, confidence, box) per photo, to diff pipeline changes. */
    private fun dump(results: Map<String, ScanResult>) {
        val out = System.getProperty("winelabel.dump")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return
        out.mkdirs()
        for ((name, r) in results) {
            out.resolve("$name.txt").writeText(r.lines.joinToString("\n") { l ->
                "${l.text}\t${l.confidence}\t" + l.box.joinToString(" ") { "${it.x},${it.y}" }
            } + "\n" + r.label.values() + "\n")
        }
    }

    private fun load(f: File): RgbImage {
        val bi = ImageIO.read(f)
        return RgbImage(bi.width, bi.height, bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width))
    }

    @AfterAll
    fun close() = engine.close()

    @Test
    fun accuracyFloors() {
        val dir = Fixtures.root.resolve(photos)
        val expected = Fixtures.readJson(dir.resolve("expected.json")).jsonObject
        var fieldsOk = 0; var fieldsN = 0; var textOk = 0; var textN = 0
        for ((name, exp) in expected) {
            val r = results.getValue(name)
            val actual = r.label.values()
            val text = r.lines.joinToString(" ") { Text.normalize(it.text) }
            val squashed = text.replace(" ", "")
            println("## $name (${r.millis} ms)")
            for ((field, e) in exp.jsonObject) {
                if (field.startsWith("_")) continue
                val ok = Fixtures.valuesEqual(Fixtures.toKotlin(e), actual[field])
                fieldsOk += if (ok) 1 else 0; fieldsN++
                if (!ok) println("  BAD $field expected=${Fixtures.toKotlin(e)} got=${actual[field]}")
            }
            for (s in exp.jsonObject["_text"]?.jsonArray ?: emptyList()) {
                val snippet = s.jsonPrimitive.content
                val found = snippet in text || snippet.replace(" ", "") in squashed
                textOk += if (found) 1 else 0; textN++
            }
        }
        println("FIELDS $fieldsOk/$fieldsN  TEXT $textOk/$textN  TIME ${results.values.sumOf { it.millis }} ms")
        if (!isDefaultSet) return
        assertTrue(fieldsOk.toDouble() / fieldsN >= 0.78, "field accuracy $fieldsOk/$fieldsN")
        // Python without CLAHE (which the Kotlin pipeline skips) also finds 38/59.
        assertTrue(textOk.toDouble() / textN >= 0.62, "text recall $textOk/$textN")
    }

    @Test
    fun matchesPythonOcr() {
        assumeTrue(isDefaultSet, "Python goldens exist only for fixtures/images")
        val golden = File(javaClass.getResource("/golden")!!.toURI())
        val sims = mutableListOf<Double>()
        for ((name, r) in results) {
            val g = Fixtures.readJson(golden.resolve(name.substringBeforeLast('.') + ".json")).jsonObject
            val pyText = g["lines"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
            val ktText = r.lines.joinToString("\n") { it.text }
            val sim = SequenceMatcher.ratio(pyText, ktText)
            sims.add(sim)
            println("$name similarity=${"%.3f".format(sim)}")
            if (sim < 0.9) println("  PY: ${pyText.replace("\n", " | ")}\n  KT: ${ktText.replace("\n", " | ")}")
        }
        val mean = sims.average()
        println("MEAN similarity ${"%.3f".format(mean)}")
        assertTrue(mean >= 0.9, "mean OCR similarity to Python $mean")
    }
}
