package winelabel

import winelabel.layout.Layout
import winelabel.model.OcrLine
import winelabel.model.RgbImage
import winelabel.ocr.ImageOps
import winelabel.ocr.OcrEngine
import winelabel.ocr.Planes
import winelabel.parse.LabelParser
import winelabel.parse.WineLabel
import kotlin.math.max
import kotlin.math.roundToInt

data class ScanResult(
    val lines: List<OcrLine>,  // reading order, in [image] coordinates
    val rawText: String,
    val label: WineLabel,
    val image: RgbImage,       // the downscaled image OCR ran on
    val millis: Long,
    val engine: String,
)

/** Port of winelabel/scanner.py: image -> OCR lines -> raw text + parsed fields. */
object Scanner {
    const val MAX_SIDE = 2000

    /** Short, low-confidence reads are usually ornaments or drawings, not text. */
    fun isNoise(line: OcrLine) = line.text.length <= 2 && line.confidence < 0.7

    /** Downscale so the long side is at most [maxSide] (area averaging, like cv2.INTER_AREA). */
    fun prepare(img: RgbImage, maxSide: Int = MAX_SIDE): RgbImage {
        val scale = maxSide.toDouble() / max(img.width, img.height)
        if (scale >= 1) return img
        val w = (img.width * scale).roundToInt()
        val h = (img.height * scale).roundToInt()
        val p = ImageOps.resizeArea(Planes.from(img), w, h)
        val px = IntArray(w * h) { i ->
            val b = p.data[0][i].roundToInt().coerceIn(0, 255)
            val g = p.data[1][i].roundToInt().coerceIn(0, 255)
            val r = p.data[2][i].roundToInt().coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }
        return RgbImage(w, h, px)
    }

    fun scan(img: RgbImage, engine: OcrEngine): ScanResult {
        val start = System.nanoTime()
        val prepared = prepare(img)
        val lines = Layout.readingOrder(engine.recognize(prepared).filterNot { isNoise(it) })
        val label = LabelParser.parse(lines)
        return ScanResult(lines, Layout.rawText(lines), label, prepared, (System.nanoTime() - start) / 1_000_000, engine.name)
    }
}
