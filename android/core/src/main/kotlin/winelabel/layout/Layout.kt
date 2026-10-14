package winelabel.layout

import winelabel.model.OcrLine
import kotlin.math.abs

/** Unordered OCR lines -> rows -> reading order -> raw text. */
object Layout {
    /** Lines whose vertical centres overlap form a row; rows top to bottom, each left to right. */
    fun groupRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.center.y }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowY = row.sumOf { it.center.y } / row.size
                val tolerance = 0.5 * minOf(line.height, row.minOf { it.height })
                if (abs(line.center.y - rowY) <= tolerance) {
                    row.add(line)
                    continue
                }
            }
            rows.add(mutableListOf(line))
        }
        return rows.map { r -> r.sortedBy { it.left } }
    }

    fun readingOrder(lines: List<OcrLine>): List<OcrLine> = groupRows(lines).flatten()

    /** All label text, one visual row per line. */
    fun rawText(lines: List<OcrLine>): String =
        groupRows(lines).joinToString("\n") { row -> row.joinToString("  ") { it.text } }
}
