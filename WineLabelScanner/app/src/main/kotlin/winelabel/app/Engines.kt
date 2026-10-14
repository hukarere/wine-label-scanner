package winelabel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point as AndroidPoint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import winelabel.model.OcrLine
import winelabel.model.Point
import winelabel.model.RgbImage
import winelabel.ocr.OcrEngine
import winelabel.ocr.PpOcrEngine

enum class EngineKind(val label: String) { ONNX("PP-OCR (ONNX)"), MLKIT("ML Kit") }

/** Creates engines lazily (model loading takes a moment) and keeps them for the app's lifetime. */
class Engines(private val context: Context) {
    private val cache = mutableMapOf<EngineKind, OcrEngine>()

    @Synchronized
    fun get(kind: EngineKind): OcrEngine = cache.getOrPut(kind) {
        when (kind) {
            EngineKind.ONNX -> {
                fun asset(name: String) = context.assets.open("models/$name").use { it.readBytes() }
                PpOcrEngine(
                    detModel = asset("PP-OCRv6_det_small.onnx"),
                    clsModel = asset("ch_ppocr_mobile_v2.0_cls_mobile.onnx"),
                    recModel = asset("PP-OCRv6_rec_small.onnx"),
                    keys = asset("ppocrv6_keys.txt").toString(Charsets.UTF_8),
                )
            }
            EngineKind.MLKIT -> MlKitEngine()
        }
    }

    fun closeAll() = synchronized(this) { cache.values.forEach { it.close() }; cache.clear() }
}

/** Google ML Kit on-device Latin text recognition, adapted to the shared OcrEngine interface. */
class MlKitEngine : OcrEngine {
    override val name = "ML Kit (Latin)"
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(image: RgbImage): List<OcrLine> {
        val bitmap = image.toBitmap()
        val text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)))
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val corners = line.cornerPoints?.takeIf { it.size == 4 } ?: return@mapNotNull null
            OcrLine(line.text, line.confidence.toDouble(), corners.map { it.toPoint() })
        }
    }

    override fun close() = client.close()

    private fun AndroidPoint.toPoint() = Point(x.toDouble(), y.toDouble())
}

fun RgbImage.toBitmap(): Bitmap {
    val argb = IntArray(pixels.size) { pixels[it] or (0xFF shl 24) }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}
