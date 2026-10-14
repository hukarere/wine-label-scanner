# Wine Label Scanner — Android app

Take or pick a photo of a wine bottle → **all text on the label** + **parsed
fields** (producer, vintage, appellation, classification, grapes, ABV, volume,
country). Fully on-device, two switchable OCR engines:

- **PP-OCR (ONNX)** — PP-OCRv6 det/rec + orientation classifier on ONNX Runtime
  (a port of rapidocr's pipeline). The default and the more accurate engine.
- **ML Kit** — Google's on-device Latin text recognizer, kept for comparison.

## Install the debug APK

```bash
scp <vps>:/home/grg/android/wine-label-scanner-debug.apk .
adb install -r wine-label-scanner-debug.apk
```

Or open the file on the phone → allow "install unknown apps" → Install.
Android 7.0+ (arm64 phones).

## Use

1. Pick the engine (chips at the top), then **Take photo** or **Choose photo**
   (or share a photo into the app from the gallery).
2. Results: the photo with detected text boxes (green = confident, orange =
   doubtful), the parsed fields with confidence (faded = below 50%), and all text.
   "reduced detail: low memory" means the phone was short of memory and the text
   detector ran on a smaller copy of the photo.
3. **Re-scan with …** reads the same photo with the other engine.
4. **Copy debug report** copies JSON (device, engine, timings, detector size and free
   memory, every OCR line with its box and confidence, fields). Paste it back to turn a
   bad scan into a test case.

## Build and test

```bash
export JAVA_HOME=~/opt/jdk-17          # Temurin 17; Android SDK at ~/Android/Sdk (local.properties)
./gradlew :core:test                   # 58 JVM tests, ~1 min
./gradlew -q :core:cli --args="eval fixtures/images"   # per-photo accuracy report
./gradlew :app:assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
```

On a phone (via adb): `./gradlew :app:assembleDebugAndroidTest`, install both APKs,
then `tools/device-test.sh ONNX` — see `docs/PROJECT_NOTES.md` "Testing on the user's phone".

## Modules

```
core/   pure Kotlin/JVM — all recognition logic, tested on the VPS
  src/main/kotlin/winelabel/
    model/      OcrLine, RgbImage (platform-neutral image)
    layout/     reading order, raw text
    parse/      LabelParser, Reference (grape/region/country lists), Text,
                SequenceMatcher (difflib's ratio, for fuzzy matching)
    ocr/        OcrEngine interface, PpOcrEngine (port of rapidocr's pipeline),
                ImageOps + Geometry (replace OpenCV / pyclipper)
    Scanner.kt  image -> OCR -> noise filter -> reading order -> fields
  src/cli/      desktop command line (scan / eval) and the shared evaluator; not in the app
  src/test/     JUnit tests
app/    Android (Compose)
  Engines.kt        ONNX engine from assets, ML Kit adapter
  AppScan.kt        free-memory budget -> detector size cap, scan, engine details
  AndroidImages.kt  URI -> downsampled, EXIF-rotated RgbImage
  ScanViewModel.kt  state machine, runs scans off the main thread
  DebugReport.kt    JSON report
  ui/ScanScreen.kt  the single screen
  src/androidTest/  DeviceScanTest: both engines on a photo set, on a real phone
```

`core` compiles against `ai.onnxruntime` from the desktop artifact
(`compileOnly`); the app supplies `onnxruntime-android`, which has the same API.

## Tests

| Test | What it checks |
|---|---|
| `ParserFixtureTest` | every OCR-line case in `../fixtures/text` |
| `ParserTest`, `LayoutTest` | parser and layout units, difflib-compatible fuzzy scores, noise filter |
| `SyntheticLabelTest` | a rendered label (straight and rotated 7°) through the real models |
| `PpOcrParityTest` | OCR + parser on the 8 photos in `../fixtures/images`: accuracy floors, and OCR text vs. the frozen golden output in `core/src/test/resources/golden` (from the retired Python prototype) |
| `AndroidRegexCompatTest` | every regex is valid on Android's ICU engine |

Results on the photo sets (identical on the VPS JVM and on the phone):

| | Fields | Text snippets |
|---|---|---|
| 8 Wikimedia photos (`fixtures/images`) | 31/37 | 39/59 |
| 26 Vinmonopolet photos (local only) | 77/108 | 123/140 |

## Notes

- APK is ~74 MB: models 32 MB, ONNX Runtime 18 MB, ML Kit 12 MB, unminified debug
  code ~10 MB. A release build with R8 would drop most of the code size.
- ML Kit's dependencies add INTERNET/ACCESS_NETWORK_STATE permissions (Google
  telemetry); OCR itself never needs the network.
- The recognizer's character list ships as `assets/models/ppocrv6_keys.txt`: it is
  also embedded in the ONNX model metadata, but ONNX Runtime's Java API mangles that
  UTF-8 string, so it was extracted once with the Python `onnx` package (recipe in
  `docs/PROJECT_NOTES.md`).
