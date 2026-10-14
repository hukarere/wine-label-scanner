# Wine Label Scanner

Read a wine bottle label from a photo: **all the text on it** plus **parsed fields**
(producer, vintage, appellation, classification, grapes, alcohol %, volume, country).
OCR runs fully on-device with PaddleOCR's PP-OCRv6 models on ONNX Runtime.

| Directory | What |
|---|---|
| [`wine-label-py/`](wine-label-py/README.md) | Python prototype: OCR pipeline (rapidocr), field parser, CLI, accuracy evaluation on real photos |
| [`WineLabelScanner/`](WineLabelScanner/README.md) | Android app (Kotlin, Compose): Kotlin port of the pipeline + parser, with an ML Kit engine for comparison |

Both share the test fixtures in `wine-label-py/fixtures/` (OCR-line cases and 8 labelled
real photos), so the Python and Kotlin parsers are checked against the same expectations.

## Quick start

```bash
# Python prototype
cd wine-label-py
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/winelabel scan photo.jpg

# Android app (JDK 17 + Android SDK)
cd WineLabelScanner
./gradlew :core:test          # parser + OCR parity tests on the JVM
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## Third-party content

- OCR models in `WineLabelScanner/app/src/main/assets/models/`: PaddleOCR PP-OCRv6 and
  PP-OCR text orientation classifier (Apache License 2.0), ONNX conversions distributed
  by [RapidOCR](https://github.com/RapidAI/RapidOCR).
- Test photos in `wine-label-py/fixtures/images/`: Wikimedia Commons, CC BY-SA / public
  domain; authors and licenses in [`ATTRIBUTION.md`](wine-label-py/fixtures/images/ATTRIBUTION.md).
