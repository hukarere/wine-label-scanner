package winelabel.model

/**
 * Platform-neutral 8-bit RGB image. [pixels] are packed 0xRRGGBB (alpha ignored),
 * row-major — the same layout as Android's Bitmap.getPixels and Java's BufferedImage.getRGB.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel count ${pixels.size} != ${width}x$height" }
    }

    fun r(x: Int, y: Int) = (pixels[y * width + x] shr 16) and 0xFF
    fun g(x: Int, y: Int) = (pixels[y * width + x] shr 8) and 0xFF
    fun b(x: Int, y: Int) = pixels[y * width + x] and 0xFF
}
