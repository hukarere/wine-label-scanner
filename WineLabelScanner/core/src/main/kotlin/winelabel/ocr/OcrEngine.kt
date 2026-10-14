package winelabel.ocr

import winelabel.model.OcrLine
import winelabel.model.RgbImage

/** An OCR backend. Implementations: [PpOcrEngine] (ONNX), ML Kit (in the app module). */
interface OcrEngine : AutoCloseable {
    val name: String
    fun recognize(image: RgbImage): List<OcrLine>
    override fun close() {}
}
