package winelabel.cli

import winelabel.ScanResult
import winelabel.model.RgbImage
import winelabel.ocr.PpOcrEngine
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Desktop (JVM) helpers: the same models and pipeline as the app, images read with ImageIO. */
object Desktop {
    /** Where the app's model files live; the Gradle tasks pass it as a system property. */
    val modelsDir = File(System.getProperty("winelabel.models") ?: "app/src/main/assets/models")

    fun engine(dir: File = modelsDir) = PpOcrEngine(
        dir.resolve("PP-OCRv6_det_small.onnx").readBytes(),
        dir.resolve("ch_ppocr_mobile_v2.0_cls_mobile.onnx").readBytes(),
        dir.resolve("PP-OCRv6_rec_small.onnx").readBytes(),
        dir.resolve("ppocrv6_keys.txt").readText(),
    )

    fun loadImage(f: File): RgbImage {
        val bi = ImageIO.read(f) ?: error("Not an image: $f")
        return RgbImage(bi.width, bi.height, bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width))
    }

    /** The image OCR ran on, with numbered boxes: green = confident (>= 0.8), orange = doubtful. */
    fun writeDebugImage(result: ScanResult, out: File) {
        val img = result.image
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val thickness = maxOf(1, img.width / 600).toFloat()
        g.stroke = BasicStroke(thickness)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (12 * thickness).toInt())
        result.lines.forEachIndexed { i, line ->
            g.color = if (line.confidence >= 0.8) Color(0, 180, 0) else Color(255, 140, 0)
            val xs = line.box.map { it.x.toInt() }.toIntArray()
            val ys = line.box.map { it.y.toInt() }.toIntArray()
            g.drawPolygon(xs, ys, xs.size)
            g.color = Color.RED
            g.drawString("${i + 1}", xs[0], maxOf(ys[0] - 4, 12))
        }
        g.dispose()
        ImageIO.write(bi, out.extension.ifEmpty { "png" }, out)
    }
}
