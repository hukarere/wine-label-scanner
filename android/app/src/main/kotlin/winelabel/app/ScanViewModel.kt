package winelabel.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import winelabel.ScanResult
import winelabel.Scanner
import winelabel.model.RgbImage
import winelabel.ocr.PpOcrEngine

sealed interface UiState {
    data object Idle : UiState
    data class Working(val message: String) : UiState
    data class Done(val result: ScanResult, val timings: Map<String, Long>) : UiState
    /** [details] is the full stack trace, for "Copy error details". */
    data class Error(val message: String, val details: String) : UiState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val engines = Engines(app)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _engine = MutableStateFlow(
        runCatching { EngineKind.valueOf(prefs.getString("engine", null) ?: "") }.getOrDefault(EngineKind.ONNX)
    )
    val engine: StateFlow<EngineKind> = _engine.asStateFlow()

    /** Kept so the same photo can be re-scanned with the other engine. */
    private var lastImage: RgbImage? = null

    fun setEngine(kind: EngineKind) {
        _engine.value = kind
        prefs.edit().putString("engine", kind.name).apply()
    }

    fun scan(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState.Working("Loading photo…")
            try {
                val img = withContext(Dispatchers.IO) { AndroidImages.load(getApplication(), uri) }
                lastImage = img
                run(img)
            } catch (e: Throwable) {
                fail("Could not load photo", e)
            }
        }
    }

    fun rescan() {
        val img = lastImage ?: return
        viewModelScope.launch { run(img) }
    }

    private suspend fun run(img: RgbImage) {
        val kind = _engine.value
        try {
            _state.value = UiState.Working("Reading label with ${kind.label}…")
            val (result, timings) = withContext(Dispatchers.Default) {
                val engine = engines.get(kind)
                val r = Scanner.scan(img, engine)
                r to ((engine as? PpOcrEngine)?.lastTimings ?: emptyMap())
            }
            _state.value = UiState.Done(result, timings)
        } catch (e: Throwable) {
            fail("${kind.label} failed", e)
        }
    }

    /** Show the error and log it: caught exceptions never reach logcat's AndroidRuntime crash output. */
    private fun fail(what: String, e: Throwable) {
        Log.e(TAG, what, e)
        _state.value = UiState.Error("$what: ${e.message ?: e.javaClass.simpleName}", Log.getStackTraceString(e))
    }

    override fun onCleared() = engines.closeAll()

    companion object {
        const val TAG = "WineLabel"
    }
}
