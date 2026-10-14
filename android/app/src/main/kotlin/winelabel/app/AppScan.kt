package winelabel.app

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import winelabel.ScanResult
import winelabel.Scanner
import winelabel.model.RgbImage
import winelabel.ocr.OcrEngine
import winelabel.ocr.PpOcrEngine

/** Engine details of one scan, shown under the results and included in the debug report. */
data class ScanInfo(
    val timings: Map<String, Long> = emptyMap(),
    val detInput: PpOcrEngine.DetInput? = null,
    val detMaxPixels: Int = 0,
    val availMemMb: Long = 0,
)

/**
 * The PP-OCR detector needs ~170 bytes of memory per input pixel (measured on a 2.7 GB phone,
 * PROJECT_NOTES "Memory on the phone"); a 13 MP photo gives a 3 MP detector input, which can
 * get the app killed. Before each scan, cap the detector input to what fits in the free memory.
 * With enough memory the cap is above the image size and results are unchanged.
 */
object DeviceMemory {
    const val BYTES_PER_DET_PIXEL = 170L
    /** Below this the detector misses too much small print; better to try than to refuse. */
    const val MIN_DET_PIXELS = 1_000_000
    private const val MARGIN_BYTES = 64L shl 20

    data class Budget(val availMemMb: Long, val detMaxPixels: Int)

    fun budget(context: Context): Budget {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // threshold = where the system starts killing background apps; stay above it.
        val headroom = mi.availMem - mi.threshold - MARGIN_BYTES
        val pixels = (headroom / BYTES_PER_DET_PIXEL).coerceIn(MIN_DET_PIXELS.toLong(), Int.MAX_VALUE.toLong())
        return Budget(mi.availMem shr 20, pixels.toInt())
    }
}

/** The app's scan path (UI and DeviceScanTest): memory budget, scan, engine details. */
object AppScan {
    private const val TAG = "WineLabel"

    /** [forceDetMaxPixels] overrides the memory budget (on-device tests). */
    fun scan(context: Context, img: RgbImage, engine: OcrEngine, forceDetMaxPixels: Int? = null): Pair<ScanResult, ScanInfo> {
        val pp = engine as? PpOcrEngine ?: return Scanner.scan(img, engine) to ScanInfo()
        Runtime.getRuntime().gc() // give back the previous scan's garbage before measuring
        val budget = DeviceMemory.budget(context)
        pp.detMaxPixels = forceDetMaxPixels ?: budget.detMaxPixels
        val result = Scanner.scan(img, pp)
        val det = pp.lastDetInput
        if (det?.capped == true) {
            Log.w(TAG, "Low memory (${budget.availMemMb} MB free): detector input reduced to ${det.width}x${det.height}")
        }
        return result to ScanInfo(pp.lastTimings, det, pp.detMaxPixels, budget.availMemMb)
    }
}
