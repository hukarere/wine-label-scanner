package winelabel.ocr

import winelabel.model.RgbImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Planar float image, channels in **BGR** order (what PP-OCR models were trained on,
 * and what rapidocr feeds them from OpenCV). Values 0..255.
 */
class Planes(val width: Int, val height: Int, val data: Array<FloatArray> = Array(3) { FloatArray(width * height) }) {
    operator fun get(c: Int, x: Int, y: Int) = data[c][y * width + x]

    companion object {
        fun from(img: RgbImage): Planes {
            val p = Planes(img.width, img.height)
            for (i in img.pixels.indices) {
                val px = img.pixels[i]
                p.data[0][i] = (px and 0xFF).toFloat()          // B
                p.data[1][i] = ((px shr 8) and 0xFF).toFloat()  // G
                p.data[2][i] = ((px shr 16) and 0xFF).toFloat() // R
            }
            return p
        }
    }
}

/** OpenCV-equivalent resampling and geometric operations (no OpenCV dependency). */
object ImageOps {

    /** cv2.resize(..., INTER_LINEAR): bilinear with half-pixel centres, edge clamped. */
    fun resizeBilinear(src: Planes, w: Int, h: Int): Planes {
        val dst = Planes(w, h)
        val sx = src.width.toDouble() / w
        val sy = src.height.toDouble() / h
        val x0 = IntArray(w); val x1 = IntArray(w); val fx = FloatArray(w)
        for (x in 0 until w) {
            val fxs = ((x + 0.5) * sx - 0.5).coerceAtLeast(0.0)
            val xi = floor(fxs).toInt().coerceAtMost(src.width - 1)
            x0[x] = xi; x1[x] = min(xi + 1, src.width - 1); fx[x] = (fxs - xi).toFloat()
        }
        for (y in 0 until h) {
            val fys = ((y + 0.5) * sy - 0.5).coerceAtLeast(0.0)
            val y0 = floor(fys).toInt().coerceAtMost(src.height - 1)
            val y1 = min(y0 + 1, src.height - 1)
            val fy = (fys - y0).toFloat()
            for (c in 0 until 3) {
                val s = src.data[c]; val d = dst.data[c]
                val r0 = y0 * src.width; val r1 = y1 * src.width
                for (x in 0 until w) {
                    val top = s[r0 + x0[x]] * (1 - fx[x]) + s[r0 + x1[x]] * fx[x]
                    val bot = s[r1 + x0[x]] * (1 - fx[x]) + s[r1 + x1[x]] * fx[x]
                    d[y * w + x] = top * (1 - fy) + bot * fy
                }
            }
        }
        return dst
    }

    /** cv2.resize(..., INTER_AREA) for downscaling: average of covered source pixels. */
    fun resizeArea(src: Planes, w: Int, h: Int): Planes {
        if (w >= src.width || h >= src.height) return resizeBilinear(src, w, h)
        return Planes(w, h, Array(3) { c -> resizeAreaChannel(src.data[c], src.width, src.height, w, h) })
    }

    /**
     * [resizeArea] on one channel (sw×sh → w×h, downscaling only). Channels are independent,
     * so resizing them one at a time gives identical values with a third of the memory.
     */
    fun resizeAreaChannel(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        val dst = FloatArray(w * h)
        val sx = sw.toDouble() / w
        val sy = sh.toDouble() / h
        val tmp = FloatArray(w * sh)
        // Horizontal pass
        for (x in 0 until w) {
            val a = x * sx; val b = a + sx
            var i = floor(a).toInt()
            while (i < ceil(b).toInt() && i < sw) {
                val wgt = (min(b, i + 1.0) - max(a, i.toDouble())).toFloat() / sx.toFloat()
                for (y in 0 until sh) tmp[y * w + x] += src[y * sw + i] * wgt
                i++
            }
        }
        // Vertical pass
        for (y in 0 until h) {
            val a = y * sy; val b = a + sy
            var j = floor(a).toInt()
            while (j < ceil(b).toInt() && j < sh) {
                val wgt = (min(b, j + 1.0) - max(a, j.toDouble())).toFloat() / sy.toFloat()
                for (x in 0 until w) dst[y * w + x] += tmp[j * w + x] * wgt
                j++
            }
        }
        return dst
    }

