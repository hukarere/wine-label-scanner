# AGENTS.md — working on the Wine Label Scanner

Read this first. Detailed history, measurements, decisions and roadmap are in
[`docs/PROJECT_NOTES.md`](docs/PROJECT_NOTES.md) — read its **Status** and **Roadmap**
sections before starting new work.

## What this is

Photo of a wine bottle → all text on the label + parsed fields (producer, vintage,
appellation, classification, grapes, ABV, volume, country). On-device OCR with
PaddleOCR PP-OCRv6 models on ONNX Runtime.

```
python/               Python prototype (reference implementation, CLI, evaluation)
  winelabel/          preprocess, ocr (rapidocr), layout, parser, scanner, evaluate, cli
  winelabel/data/     grapes.txt, regions.txt (name|country), countries.txt (name|aliases)
  fixtures/text/      OCR-line test cases (shared with Kotlin)
  fixtures/images/    8 labelled real photos + expected.json + ATTRIBUTION.md (shared)
android/              Android app (Gradle)
  core/               pure Kotlin/JVM: port of parser + layout + the whole OCR pipeline
  app/                Compose UI, ONNX + ML Kit engines, image loading, debug report
docs/PROJECT_NOTES.md history, architecture, decisions, numbers, gotchas, roadmap
```

## Environment (the dev VPS)

- **No Android device and no emulator** (no KVM; an ARM64 emulator under software emulation was tried and fails — see PROJECT_NOTES timeline 16). Everything testable runs on the JVM or
  in Python here. The user installs APKs on their own phone and reports back.
- JDK 17: `export JAVA_HOME=~/opt/jdk-17` (not on PATH by default — Gradle fails without it).
- Android SDK: `~/Android/Sdk` (referenced by `android/local.properties`, gitignored;
  in a fresh clone set `ANDROID_HOME=~/Android/Sdk`).
- Python: `python/.venv` (create with `python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`).
- Git: remote `git@github.com:hukarere/wine-label-scanner.git` (public); the repo's
  `core.sshCommand` uses `~/.ssh/github-hukarere`. No `gh` CLI.

## Commands

```bash
# Python (from python/)
.venv/bin/pytest -q                                   # 57 tests, ~25 s
.venv/bin/winelabel scan photo.jpg [--json] [--debug-image boxes.png] [--no-enhance]
.venv/bin/winelabel eval fixtures/images [--no-enhance]   # per-field accuracy

# Android (from android/)
export JAVA_HOME=~/opt/jdk-17
./gradlew :core:test                                  # 55 tests, ~1 min (incl. OCR on 8 photos)
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
cp app/build/outputs/apk/debug/app-debug.apk /home/grg/android/wine-label-scanner-debug.apk  # gitignored
```

Gradle prints little with `-q`; read results from `core/build/test-results/test/*.xml`.
If `:core:test` fails, `assembleDebug` in the same invocation does **not** run — don't
copy a stale APK.

## Rules / invariants

1. **The parser exists twice and must stay in sync**:
   `python/winelabel/parser.py` ↔ `android/core/src/main/kotlin/winelabel/parse/LabelParser.kt`,
   `normalize()` ↔ `parse/Text.kt`, `layout.py` ↔ `layout/Layout.kt`, `scanner.is_noise` ↔ `Scanner.isNoise`.
   Change both, run both test suites. Data lists are copied:
   `python/winelabel/data/*.txt` → `android/core/src/main/resources/winelabel/data/` (keep identical).
2. **Android regexes run on ICU, not java.util.regex**: only inline flags `i m s x w` are
   allowed. Use `RegexOption.IGNORE_CASE` instead of `(?iu)`, `\p{L}\p{N}_` instead of `(?U)\w`.
   `AndroidRegexCompatTest` guards this; JVM tests can't otherwise catch it.
3. **Recognizer alphabet** ships as `app/src/main/assets/models/ppocrv6_keys.txt`. ONNX
   Runtime's Java API mangles the UTF-8 `character` metadata inside the model, so never read
   it from the model. Regenerate the file if the rec model changes (see PROJECT_NOTES).
4. **Python parity details**: `round()` is half-even → `Math.rint`; `difflib` ratio is
   re-implemented in `parse/SequenceMatcher.kt`; Python `max()` tie-breaking is reproduced
   with explicit comparators. Model input channels are **BGR**.
5. The Kotlin pipeline does **not** apply CLAHE; Python goldens in
   `core/src/test/resources/golden/` come from `winelabel scan --json --no-enhance`.
6. In the app, every caught exception must be logged with `Log.e("WineLabel", …)` and shown
   via `UiState.Error(message, details)` — logcat's `AndroidRuntime` tag only shows crashes.
7. KDoc/comments: don't write `/*` inside `/** */` (Kotlin nests comments → "Unclosed comment").

## Feedback loop with the user (no device here)

- User installs: `adb install -r wine-label-scanner-debug.apk` (they scp it from the VPS).
- They send back one of: the app's **Copy debug report** JSON (engine, timings, every OCR
  line with box/confidence, fields), **Copy error details** (stack trace), or
  `adb logcat WineLabel:E AndroidRuntime:E '*:S'`.
- **Turn every bad scan into a test case before fixing it** (how-to in PROJECT_NOTES:
  "Adding a test case"), then fix in both parsers.

## When finishing a piece of work

Update `docs/PROJECT_NOTES.md` (Status, Roadmap, and Measurements if numbers changed),
commit, and push. Rebuild and copy the APK if app behaviour changed, and tell the user.
