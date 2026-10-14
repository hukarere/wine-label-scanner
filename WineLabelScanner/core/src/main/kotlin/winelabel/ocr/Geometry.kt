package winelabel.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Rotated rectangle like cv2.RotatedRect: centre, side lengths, angle (radians) of the [w] side. */
data class RotatedRect(val cx: Double, val cy: Double, val w: Double, val h: Double, val angle: Double) {
    /** The 4 corners (cv2.boxPoints, in no particular order). */
    fun corners(): List<DoubleArray> {
        val ux = cos(angle) * w / 2; val uy = sin(angle) * w / 2
        val vx = -sin(angle) * h / 2; val vy = cos(angle) * h / 2
        return listOf(
            doubleArrayOf(cx - ux - vx, cy - uy - vy), doubleArrayOf(cx + ux - vx, cy + uy - vy),
            doubleArrayOf(cx + ux + vx, cy + uy + vy), doubleArrayOf(cx - ux + vx, cy - uy + vy),
        )
    }

    val shortSide get() = min(w, h)
}

/** Geometry helpers replacing the OpenCV / pyclipper / shapely calls in rapidocr's DB post-processing. */
object Geometry {

    /**
     * Connected components (8-connectivity) of a binary mask, like the outer contours
     * of cv2.findContours. Each component is returned as the convex-hull-relevant points:
     * the leftmost and rightmost pixel of every row it spans.
     */
    fun components(mask: BooleanArray, width: Int, height: Int, maxCount: Int): List<List<IntArray>> {
        val label = IntArray(width * height)
        val out = mutableListOf<List<IntArray>>()
        val stack = IntArray(width * height)
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            if (out.size >= maxCount) break
            next++
            var sp = 0
            stack[sp++] = start
            label[start] = next
            val rowMin = HashMap<Int, Int>()
            val rowMax = HashMap<Int, Int>()
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % width
                val y = p / width
                rowMin[y] = min(rowMin[y] ?: x, x)
                rowMax[y] = max(rowMax[y] ?: x, x)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width || (dx == 0 && dy == 0)) continue
                        val q = ny * width + nx
                        if (mask[q] && label[q] == 0) {
                            label[q] = next
                            stack[sp++] = q
                        }
                    }
                }
            }
            val pts = mutableListOf<IntArray>()
            for ((y, x0) in rowMin) {
                pts.add(intArrayOf(x0, y))
                val x1 = rowMax[y]!!
                if (x1 != x0) pts.add(intArrayOf(x1, y))
            }
            out.add(pts)
        }
        return out
    }

    /** Andrew's monotone chain convex hull (counter-clockwise, no duplicate end point). */
    fun convexHull(points: List<DoubleArray>): List<DoubleArray> {
        val pts = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size <= 2) return pts
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = mutableListOf<DoubleArray>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<DoubleArray>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** cv2.minAreaRect: rotating calipers over the convex hull. */
    fun minAreaRect(points: List<DoubleArray>): RotatedRect {
        val hull = convexHull(points)
        if (hull.size == 1) return RotatedRect(hull[0][0], hull[0][1], 0.0, 0.0, 0.0)
        if (hull.size == 2) {
            val (a, b) = hull
            return RotatedRect((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, hypot(b[0] - a[0], b[1] - a[1]), 0.0,
                atan2(b[1] - a[1], b[0] - a[0]))
        }
        var best: RotatedRect? = null
        var bestArea = Double.MAX_VALUE
        for (i in hull.indices) {
            val p = hull[i]; val q = hull[(i + 1) % hull.size]
            val len = hypot(q[0] - p[0], q[1] - p[1])
            if (len == 0.0) continue
            val ux = (q[0] - p[0]) / len; val uy = (q[1] - p[1]) / len
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (r in hull) {
                val u = r[0] * ux + r[1] * uy
                val v = -r[0] * uy + r[1] * ux
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2; val cv = (minV + maxV) / 2
                best = RotatedRect(cu * ux - cv * uy, cu * uy + cv * ux, maxU - minU, maxV - minV, atan2(uy, ux))
            }
        }
        return best!!
    }

    /**
     * rapidocr's get_mini_boxes ordering: sort corners by x, then pick
     * top-left, top-right, bottom-right, bottom-left.
     */
    fun miniBox(rect: RotatedRect): List<DoubleArray> {
        val p = rect.corners().sortedBy { it[0] }
        val (i1, i4) = if (p[1][1] > p[0][1]) 0 to 1 else 1 to 0
        val (i2, i3) = if (p[3][1] > p[2][1]) 2 to 3 else 3 to 2
        return listOf(p[i1], p[i2], p[i3], p[i4])
    }

    /**
     * DB "unclip": offset the box outward by area * ratio / perimeter. For a rectangle the
     * min-area rect of pyclipper's round-joined offset is the rectangle grown by d on each side.
     */
    fun unclip(rect: RotatedRect, ratio: Double): RotatedRect {
        val area = rect.w * rect.h
        val perimeter = 2 * (rect.w + rect.h)
        if (perimeter == 0.0) return rect
        val d = area * ratio / perimeter
        return rect.copy(w = rect.w + 2 * d, h = rect.h + 2 * d)
    }

    /** box_score_fast: mean of [prob] inside the polygon (integer-truncated vertices, like cv2.fillPoly). */
    fun polygonMean(prob: FloatArray, width: Int, height: Int, box: List<DoubleArray>): Double {
        val xmin = floor(box.minOf { it[0] }).toInt().coerceIn(0, width - 1)
        val xmax = ceil(box.maxOf { it[0] }).toInt().coerceIn(0, width - 1)
        val ymin = floor(box.minOf { it[1] }).toInt().coerceIn(0, height - 1)
        val ymax = ceil(box.maxOf { it[1] }).toInt().coerceIn(0, height - 1)
        val poly = box.map { doubleArrayOf((it[0].toInt() - xmin).toDouble(), (it[1].toInt() - ymin).toDouble()) }
        var sum = 0.0
        var n = 0
        for (y in 0..ymax - ymin) for (x in 0..xmax - xmin) {
            if (insideConvex(poly, x.toDouble(), y.toDouble())) {
                sum += prob[(y + ymin) * width + (x + xmin)]
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Point in convex polygon, boundary inclusive (fillPoly includes edge pixels). */
    private fun insideConvex(poly: List<DoubleArray>, x: Double, y: Double): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0])
            val len = hypot(b[0] - a[0], b[1] - a[1])
            if (abs(cross) <= 0.5 * len) continue // within half a pixel of the edge
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** imutils order_points_clockwise: tl, tr, br, bl. */
    fun orderClockwise(pts: List<DoubleArray>): List<DoubleArray> {
        val xs = pts.sortedBy { it[0] }
        val (tl, bl) = xs.take(2).sortedBy { it[1] }
        val (tr, br) = xs.drop(2).sortedBy { it[1] }
        return listOf(tl, tr, br, bl)
    }
}