    /**
     * [resizeBilinear] followed by PP-OCR normalization ((v/255 - 0.5)/0.5 = v/127.5 - 1), written
     * straight into [out] as a CHW tensor. Same values as resize + fill, without the resized copy.
     */
    fun resizeBilinearNormalized(src: Planes, w: Int, h: Int, out: java.nio.FloatBuffer) {
        val sx = src.width.toDouble() / w
        val sy = src.height.toDouble() / h
        val plane = w * h
        val x0 = IntArray(w); val x1 = IntArray(w); val fx = FloatArray(w)
        for (x in 0 until w) {
            val fxs = ((x + 0.5) * sx - 0.5).coerceAtLeast(0.0)
            val xi = floor(fxs).toInt().coerceAtMost(src.width - 1)
            x0[x] = xi; x1[x] = min(xi + 1, src.width - 1); fx[x] = (fxs - xi).toFloat()
        }
        for (y in 0 until h) {
            val fys = ((y + 0.5) * sy - 0.5).coerceAtLeast(0.0)
            val y0 = floor(fys).toInt().coerceAtMost(src.height - 1)
            val y1 = min(y0 + 1, src.height - 1)
            val fy = (fys - y0).toFloat()
            for (c in 0 until 3) {
                val s = src.data[c]
                val r0 = y0 * src.width; val r1 = y1 * src.width
                for (x in 0 until w) {
                    val top = s[r0 + x0[x]] * (1 - fx[x]) + s[r0 + x1[x]] * fx[x]
                    val bot = s[r1 + x0[x]] * (1 - fx[x]) + s[r1 + x1[x]] * fx[x]
                    out.put(c * plane + y * w + x, (top * (1 - fy) + bot * fy) / 127.5f - 1f)
                }
            }
        }
    }

    /** Constant (black) padding top and bottom — rapidocr's add_round_letterbox. */
    fun padVertical(src: Planes, pad: Int): Planes {
        val dst = Planes(src.width, src.height + 2 * pad)
        for (c in 0 until 3) System.arraycopy(src.data[c], 0, dst.data[c], pad * src.width, src.data[c].size)
        return dst
    }

    /** np.rot90 (counter-clockwise). */
    fun rot90(src: Planes): Planes {
        val dst = Planes(src.height, src.width)
        for (c in 0 until 3) for (y in 0 until dst.height) for (x in 0 until dst.width) {
            dst.data[c][y * dst.width + x] = src.data[c][x * src.width + (src.width - 1 - y)]
        }
        return dst
    }

    /** cv2.rotate(img, ROTATE_180). */
    fun rot180(src: Planes): Planes {
        val dst = Planes(src.width, src.height)
        val n = src.width * src.height
        for (c in 0 until 3) for (i in 0 until n) dst.data[c][i] = src.data[c][n - 1 - i]
        return dst
    }

    /**
     * cv2.getPerspectiveTransform + cv2.warpPerspective(INTER_CUBIC, BORDER_REPLICATE):
     * cut the quadrilateral [quad] (tl, tr, br, bl) out of [src] into a w×h image.
     */
    fun warpQuad(src: Planes, quad: List<DoubleArray>, w: Int, h: Int): Planes {
        // Map destination pixel -> source point.
        val dstPts = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w.toDouble(), 0.0),
            doubleArrayOf(w.toDouble(), h.toDouble()), doubleArrayOf(0.0, h.toDouble()))
        val m = perspectiveTransform(dstPts, quad)
        val dst = Planes(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val den = m[6] * x + m[7] * y + 1.0
            val u = (m[0] * x + m[1] * y + m[2]) / den
            val v = (m[3] * x + m[4] * y + m[5]) / den
            for (c in 0 until 3) dst.data[c][y * w + x] = bicubic(src, c, u, v)
        }
        return dst
    }

    /** 3x3 homography (h33 = 1) mapping [from] points onto [to] points. */
    fun perspectiveTransform(from: List<DoubleArray>, to: List<DoubleArray>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = from[i][0] to from[i][1]
            val (u, v) = to[i][0] to to[i][1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until 8) {
            val pivot = (col until 8).maxBy { abs(a[it][col]) }
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
            val p = a[col][col]
            if (abs(p) < 1e-12) continue
            for (k in col until 9) a[col][k] /= p
            for (r in 0 until 8) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (k in col until 9) a[r][k] -= f * a[col][k]
            }
        }
        return DoubleArray(8) { a[it][8] }
    }

    private fun cubicWeight(t: Double): Double {
        val a = -0.75 // OpenCV's bicubic coefficient
        val x = abs(t)
        return when {
            x <= 1 -> ((a + 2) * x - (a + 3)) * x * x + 1
            x < 2 -> ((a * x - 5 * a) * x + 8 * a) * x - 4 * a
            else -> 0.0
        }
    }

    private fun bicubic(src: Planes, c: Int, u: Double, v: Double): Float {
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val fx = u - x0
        val fy = v - y0
        var sum = 0.0
        for (j in -1..2) {
            val wy = cubicWeight(j - fy)
            val yy = (y0 + j).coerceIn(0, src.height - 1)
            var row = 0.0
            for (i in -1..2) {
                val xx = (x0 + i).coerceIn(0, src.width - 1)
                row += cubicWeight(i - fx) * src.data[c][yy * src.width + xx]
            }
            sum += wy * row
        }
        return sum.coerceIn(0.0, 255.0).toFloat()
    }
}
