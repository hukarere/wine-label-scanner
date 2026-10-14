package winelabel.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import winelabel.model.OcrLine
import winelabel.model.Point
import winelabel.model.RgbImage
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv6 (det + rec) and the PP-OCR text-orientation classifier on ONNX Runtime.
 * A port of rapidocr 3.x's pipeline with its default settings, so results match the
 * Python prototype. Uses only the `ai.onnxruntime` API, which is identical in the
 * desktop (`onnxruntime`) and Android (`onnxruntime-android`) artifacts.
 */
class PpOcrEngine(
    detModel: ByteArray,
    clsModel: ByteArray,
    recModel: ByteArray,
    /**
     * Recognition alphabet, one entry per line (ppocrv6_keys.txt). It is also embedded in the
     * rec model's "character" metadata, but ONNX Runtime's Java API mangles that UTF-8 string
     * (bytes decoded one by one and truncated), so it ships as a separate file.
     */
    keys: String,
    private val config: Config = Config(),
) : OcrEngine {

    data class Config(
        val detLimitSideLen: Int = 736,   // limit_type "min"
        val detThresh: Float = 0.3f,
        val boxThresh: Double = 0.5,
        val unclipRatio: Double = 1.6,
        val maxCandidates: Int = 1000,
        val clsThresh: Float = 0.9f,
        val textScore: Double = 0.5,
        val maxSideLen: Int = 2000,
        val minSideLen: Int = 30,
        val minHeight: Int = 30,          // vertical padding (use_vertical_padding)
        val widthHeightRatio: Double = 8.0,
        val batch: Int = 6,
        val threads: Int = 0,             // 0 = ONNX Runtime default
    )

    override val name = "PP-OCRv6 (ONNX)"

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        if (config.threads > 0) setIntraOpNumThreads(config.threads)
    }
    private val det = env.createSession(detModel, options)
    private val cls = env.createSession(clsModel, options)
    private val rec = env.createSession(recModel, options)

    /** Recognition alphabet plus CTC blank (index 0) and a trailing space, as rapidocr builds it. */
    private val characters: List<String> = listOf("blank") + keys.split("\n") + " "

    init {
        val classes = rec.outputInfo.values.first().info.let { (it as ai.onnxruntime.TensorInfo).shape.last() }
        require(classes == characters.size.toLong()) {
            "keys file has ${characters.size - 2} entries but the rec model outputs $classes classes"
        }
    }

    /** Last call's stage timings in ms, for the debug report. */
    @Volatile var lastTimings: Map<String, Long> = emptyMap()
        private set

    override fun recognize(image: RgbImage): List<OcrLine> {
        val t0 = System.nanoTime()
        val original = Planes.from(image)

        // rapidocr preprocess_img: keep sides within [minSideLen, maxSideLen], multiples of 32.
        var (img, ratioW, ratioH) = boundSides(original)
        // apply_vertical_padding for very wide/short images.
        var padTop = 0
        if (img.height <= config.minHeight || img.width.toDouble() / img.height > config.widthHeightRatio) {
            val newH = max((img.width / config.widthHeightRatio).toInt(), config.minHeight) * 2
            padTop = abs(newH - img.height) / 2
            img = ImageOps.padVertical(img, padTop)
        }

        val t1 = System.nanoTime()
        val boxes = detect(img)
        val t2 = System.nanoTime()
        if (boxes.isEmpty()) {
            lastTimings = mapOf("prep" to ms(t0, t1), "det" to ms(t1, t2))
            return emptyList()
        }
        val crops = boxes.map { cropBox(img, it) }.toMutableList()
        classifyAndRotate(crops)
        val t3 = System.nanoTime()
        val texts = recognizeCrops(crops)
        val t4 = System.nanoTime()
        lastTimings = mapOf("prep" to ms(t0, t1), "det" to ms(t1, t2), "cls" to ms(t2, t3), "rec" to ms(t3, t4))

        val out = mutableListOf<OcrLine>()
        for ((box, res) in boxes.zip(texts)) {
            val (text, score) = res
            if (text.isBlank() || score < config.textScore) continue
            // map_boxes_to_original: undo padding, then scaling; clamp to the image.
            val pts = box.map { p ->
                Point(
                    (p[0] * ratioW).coerceIn(0.0, image.width.toDouble()),
                    ((p[1] - padTop) * ratioH).coerceIn(0.0, image.height.toDouble()),
                )
            }
            out.add(OcrLine(text, score, pts))
        }
        return out
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1_000_000

    // ------------------------------------------------------------------ preprocessing

    private fun round32(v: Int) = (v / 32.0).roundToInt() * 32

    private fun boundSides(src: Planes): Triple<Planes, Double, Double> {
        var img = src
        var ratioW = 1.0
        var ratioH = 1.0
        if (max(img.width, img.height) > config.maxSideLen) {
            val r = config.maxSideLen.toDouble() / max(img.width, img.height)
            val (w, h) = round32((img.width * r).toInt()) to round32((img.height * r).toInt())
            ratioW = img.width.toDouble() / w; ratioH = img.height.toDouble() / h
            img = ImageOps.resizeBilinear(img, w, h)
        }
        if (min(img.width, img.height) < config.minSideLen) {
            val r = config.minSideLen.toDouble() / min(img.width, img.height)
            val (w, h) = round32((img.width * r).toInt()) to round32((img.height * r).toInt())
            ratioW = src.width.toDouble() / w; ratioH = src.height.toDouble() / h
            img = ImageOps.resizeBilinear(img, w, h)
        }
        return Triple(img, ratioW, ratioH)
    }

    /** Normalize to (x/255 - 0.5)/0.5 into a CHW float buffer region, padding columns past [w] with 0. */
    private fun fillTensor(buf: FloatArray, offset: Int, img: Planes, padW: Int) {
        val plane = img.height * padW
        for (c in 0 until 3) for (y in 0 until img.height) {
            val row = offset + c * plane + y * padW
            val srcRow = y * img.width
            for (x in 0 until img.width) buf[row + x] = img.data[c][srcRow + x] / 127.5f - 1f
        }
    }

    // ------------------------------------------------------------------ detection

    /** Returns boxes (tl, tr, br, bl) in the coordinates of [img], sorted in reading order. */
    private fun detect(img: Planes): List<List<DoubleArray>> {
        // DetPreProcess.resize, limit_type "min".
        val shortSide = min(img.width, img.height)
        val ratio = if (shortSide < config.detLimitSideLen) config.detLimitSideLen.toDouble() / shortSide else 1.0
        val rw = round32((img.width * ratio).toInt())
        val rh = round32((img.height * ratio).toInt())
        if (rw <= 0 || rh <= 0) return emptyList()
        val resized = ImageOps.resizeBilinear(img, rw, rh)
        val input = FloatArray(3 * rw * rh)
        fillTensor(input, 0, resized, rw)

        val prob: FloatArray
        val (pw, ph) = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { res ->
                val out = res[0] as OnnxTensor
                val shape = out.info.shape // [1, 1, H, W]
                val fb = out.floatBuffer
                prob = FloatArray(fb.remaining()).also { fb.get(it) }
                shape[3].toInt() to shape[2].toInt()
            }
        }
        return dbPostProcess(prob, pw, ph, img.width, img.height)
    }

    private fun dbPostProcess(prob: FloatArray, w: Int, h: Int, destW: Int, destH: Int): List<List<DoubleArray>> {
        // Binarize, then dilate with a 2x2 kernel (anchor at (1,1): max over the pixel, left and up).
        val seg = BooleanArray(w * h) { prob[it] > config.detThresh }
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            mask[y * w + x] = seg[y * w + x] ||
                (x > 0 && seg[y * w + x - 1]) ||
                (y > 0 && seg[(y - 1) * w + x]) ||
                (x > 0 && y > 0 && seg[(y - 1) * w + x - 1])
        }

        val boxes = mutableListOf<List<DoubleArray>>()
        for (component in Geometry.components(mask, w, h, config.maxCandidates)) {
            val pts = component.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
            val rect = Geometry.minAreaRect(pts)
            if (rect.shortSide < 3) continue
            val score = Geometry.polygonMean(prob, w, h, Geometry.miniBox(rect))
            if (score < config.boxThresh) continue
            val grown = Geometry.unclip(rect, config.unclipRatio)
            if (grown.shortSide < 5) continue
            val box = Geometry.miniBox(grown).map { p ->
                doubleArrayOf(
                    Math.rint(p[0] / w * destW).coerceIn(0.0, destW.toDouble()),
                    Math.rint(p[1] / h * destH).coerceIn(0.0, destH.toDouble()),
                )
            }
            // filter_det_res: order clockwise, clip to the image, drop tiny boxes.
            val ordered = Geometry.orderClockwise(box).map { p ->
                doubleArrayOf(
                    p[0].coerceIn(0.0, destW - 1.0).toInt().toDouble(),
                    p[1].coerceIn(0.0, destH - 1.0).toInt().toDouble(),
                )
            }
            val rectW = hypot(ordered[0][0] - ordered[1][0], ordered[0][1] - ordered[1][1]).toInt()
            val rectH = hypot(ordered[0][0] - ordered[3][0], ordered[0][1] - ordered[3][1]).toInt()
            if (rectW <= 3 || rectH <= 3) continue
            boxes.add(ordered)
        }
        return sortBoxes(boxes)
    }

    /** TextDetector.sorted_boxes: rows by top-left y (new row when the gap is >= 10 px), then x. */
    private fun sortBoxes(boxes: List<List<DoubleArray>>): List<List<DoubleArray>> {
        val byY = boxes.sortedBy { it[0][1] }
        var line = 0
        val lineIds = IntArray(byY.size)
        for (i in 1 until byY.size) {
            if (byY[i][0][1] - byY[i - 1][0][1] >= 10) line++
            lineIds[i] = line
        }
        return byY.indices.sortedWith(compareBy({ lineIds[it] }, { byY[it][0][0] })).map { byY[it] }
    }

    /** get_rotate_crop_image: perspective-cut the box; stand tall crops upright. */
    private fun cropBox(img: Planes, box: List<DoubleArray>): Planes {
        val cw = max(dist(box[0], box[1]), dist(box[2], box[3])).toInt().coerceAtLeast(1)
        val ch = max(dist(box[0], box[3]), dist(box[1], box[2])).toInt().coerceAtLeast(1)
        val crop = ImageOps.warpQuad(img, box, cw, ch)
        return if (crop.height.toDouble() / crop.width >= 1.5) ImageOps.rot90(crop) else crop
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])

    // ------------------------------------------------------------------ orientation (0 / 180)

    private fun classifyAndRotate(crops: MutableList<Planes>) {
        val (clsH, clsW) = 48 to 192
        val order = crops.indices.sortedBy { crops[it].width.toDouble() / crops[it].height }
        for (batch in order.chunked(config.batch)) {
            val input = FloatArray(batch.size * 3 * clsH * clsW)
            batch.forEachIndexed { b, idx ->
                val img = crops[idx]
                val ratio = img.width.toDouble() / img.height
                val rw = if (ceil(clsH * ratio) > clsW) clsW else ceil(clsH * ratio).toInt()
                fillTensor(input, b * 3 * clsH * clsW, ImageOps.resizeBilinear(img, rw.coerceAtLeast(1), clsH), clsW)
            }
            val shape = longArrayOf(batch.size.toLong(), 3, clsH.toLong(), clsW.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
                cls.run(mapOf(cls.inputNames.first() to t)).use { res ->
                    val fb = (res[0] as OnnxTensor).floatBuffer
                    val probs = FloatArray(fb.remaining()).also { fb.get(it) } // [N, 2]: "0", "180"
                    batch.forEachIndexed { b, idx ->
                        val p0 = probs[b * 2]; val p180 = probs[b * 2 + 1]
                        if (p180 > p0 && p180 > config.clsThresh) crops[idx] = ImageOps.rot180(crops[idx])
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ recognition

    private fun recognizeCrops(crops: List<Planes>): List<Pair<String, Double>> {
        val recH = 48
        val results = Array(crops.size) { "" to 0.0 }
        val order = crops.indices.sortedBy { crops[it].width.toDouble() / crops[it].height }
        for (batch in order.chunked(config.batch)) {
            var maxRatio = 320.0 / recH
            for (idx in batch) maxRatio = max(maxRatio, crops[idx].width.toDouble() / crops[idx].height)
            val recW = (recH * maxRatio).toInt()
            val input = FloatArray(batch.size * 3 * recH * recW)
            batch.forEachIndexed { b, idx ->
                val img = crops[idx]
                val ratio = img.width.toDouble() / img.height
                val rw = if (ceil(recH * ratio) > recW) recW else ceil(recH * ratio).toInt()
                fillTensor(input, b * 3 * recH * recW, ImageOps.resizeBilinear(img, rw.coerceAtLeast(1), recH), recW)
            }
            val shape = longArrayOf(batch.size.toLong(), 3, recH.toLong(), recW.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
                rec.run(mapOf(rec.inputNames.first() to t)).use { res ->
                    val out = res[0] as OnnxTensor
                    val (steps, classes) = out.info.shape.let { it[1].toInt() to it[2].toInt() }
                    val fb = out.floatBuffer
                    val probs = FloatArray(fb.remaining()).also { fb.get(it) }
                    batch.forEachIndexed { b, idx -> results[idx] = ctcDecode(probs, b * steps * classes, steps, classes) }
                }
            }
        }
        return results.toList()
    }

    /** Greedy CTC: argmax per step, merge repeats, drop blanks; score = mean prob of kept steps. */
    private fun ctcDecode(probs: FloatArray, offset: Int, steps: Int, classes: Int): Pair<String, Double> {
        val sb = StringBuilder()
        var sum = 0.0
        var n = 0
        var prev = -1
        for (t in 0 until steps) {
            val base = offset + t * classes
            var best = 0
            var bestP = probs[base]
            for (c in 1 until classes) if (probs[base + c] > bestP) { best = c; bestP = probs[base + c] }
            if (best != prev && best != 0 && best < characters.size) {
                sb.append(characters[best])
                sum += bestP
                n++
            }
            prev = best
        }
        return sb.toString() to if (n == 0) 0.0 else sum / n
    }

    override fun close() {
        det.close(); cls.close(); rec.close(); options.close()
    }
}
