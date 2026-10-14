# Wine Label Scanner

Read a wine bottle label from a photo: **all the text on it** plus **parsed fields**
(producer, vintage, appellation, classification, grapes, alcohol %, volume, country).
OCR runs fully on-device with PaddleOCR's PP-OCRv6 models on ONNX Runtime.

| Directory | What |
|---|---|
| [`android/`](android/README.md) | Android app (Kotlin, Compose). `core/` holds the whole recognition pipeline as plain Kotlin, so it also runs and is tested on a desktop JVM |
| `fixtures/` | Test cases: OCR-line cases (`text/`), 8 labelled real photos (`images/`), ground truth for a local-only retailer photo set |
| `tools/` | `fetch_vinmonopolet.py`: downloads the local-only retailer photo set (stdlib Python) |
| [`docs/`](docs/PROJECT_NOTES.md) | Project notes: status, roadmap, decisions, measurements |

The project started with a Python prototype (rapidocr); it was retired once the Kotlin
port matched it (see the notes). It is in the git history before the retirement commit.

## Quick start

```bash
cd android                             # JDK 17 + Android SDK
./gradlew :core:test                   # parser, layout and OCR tests on the JVM
./gradlew -q :core:cli --args="scan fixtures/images/filhot_2008.jpg"   # read one photo
./gradlew -q :core:cli --args="eval fixtures/images"                   # accuracy on a folder
./gradlew :app:assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
```

## Working on this project

Start with [`AGENTS.md`](AGENTS.md) (environment, commands, rules; Claude Code loads it via
`CLAUDE.md`). History, decisions, measurements, status and roadmap are in
[`docs/PROJECT_NOTES.md`](docs/PROJECT_NOTES.md).

## Third-party content

- OCR models in `android/app/src/main/assets/models/`: PaddleOCR PP-OCRv6 and
  PP-OCR text orientation classifier (Apache License 2.0), ONNX conversions distributed
  by [RapidOCR](https://github.com/RapidAI/RapidOCR).
- Test photos in `fixtures/images/`: Wikimedia Commons, CC BY-SA / public
  domain; authors and licenses in [`ATTRIBUTION.md`](fixtures/images/ATTRIBUTION.md).
- `fixtures/vinmonopolet.expected.json`: facts and short text read from product photos
  of Vinmonopolet (vinmonopolet.no); the photos themselves are not in the repository.
