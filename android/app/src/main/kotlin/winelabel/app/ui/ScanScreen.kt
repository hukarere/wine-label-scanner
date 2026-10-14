package winelabel.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import winelabel.ScanResult
import winelabel.app.DebugReport
import winelabel.app.EngineKind
import winelabel.app.ScanViewModel
import winelabel.app.UiState
import winelabel.app.ScanInfo
import winelabel.app.toBitmap
import java.io.File

private val FIELD_LABELS = mapOf(
    "producer" to "Producer", "vintage" to "Vintage", "appellation" to "Appellation",
    "classification" to "Classification", "grapes" to "Grapes", "abv" to "Alcohol",
    "volume_ml" to "Volume", "country" to "Country",
)

@Composable
fun WineTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFE8A0B4))
        else -> lightColorScheme(primary = Color(0xFF7A1F3D))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()

    val captureUri = remember { newCaptureUri(context) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.scan(captureUri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.scan(it) }
    }
    val busy = state is UiState.Working

    Scaffold(topBar = { TopAppBar(title = { Text("Wine Label Scanner") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Engine:", style = MaterialTheme.typography.labelLarge)
                EngineKind.entries.forEach { kind ->
                    FilterChip(
                        selected = engine == kind,
                        onClick = { vm.setEngine(kind) },
                        label = { Text(kind.label) },
                        enabled = !busy,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { takePicture.launch(captureUri) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Take photo")
                }
                OutlinedButton(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Text("Choose photo") }
            }

            when (val s = state) {
                UiState.Idle -> Tips()
                is UiState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.message)
                }
                is UiState.Error -> Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { copy(context, "Error details", s.details) }) {
                            Text("Copy error details")
                        }
                    }
                }
                is UiState.Done -> Results(s.result, s.info, engine, onCompare = { vm.setEngine(it); vm.rescan() })
            }
        }
    }
}

@Composable
private fun Tips() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("For best results", fontWeight = FontWeight.Bold)
            Text("• Fill the frame with the label")
            Text("• Shoot straight on, not from an angle")
            Text("• Avoid glare: tilt the bottle away from lights")
            Text("• Curved label? Take it in two shots, left and right")
            Text("• You can also share a photo into this app from the gallery")
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Results(result: ScanResult, info: ScanInfo, current: EngineKind, onCompare: (EngineKind) -> Unit) {
    val context = LocalContext.current
    val other = EngineKind.entries.first { it != current }

    PhotoWithBoxes(result)
    Text(
        "${result.engine} · ${result.lines.size} lines · ${result.millis} ms" +
            (if (info.timings.isNotEmpty()) " (${info.timings.entries.joinToString { "${it.key} ${it.value}" }})" else "") +
            (if (info.detInput?.capped == true) " · reduced detail: low memory" else ""),
        style = MaterialTheme.typography.bodySmall,
    )

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Label details", fontWeight = FontWeight.Bold)
            for ((name, f) in result.label.fields()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(FIELD_LABELS[name] ?: name, Modifier.weight(0.35f), style = MaterialTheme.typography.labelLarge)
                    if (f == null) {
                        Text("—", Modifier.weight(0.65f).alpha(0.4f))
                    } else {
                        val value = when (name) {
                            "abv" -> "${f.value} %"
                            "volume_ml" -> "${f.value} ml"
                            else -> (f.value as? List<*>)?.joinToString(", ") ?: f.value.toString()
                        }
                        // Doubtful values are shown faded so the user knows to check them.
                        Text(
                            "$value  (${"%.0f".format(f.confidence * 100)}%)",
                            Modifier.weight(0.65f).alpha(if (f.confidence < 0.5) 0.45f else 1f),
                        )
                    }
                }
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("All text on the label", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(result.rawText.ifEmpty { "(no text found)" }, fontFamily = FontFamily.Monospace)
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { copy(context, "Label text", result.rawText) }) { Text("Copy text") }
        OutlinedButton(onClick = { share(context, result.rawText) }) { Text("Share") }
        OutlinedButton(onClick = {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            copy(context, "Debug report", DebugReport.build(result, info, version))
        }) { Text("Copy debug report") }
        OutlinedButton(onClick = { onCompare(other) }) { Text("Re-scan with ${other.label}") }
    }
    Spacer(Modifier.height(24.dp))
}

/** The scanned photo with detected text boxes drawn on top. */
@Composable
private fun PhotoWithBoxes(result: ScanResult) {
    val bitmap = remember(result) { result.image.toBitmap().asImageBitmap() }
    val ratio = result.image.width.toFloat() / result.image.height
    Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.4f, 2.5f))) {
        Image(bitmap, contentDescription = "Scanned photo", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            // Image is letterboxed (ContentScale.Fit) when the aspect ratio was clamped.
            val scale = minOf(size.width / result.image.width, size.height / result.image.height)
            val dx = (size.width - result.image.width * scale) / 2
            val dy = (size.height - result.image.height * scale) / 2
            for (line in result.lines) {
                val path = Path().apply {
                    line.box.forEachIndexed { i, p ->
                        val o = Offset(dx + p.x.toFloat() * scale, dy + p.y.toFloat() * scale)
                        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                    close()
                }
                val color = if (line.confidence >= 0.8) Color(0xFF2E7D32) else Color(0xFFEF6C00)
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "capture.jpg"))
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share label text"))
}
