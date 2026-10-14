# AGENTS.md — working on the Wine Label Scanner

Read this first. Detailed history, measurements, decisions and roadmap are in
[`docs/PROJECT_NOTES.md`](docs/PROJECT_NOTES.md) — read its **Status** and **Roadmap**
sections before starting new work.

## What this is

Photo of a wine bottle → all text on the label + parsed fields (producer, vintage,
appellation, classification, grapes, ABV, volume, country). On-device OCR with
PaddleOCR PP-OCRv6 models on ONNX Runtime. One implementation, in Kotlin; it runs both
in the Android app and on the desktop JVM (tests, CLI). The original Python prototype
was retired (PROJECT_NOTES timeline 20); it is in git history.

```
android/              Gradle project
  core/               pure Kotlin/JVM: the whole OCR pipeline + layout + parser
    src/cli/          desktop CLI (scan / eval) + Evaluate (scoring shared with the tests)
    src/main/resources/winelabel/data/   grapes.txt, regions.txt (name|country), countries.txt
  app/                Compose UI, ONNX + ML Kit engines, image loading, memory budget, debug report
  tools/device-test.sh  run DeviceScanTest on the user's phone, record peak memory
fixtures/
  text/               OCR-line test cases (JSON)
  images/             8 labelled Wikimedia photos + expected.json + ATTRIBUTION.md
  vinmonopolet/       26 retailer packshots, LOCAL ONLY (copyrighted, gitignored) — tools/fetch_vinmonopolet.py
  vinmonopolet.expected.json  its hand-checked ground truth (committed; the folder's expected.json links here)
tools/fetch_vinmonopolet.py   recreate the local-only set (python3, stdlib only)
docs/PROJECT_NOTES.md history, architecture, decisions, numbers, gotchas, roadmap
```

## Environment (the dev VPS)

- **No Android device and no emulator** (no KVM; an ARM64 emulator under software emulation
  was tried and fails — PROJECT_NOTES timeline 16). Everything testable runs on the JVM here,
  plus the user's real phone when they open the adb tunnel (below).
- JDK 17: `export JAVA_HOME=~/opt/jdk-17` (not on PATH by default — Gradle fails without it).
- Android SDK: `~/Android/Sdk` (referenced by `android/local.properties`, gitignored;
  in a fresh clone set `ANDROID_HOME=~/Android/Sdk`).
- Git: remote `git@github.com:hukarere/wine-label-scanner.git` (public); the repo's
  `core.sshCommand` uses `~/.ssh/github-hukarere`. No `gh` CLI.

## Commands (from android/)

```bash
export JAVA_HOME=~/opt/jdk-17
./gradlew :core:test                                  # 63 tests, ~1 min (incl. OCR on 8 photos)
./gradlew -q :core:cli --args="scan fixtures/images/filhot_2008.jpg [--json] [--debug-image /tmp/b.png]"
./gradlew -q :core:cli --args="eval fixtures/images [--only a.jpg,b.jpg] [--dump dir]"   # paths from repo root
./gradlew :core:test --tests winelabel.PpOcrParityTest -Pphotos=vinmonopolet [-Pdump=dir] --rerun
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest [-Pphotos=vinmonopolet]   # on-device test APK
./gradlew :app:lintDebug
cp app/build/outputs/apk/debug/app-debug.apk /home/grg/android/wine-label-scanner-debug.apk  # gitignored
```

Gradle prints little with `-q`; read test results from `core/build/test-results/test/*.xml`.
If `:core:test` fails, `assembleDebug` in the same invocation does **not** run — don't
copy a stale APK. Fixtures aren't Gradle task inputs: after editing them use `--rerun`.

## Rules / invariants

1. **Measure every recognition change on both photo sets** (`cli eval fixtures/images` and
   `fixtures/vinmonopolet`) and record the numbers in PROJECT_NOTES. Pipeline changes that
   shouldn't change results must produce identical `--dump` output before/after.
2. **Android regexes run on ICU, not java.util.regex**: only inline flags `i m s x w` are
   allowed. Use `RegexOption.IGNORE_CASE` instead of `(?iu)`, `\p{L}\p{N}_` instead of `(?U)\w`.
   `AndroidRegexCompatTest` guards this; JVM tests can't otherwise catch it.
3. **Recognizer alphabet** ships as `app/src/main/assets/models/ppocrv6_keys.txt`. ONNX
   Runtime's Java API mangles the UTF-8 `character` metadata inside the model, so never read
   it from the model. Regenerate the file if the rec model changes (see PROJECT_NOTES).
4. **rapidocr compatibility**: the pipeline is a port of rapidocr (Python). Its numeric
   details are reproduced on purpose: Python `round()` is half-even → `Math.rint`; `difflib`
   ratio in `parse/SequenceMatcher.kt`; Python `max()` tie-breaking via explicit comparators;
   model input channels are **BGR**. No CLAHE. The golden OCR output in
   `core/src/test/resources/golden/` is frozen Python output (`PpOcrParityTest` checks similarity).
5. In the app, every caught exception must be logged with `Log.e("WineLabel", …)` and shown
   via `UiState.Error(message, details)` — logcat's `AndroidRuntime` tag only shows crashes.
6. KDoc/comments: don't write `/*` inside `/** */` (Kotlin nests comments → "Unclosed comment"),
   e.g. no `fixtures/text/*.json` in a doc comment.
7. No locale-dependent number formatting in data (`"%.2f".format(x).toDouble()` breaks in ru/de/fr).
8. Memory: the user's phone has 2.7 GB RAM. The app's ONNX engine uses `Config(lowMemory = true)`
   and `AppScan` sets `detMaxPixels` from free memory before each scan (PROJECT_NOTES "Memory on
   the phone"). Check peak memory with `tools/device-test.sh` after pipeline changes.
9. Never commit `fixtures/vinmonopolet/` (copyrighted retailer photos). Its ground truth
   `fixtures/vinmonopolet.expected.json` is committed; the shop's metadata is not ground truth.

## Feedback loop with the user (no device here)

- **Preferred:** the user opens an SSH reverse tunnel to their adb server
  (`ssh -N -R 5038:localhost:5037 grg@<vps>`); then `adb -P 5038 …` here reaches their phone
  (Android 10, 2.7 GB RAM). Install, run `android/tools/device-test.sh`, read logcat yourself —
  see PROJECT_NOTES "Testing on the user's phone". Check with `adb -P 5038 devices` first.
- **It is the user's own phone**: instrumentation tests run in the background, but don't open
  the app's UI or push files without asking — they may be using the phone (check
  `dumpsys power | grep mWakefulness`). Remove anything you put on the phone afterwards.
- Otherwise the user installs: `adb install -r wine-label-scanner-debug.apk` (they scp it from the VPS).
- They send back one of: the app's **Copy debug report** JSON (engine, timings, detector size,
  every OCR line with box/confidence, fields), **Copy error details** (stack trace), or
  `adb logcat WineLabel:E AndroidRuntime:E '*:S'`.
- **Turn every bad scan into a test case before fixing it** (how-to in PROJECT_NOTES:
  "Adding a test case").

## When finishing a piece of work

Update `docs/PROJECT_NOTES.md` (Status, Roadmap, and Measurements if numbers changed),
commit, and push. Rebuild and copy the APK if app behaviour changed, and tell the user.
