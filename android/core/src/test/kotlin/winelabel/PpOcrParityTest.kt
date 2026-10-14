package winelabel

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import winelabel.cli.Desktop
import winelabel.cli.Evaluate
import winelabel.parse.SequenceMatcher
import java.io.File

/**
 * The OCR pipeline on the real photos in fixtures/images: accuracy floors, and OCR text close
 * to the frozen reference output in resources/golden (from the retired Python prototype, which
 * ran the same models through rapidocr; see docs/PROJECT_NOTES.md).
 *
 * `-Pphotos=<folder>` runs it on another photo folder under fixtures/ (e.g. the local-only
 * vinmonopolet set): scores are printed, but floors and golden checks apply only to `images`.
 * `-Pdump=<dir>` writes every OCR line per photo, to diff the output of pipeline changes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PpOcrParityTest {
    private val engine by lazy { Desktop.engine(Fixtures.modelsDir) }
    private val photos = System.getProperty("winelabel.photos") ?: "images"
    private val isDefaultSet = photos == "images"

    private val report by lazy {
        Evaluate.run(Fixtures.root.resolve(photos), engine).also { r ->
            System.getProperty("winelabel.dump")?.takeIf { it.isNotEmpty() }?.let { Evaluate.dump(r, File(it)) }
        }
    }

    @AfterAll
    fun close() = engine.close()

    @Test
    fun accuracyFloors() {
        Evaluate.print(report)
        val r = report
        println("FIELDS ${r.fieldsOk}/${r.fieldsTotal}  TEXT ${r.textOk}/${r.textTotal}  TIME ${r.millis} ms")
        if (!isDefaultSet) return
        // Floors just under the current scores (31/37, 38/59); raise them as accuracy improves.
        assertTrue(r.fieldsOk.toDouble() / r.fieldsTotal >= 0.78, "field accuracy ${r.fieldsOk}/${r.fieldsTotal}")
        assertTrue(r.textOk.toDouble() / r.textTotal >= 0.62, "text recall ${r.textOk}/${r.textTotal}")
    }

    @Test
    fun matchesGoldenOcr() {
        assumeTrue(isDefaultSet, "golden OCR output exists only for fixtures/images")
        val golden = File(javaClass.getResource("/golden")!!.toURI())
        val sims = mutableListOf<Double>()
        for (im in report.images) {
            val g = Fixtures.readJson(golden.resolve(im.name.substringBeforeLast('.') + ".json")).jsonObject
            val refText = g["lines"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
            val ktText = im.result.lines.joinToString("\n") { it.text }
            val sim = SequenceMatcher.ratio(refText, ktText)
            sims.add(sim)
            println("${im.name} similarity=${"%.3f".format(sim)}")
            if (sim < 0.9) println("  REF: ${refText.replace("\n", " | ")}\n  KT:  ${ktText.replace("\n", " | ")}")
        }
        val mean = sims.average()
        println("MEAN similarity ${"%.3f".format(mean)}")
        assertTrue(mean >= 0.9, "mean OCR similarity to the golden output $mean")
    }
}
