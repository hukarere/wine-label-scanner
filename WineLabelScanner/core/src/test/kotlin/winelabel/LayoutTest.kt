package winelabel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import winelabel.layout.Layout
import winelabel.model.OcrLine
import winelabel.model.Point

/** Mirrors tests/test_layout.py. */
class LayoutTest {
    @Test
    fun rowsAndOrder() {
        val lines = listOf(
            OcrLine.rect("750 ml", 0.9, 300.0, 200.0, 80.0, 20.0),
            OcrLine.rect("13% vol", 0.9, 20.0, 203.0, 90.0, 20.0),
            OcrLine.rect("2015", 0.9, 100.0, 120.0, 60.0, 30.0),
            OcrLine.rect("Château X", 0.9, 50.0, 10.0, 300.0, 60.0),
        )
        assertEquals(listOf("Château X", "2015", "13% vol", "750 ml"), Layout.readingOrder(lines).map { it.text })
        assertEquals(3, Layout.groupRows(lines).size)
        assertEquals("Château X\n2015\n13% vol  750 ml", Layout.rawText(lines))
    }

    @Test
    fun heightOfRotatedBox() {
        val line = OcrLine("x", 1.0, listOf(Point(0.0, 0.0), Point(0.0, 100.0), Point(-20.0, 100.0), Point(-20.0, 0.0)))
        assertEquals(20.0, line.height, 1e-6)
    }

    @Test
    fun empty() {
        assertEquals("", Layout.rawText(emptyList()))
    }
}
