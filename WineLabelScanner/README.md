# Wine Label Scanner — Android prototype

Take or pick a photo of a wine bottle → **all text on the label** + **parsed
fields** (producer, vintage, appellation, classification, grapes, ABV, volume,
country). Fully on-device, two switchable OCR engines:

- **PP-OCR (ONNX)** — PP-OCRv6 det/rec + orientation classifier on ONNX Runtime;
  the same models and pipeline as the Python prototype in `../wine-label-py`.
- **ML Kit** — Google's on-device Latin text recognizer, for comparison.

## Install the debug APK

```bash
scp <vps>:/home/grg/android/wine-label-scanner-debug.apk .
```

Then on the phone: open the file → allow "install unknown apps" for the app you
opened it with → Install. Android 7.0+ (arm64 phones).

## Use

1. Pick the engine (chips at the top), then **Take photo** or **Choose photo**
   (or share a photo into the app from the gallery).
2. Results: the photo with detected text boxes (green = confident, orange =
   doubtful), the parsed fields with confidence (faded = below 50%), and all text.
3. **Re-scan with …** reads the same photo with the other engine.
4. **Copy debug report** copies JSON (device, engine, timings, every OCR line with
   its box and confidence, fields). Paste it back to turn a bad scan into a test case.

## Build

Toolchain lives in user space on the VPS:

```bash
export JAVA_HOME=~/opt/jdk-17          # Temurin 17; Android SDK at ~/Android/Sdk (local.properties)
./gradlew :core:test                   # 54 JVM tests, ~1 min
./gradlew :app:assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
```

## Modules

```
core/   pure Kotlin/JVM — all logic, tested on the VPS
  model/      OcrLine, RgbImage (platform-neutral image)
  layout/     reading order, raw text           (port of winelabel/layout.py)
  parse/      LabelParser, Reference, Text,     (port of winelabel/parser.py)
              SequenceMatcher (= Python difflib, for identical fuzzy scores)
  ocr/        OcrEngine interface, PpOcrEngine (port of rapidocr's pipeline),
              ImageOps + Geometry (replace OpenCV / pyclipper)
  Scanner.kt  image -> OCR -> noise filter -> reading order -> fields
app/    Android (Compose)
  Engines.kt        ONNX engine from assets, ML Kit adapter
  AndroidImages.kt  URI -> downsampled, EXIF-rotated RgbImage
  ScanViewModel.kt  state machine, runs scans off the main thread
  DebugReport.kt    JSON report
  ui/ScanScreen.kt  the single screen
```

`core` compiles against `ai.onnxruntime` from the desktop artifact
(`compileOnly`); the app supplies `onnxruntime-android`, which has the same API.

## Tests and parity with Python

| Test | What it checks |
|---|---|
| `ParserFixtureTest` | `../wine-label-py/fixtures/text` cases — the same files the Python tests use |
| `ParserTest`, `LayoutTest` | Mirrors `test_parser.py` / `test_layout.py` |
| `PpOcrParityTest` | Kotlin OCR on the 8 real photos in `../wine-label-py/fixtures/images`: accuracy floors, and OCR text vs. Python golden output (`core/src/test/resources/golden`) |

Current results on the 8 photos (VPS, 2 CPUs):

| | Kotlin (JVM) | Python, no CLAHE | Python, CLAHE |
|---|---|---|---|
| Fields correct | 31/37 | 32/37 | 30/37 |
| Text snippets found | 38/59 | 38/59 | 40/59 |
| OCR text similarity to Python | 0.971 | — | — |

Differences come from resampling details (bilinear/bicubic rounding), not logic.
The Kotlin pipeline skips CLAHE contrast enhancement: on these photos it traded
2 fields for 2 text snippets.

## Known gaps / not verifiable without a phone

- On-device speed and memory (estimate: 1–3 s per photo on a mid-range phone; the
  VPS JVM takes 1–3 s per photo).
- ML Kit engine quality and the camera/gallery flow — compiled and lint-clean, but
  only runnable on a device.
- APK is ~74 MB: models 32 MB, ONNX Runtime 18 MB, ML Kit 12 MB, unminified debug
  code ~10 MB. A release build with R8 would drop most of the code size; dropping
  one engine after comparison saves 12–18 MB.
- ML Kit's dependencies add INTERNET/ACCESS_NETWORK_STATE permissions (Google
  telemetry); OCR itself never needs the network.
- The recognizer's character list ships as `assets/models/ppocrv6_keys.txt`: it is
  also embedded in the ONNX model metadata, but ONNX Runtime's Java API mangles that
  UTF-8 string (bytes decoded individually and truncated), so it is extracted once
  with Python instead.
