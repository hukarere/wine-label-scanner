package winelabel

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import winelabel.cli.Desktop
import winelabel.model.RgbImage
import winelabel.parse.Text
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/** End to end on a rendered label: real PP-OCR models, layout, parser; straight and rotated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyntheticLabelTest {
    private val engine by lazy { Desktop.engine(Fixtures.modelsDir) }

    @AfterAll
    fun close() = engine.close()

    private val rows = listOf(
        Triple("GRAND VIN", 40, 60),
        Triple("Château Lafîte Brûlé", 80, 140),
        Triple("PAUILLAC", 50, 300),
        Triple("2015", 90, 400),
        Triple("Appellation Pauillac Contrôlée", 34, 560),
        Triple("Cabernet Sauvignon · Merlot", 34, 640),
        Triple("Mis en bouteille au Château", 26, 800),
        Triple("13,5% vol", 30, 900),
        Triple("750 ml", 30, 960),
        Triple("Produit de France", 26, 1040),
    )

    private val expected = mapOf(
        "producer" to "Château Lafîte Brûlé",
        "vintage" to 2015,
        "appellation" to "Pauillac",
        "grapes" to listOf("Cabernet Sauvignon", "Merlot"),
        "abv" to 13.5,
        "volume_ml" to 750,
        "country" to "France",
    )

    private fun font(size: Int): Font {
        val dejavu = File("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf")
        return if (dejavu.exists()) Font.createFont(Font.TRUETYPE_FONT, dejavu).deriveFont(size.toFloat())
        else Font(Font.SERIF, Font.BOLD, size)
    }

    private fun render(degrees: Double): RgbImage {
        val (w, h) = 1000 to 1200
        val paper = Color(238, 228, 205)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = paper
        g.fillRect(0, 0, w, h)
        g.rotate(Math.toRadians(degrees), w / 2.0, h / 2.0)
        g.color = Color(35, 25, 20)
        for ((text, size, top) in rows) {
            g.font = font(size)
            val fm = g.fontMetrics
            g.drawString(text, (w - fm.stringWidth(text)) / 2, top + fm.ascent)
        }
        g.dispose()
        return RgbImage(w, h, bi.getRGB(0, 0, w, h, null, 0, w))
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 7.0])
    fun readsRenderedLabel(degrees: Double) {
        val result = Scanner.scan(render(degrees), engine)
        assertTrue("Contrôlée" in result.rawText, "accents survive OCR:\n${result.rawText}")
        val values = result.label.values()
        // Small diacritics on names (î) are sometimes dropped by OCR; compare names accent-free.
        assertEquals(Text.normalize(expected["producer"] as String), Text.normalize(values["producer"] as String), result.rawText)
        for ((field, want) in expected) {
            if (field != "producer") assertTrue(Fixtures.valuesEqual(want, values[field]), "$field: ${values[field]}\n${result.rawText}")
        }
    }
}
