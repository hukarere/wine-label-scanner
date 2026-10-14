# Wine Label Scanner

Read a wine bottle label from a photo: **all the text on it** plus **parsed fields**
(producer, vintage, appellation, classification, grapes, alcohol %, volume, country).
OCR runs fully on-device with PaddleOCR's PP-OCRv6 models on ONNX Runtime.

| Directory | What |
|---|---|
| [`python/`](python/README.md) | Python prototype: OCR pipeline (rapidocr), field parser, CLI, accuracy evaluation on real photos |
| [`android/`](android/README.md) | Android app (Kotlin, Compose): Kotlin port of the pipeline + parser, with an ML Kit engine for comparison |

Both share the test fixtures in `python/fixtures/` (OCR-line cases and 8 labelled
real photos), so the Python and Kotlin parsers are checked against the same expectations.

## Quick start

```bash
# Python prototype
cd python
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/winelabel scan photo.jpg

# Android app (JDK 17 + Android SDK)
cd android
./gradlew :core:test          # parser + OCR parity tests on the JVM
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## Working on this project

Start with [`AGENTS.md`](AGENTS.md) (environment, commands, rules; Claude Code loads it via
`CLAUDE.md`). History, decisions, measurements, status and roadmap are in
[`docs/PROJECT_NOTES.md`](docs/PROJECT_NOTES.md).

## Third-party content

- OCR models in `android/app/src/main/assets/models/`: PaddleOCR PP-OCRv6 and
  PP-OCR text orientation classifier (Apache License 2.0), ONNX conversions distributed
  by [RapidOCR](https://github.com/RapidAI/RapidOCR).
- Test photos in `python/fixtures/images/`: Wikimedia Commons, CC BY-SA / public
  domain; authors and licenses in [`ATTRIBUTION.md`](python/fixtures/images/ATTRIBUTION.md).
