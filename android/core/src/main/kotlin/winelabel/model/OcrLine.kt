package winelabel.model

import kotlin.math.hypot

data class Point(val x: Double, val y: Double)

/**
 * One detected text line. [box] holds 4 corners clockwise from top-left
 * (rotated text gives a rotated quadrilateral).
 */
data class OcrLine(val text: String, val confidence: Double, val box: List<Point>) {
    init {
        require(box.size == 4) { "box needs 4 points" }
    }

    /**
     * Text height in pixels: mean length of the left and right edges — or, for vertical text
     * (box at least 1.5× taller than wide, the rule PpOcrEngine uses to turn crops upright),
     * of the top and bottom edges, so a sideways "ALC. 14% BY VOL." isn't taken for a huge name.
     */
    val height: Double
        get() {
            val sides = (dist(box[0], box[3]) + dist(box[1], box[2])) / 2
            val ends = (dist(box[0], box[1]) + dist(box[3], box[2])) / 2
            val vertical = maxOf(dist(box[0], box[3]), dist(box[1], box[2])) >=
                1.5 * maxOf(dist(box[0], box[1]), dist(box[3], box[2]))
            return if (vertical) ends else sides
        }

    val center: Point
        get() = Point(box.sumOf { it.x } / 4, box.sumOf { it.y } / 4)

    val left: Double
        get() = box.minOf { it.x }

    companion object {
        fun rect(text: String, confidence: Double, x: Double, y: Double, w: Double, h: Double) =
            OcrLine(text, confidence, listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h)))
    }
}

internal fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
