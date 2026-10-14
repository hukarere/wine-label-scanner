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

/** Image -> OCR lines -> raw text + parsed fields. */
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
        if (w >= img.width || h >= img.height) {
            val p = ImageOps.resizeArea(Planes.from(img), w, h)
            val px = IntArray(w * h) { i ->
                val b = p.data[0][i].roundToInt().coerceIn(0, 255)
                val g = p.data[1][i].roundToInt().coerceIn(0, 255)
                val r = p.data[2][i].roundToInt().coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            }
            return RgbImage(w, h, px)
        }
        // One channel at a time (B, G, R = bits 0, 8, 16): a full-size float copy of all three
        // channels would be 12 bytes per pixel of a phone photo.
        val px = IntArray(w * h)
        val channel = FloatArray(img.width * img.height)
        for (shift in intArrayOf(0, 8, 16)) {
            for (i in channel.indices) channel[i] = ((img.pixels[i] shr shift) and 0xFF).toFloat()
            val d = ImageOps.resizeAreaChannel(channel, img.width, img.height, w, h)
            for (i in d.indices) px[i] = px[i] or (d[i].roundToInt().coerceIn(0, 255) shl shift)
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
