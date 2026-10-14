# Project notes — Wine Label Scanner

Detailed record for resuming work. The short operational brief is [`AGENTS.md`](../AGENTS.md).

- [Status](#status-as-of-2026-10-14)
- [Roadmap](#roadmap)
- [Timeline](#timeline)
- [Architecture](#architecture)
- [Key decisions](#key-decisions)
- [Measurements](#measurements)
- [Gotchas](#gotchas-found-the-hard-way)
- [Adding a test case](#adding-a-test-case)
- [Maintenance recipes](#maintenance-recipes)

---

## Status (as of 2026-10-14)

| Area | State |
|---|---|
| Python prototype | Done. 57 tests pass. |
| Kotlin `core` | Done. 55 JVM tests pass, including OCR on the 8 real photos (parity with Python). |
| Android app | Builds (debug, arm64-v8a, 74 MB), lint clean. **Installed on the user's phone.** |
| On-device | The first run crashed every scan (`PatternSyntaxException`, ICU inline flags) — **fixed** in the current APK. **Awaiting the user's first real scan results with both engines.** |
| Repo | github.com/hukarere/wine-label-scanner (public). No LICENSE yet. APK not published as a Release. |

**Not yet verified anywhere:** on-device speed and memory, ML Kit engine quality, the
camera → TakePicture flow end to end, the share-into-app intent, dark theme look.

**Next expected input from the user:** debug reports (JSON) from scanning real bottles with
both engines → convert to fixtures → fix → decide which engine to keep.

## Roadmap

Prioritized; none started unless noted.

1. **Collect real-scan debug reports** → add as fixtures (see [Adding a test case](#adding-a-test-case)) → parser fixes in both languages.
2. **Choose an engine** (PP-OCR ONNX vs ML Kit) from on-device results; drop the other to cut 12–18 MB.
3. **Release build**: enable R8 (`isMinifyEnabled`, check ONNX Runtime keep rules), release signing key, smaller APK (~10 MB of debug dex goes away).
4. **Capture quality**: in-app guidance; multi-shot for curved labels (left/centre/right photos, merge lines by text similarity).
5. Producer extraction is the weakest field (decorative/script fonts). Ideas: prefer a producer-keyword line over the largest line more aggressively; use the photo's top third; fuzzy-join split rows.
6. Optional: CLAHE port to Kotlin (Python: +2 text snippets, −2 fields on the 8 photos — unclear benefit).
7. Optional: int8-quantized models (smaller/faster; must re-measure accuracy).
8. `armeabi-v7a` build if a 32-bit phone shows up (`INSTALL_FAILED_NO_MATCHING_ABIS`).
9. Remove ML Kit's merged `INTERNET`/`ACCESS_NETWORK_STATE` permissions (`tools:node="remove"`) — needs on-device check that ML Kit still works.
10. Repo hygiene: add a LICENSE (Apache-2.0 matches the models), publish the APK as a GitHub Release.

---

## Timeline

**2026-10-13**
1. User asked whether code could read all text on a wine label. Feasible: OCR; hard parts are curved labels, decorative fonts, glare.
2. Planned an Android Kotlin app (ML Kit). The user pointed out there's **no Android device** reachable from the VPS (also no KVM → no emulator) → decided to **prototype in Python first**, where everything can be tested.
3. Engine choice: ML Kit has no Python version, so a Python prototype couldn't predict its accuracy. Chose **PaddleOCR PP-OCR via ONNX Runtime (rapidocr)**, because the same `.onnx` files run on Android with `onnxruntime-android` → measured accuracy carries over.
4. Built the Python prototype (`python`): preprocessing, OCR wrapper, reading-order layout, regex/dictionary field parser, CLI, 8 hand-written OCR-line fixtures, synthetic-label OCR smoke test.
5. Tested on 8 real Wikimedia Commons photos (ground truth in `fixtures/images/expected.json`). Baseline 24/37 fields, 40/59 text snippets. A parameter sweep showed model size and detector thresholds barely matter; the misses were parser- or OCR-limited.
6. Parser improvements from the failures: glued multi-word names, classification split across two lines, prefix fuzzy match for truncated long names, country inferred from classification, producer cleanup and same-row merge, `750 MLS`, noise-line filter → 30/37 fields.
7. User asked about an APK. Plan: Kotlin `core` (pure JVM, testable here) + Compose `app`, **ONNX engine plus an ML Kit toggle** for comparison, delivery by scp/adb.
8. Installed JDK 17, Android SDK and Gradle in user space. Ported the parser (incl. a difflib clone) and the whole rapidocr pipeline (DBNet post-processing, perspective crops, cls, CTC) to Kotlin without OpenCV.
9. Found the ONNX Runtime Java metadata bug (all spaces were missing in the text) → ship `ppocrv6_keys.txt`. Added digit/letter splitting in `normalize` (both languages). Kotlin parity: 31/37 fields, 38/59 text, 0.971 text similarity to Python.
10. Built the debug APK and documented adb installation.

**2026-10-14**
11. User installed it; scans failed with a "java exception". `AndroidRuntime` logcat was empty because the ViewModel caught exceptions without logging → added `Log.e("WineLabel", …)` and a "Copy error details" button.
12. The log showed `PatternSyntaxException` for `(?U)`: Android uses **ICU** regex. Replaced `(?U)`/`(?iu)` and added `AndroidRegexCompatTest`. Rebuilt the APK (the user has not yet reported scan results with it).
13. Created the GitHub repo (models and fixtures committed, APKs gitignored); verified a fresh clone passes `:core:test`.
14. Added `AGENTS.md`, `CLAUDE.md` (imports AGENTS.md) and this file.
15. Renamed the subproject folders for consistency: `wine-label-py/` → `python/`, `WineLabelScanner/` → `android/` (the Gradle project name `WineLabelScanner` and Python package `winelabel` are unchanged).
16. Tried a headless **ARM64 emulator under software emulation** (no KVM) to test without the user. Emulator ≥ 31 refuses arm64 images on an x86_64 host; 30.4.5 boots the Android 10 (API 29) `google_apis` image, but `system_server` crash-loops (SIGABRT) because the guest kernel binds no input device (virtio-input over MMIO needs a modern virtio transport this qemu lacks; `goldfish-events` has no driver). **Abandoned and deleted** (emulator, system images, AVDs). Next approach: drive the user's real phone (Android 10) from the VPS through an SSH reverse tunnel to their local adb server (`ssh -N -R 5038:localhost:5037 grg@<vps>` on their computer, then `adb -P 5038 …` here).

---

## Architecture

### Pipeline (same in Python and Kotlin)

```
photo
 └─ prepare: EXIF-rotate (app/PIL), downscale long side ≤ 2000 (area); Python optionally CLAHE
     └─ OCR engine → list of OcrLine(text, confidence, 4-point box)
          PP-OCR (rapidocr / PpOcrEngine):
            bound sides (≤2000, ≥30, multiples of 32) → vertical padding if very wide
            → DET: DBNet, resize so short side ≥ 736 (×32), (x/255−0.5)/0.5, BGR, NCHW
              → prob map > 0.3 → 2×2 dilation → connected components → min-area rect
              → mean prob in box ≥ 0.5 → unclip ×1.6 → scale back → sort rows (Δy ≥ 10)
            → crop each box (perspective warp, bicubic; rotate 90° if h/w ≥ 1.5)
            → CLS (48×192): flip 180° if p(180) > 0.9
            → REC (48×W, batches of 6 sorted by aspect): greedy CTC, blank = 0, last = space
            → drop empty or score < 0.5
          ML Kit (app only): TextRecognition Latin, lines → OcrLine
     └─ noise filter: drop lines with ≤ 2 chars and confidence < 0.7
     └─ reading order: rows by vertical-centre overlap, then left→right; raw text = rows joined
     └─ parser → WineLabel fields, each = (value, confidence, source line)
```

### Parser (`parser.py` / `LabelParser.kt`)

- `normalize`: collapse dotted abbreviations (D.O.C.→doc), NFKD strip accents, lowercase, non-`[a-z0-9%]`→space, split digit runs from ≥3-letter runs.
- `match_names` over reference lists (longest names first), three passes on unclaimed words: exact word sequence → glued token (multi-word name ≥ 8 chars inside one token) → fuzzy (difflib ratio ≥ 0.88; ≥ 0.80 for names ≥ 12 chars, also vs. the name's prefix ×0.95 for truncation; names < 6 chars exact only).
- Extractors: ABV (3 regex tiers, 4.5–23 %), volume (ml/cl/l → ml, 100–6000, common sizes trusted), vintage (1950–2049, ≤ current year, skip founding/classification lines, prefer standalone/large/"vintage"-hinted), classification (ordered pattern list with blanking + adjacent-line pairs that must cross the break; strict case-sensitive `DO`), appellation ("Appellation X Contrôlée" phrase, else region names near markers/more words/larger text), grapes, country (phrase → any name → appellation's country → classification's country), producer (keyword bonus + text height, excluding bottling/legal/founding lines and lines fully explained by other fields; merges same-row pieces; strips ®/@ noise).

### Kotlin modules

| Python | Kotlin (`android/core/src/main/kotlin/winelabel/`) |
|---|---|
| `model.py` | `model/OcrLine.kt`, `model/RgbImage.kt` (0xRRGGBB ints) |
| `layout.py` | `layout/Layout.kt` |
| `parser.py` | `parse/LabelParser.kt`, `parse/Text.kt`, `parse/Reference.kt`, `parse/SequenceMatcher.kt` |
| rapidocr (library) | `ocr/PpOcrEngine.kt`, `ocr/ImageOps.kt` (resize, warp, rotations), `ocr/Geometry.kt` (components, hull, min-area rect, unclip, box score) |
| `ocr.py` interface | `ocr/OcrEngine.kt` |
| `scanner.py` | `Scanner.kt` |

`core` compiles against `com.microsoft.onnxruntime:onnxruntime` (`compileOnly`, desktop); the
app supplies `onnxruntime-android` — same `ai.onnxruntime` API.

### App (`android/app/src/main/kotlin/winelabel/app/`)

- `MainActivity` — Compose host; accepts `ACTION_SEND image/*` (share a photo into the app).
- `ScanViewModel` — `UiState` Idle / Working / Done(result, timings) / Error(message, details); engine choice persisted in SharedPreferences; keeps the last image for "Re-scan with other engine"; logs errors with tag `WineLabel`.
- `Engines` — lazily loads the ONNX models from `assets/models/` (+ keys file); `MlKitEngine` adapter.
- `AndroidImages` — URI → downsampled decode (long side kept ≥ 2000) → EXIF rotation → `RgbImage`.
- `ui/ScanScreen` — engine chips, Take photo (`TakePicture` + FileProvider → **no camera permission**), Choose photo (`PickVisualMedia`), photo with box overlay, fields with confidence (faded < 50 %), raw text, Copy text / Share / Copy debug report / Re-scan.
- `DebugReport` — JSON: app version, device, engine, image size, total and per-stage ms, raw text, fields, lines with boxes.

### Tests

| Suite | Count | What |
|---|---|---|
| Python `tests/` | 57 | parser fixtures + units, layout, synthetic-label OCR smoke test, real-photo accuracy floors |
| Kotlin `core/src/test/` | 55 | same parser fixtures (read from `../python/fixtures`), units mirroring Python, OCR parity on 8 photos vs. Python goldens, ICU regex guard |

---

## Key decisions

| Decision | Why |
|---|---|
| Python prototype first | No device or emulator on the VPS; Python can be tested end to end here. |
| No emulator | No KVM; ARM64 images under software emulation crash-loop (no input devices in the guest kernel). Test on the user's phone over an adb tunnel instead. |
| PP-OCR via ONNX (rapidocr) | Same model files in Python and on Android → prototype accuracy carries over. ML Kit has no Python. |
| PP-OCRv6 **small** | `medium` was no more accurate on the photos and 4× slower. One multilingual model handles accents (é, ô, ü). |
| ML Kit kept as a toggle | To compare on real bottles before committing to one engine. |
| No OpenCV on Android | Would add ~30 MB; the needed geometry is ~300 lines of Kotlin. |
| No CLAHE in Kotlin | On the 8 photos CLAHE traded 2 fields for 2 text snippets — no clear win. |
| arm64-v8a only | Covers essentially all current phones; halves native-library size. |
| `TakePicture` intent instead of CameraX | No camera permission and no preview code; the system camera does the work. |
| Models committed to git | Clone-and-build simplicity; each file < 100 MB. |
| Shared fixtures, not copied | Kotlin tests read `../python/fixtures` so both languages always test the same cases. |

## Measurements

8 real photos (`python/fixtures/images`), 37 expected fields, 59 expected text snippets. VPS, 2 CPUs.

| Pipeline | Fields | Text snippets | Notes |
|---|---|---|---|
| Python, before parser improvements | 24/37 | 40/59 | baseline |
| Python, CLAHE (default) | 30/37 | 40/59 | ~2 s/photo |
| Python, `--no-enhance` | 32/37 | 38/59 | |
| Kotlin `core` on the JVM | 31/37 | 38/59 | OCR text similarity to Python 0.971; 1–3 s/photo |

Parameter sweep (Python, before the later parser fixes): medium model 24/37 and 37/59 at 4× the time; detector `limit_side_len` 960/1280 and lower thresholds changed results by ±1–2; upscaling small photos helped only one image.

Remaining misses are OCR-limited: script fonts (Château Poujeaux), text cut off at the photo edge (Sainte Magdeleine), small print on curved labels, embossed glass.

APK (debug): 74 MB = models 32 MB (det 9.9, rec 21.2, cls 0.6) + ONNX Runtime 18 MB + ML Kit ~12 MB + unminified dex ~10 MB.

## Gotchas found the hard way

- **ONNX Runtime Java API mangles UTF-8 model metadata** (bytes decoded one by one and truncated: 18,166 entries instead of 18,708; spaces were lost because the space class index shifted). Fix: `ppocrv6_keys.txt`.
- **Android regex is ICU**: `(?U)` / `(?iu)` compile on the JVM but throw `PatternSyntaxException` on Android, inside `LabelParser.<clinit>` → `NoClassDefFoundError` for every scan.
- **Caught exceptions are invisible** in `adb logcat AndroidRuntime:E` — that tag only logs crashes.
- **Kotlin nests block comments**: `fixtures/text/*.json` inside KDoc broke compilation.
- **rapidocr 3.x** requires enum values (`OCRVersion.PPOCRV6`, `ModelType`) for model params, not strings. Its default PP-OCRv6 model is multilingual; `lang_type="latin"` is rejected for v6.
- **PP-OCR models expect BGR** channel order (rapidocr feeds OpenCV images).
- The multilingual model "reads" ornaments as CJK characters → the short-low-confidence noise filter.
- **Gradle** needs `JAVA_HOME`; a failed `:core:test` skips `:app:assembleDebug` in the same run.
- `gradle-wrapper.jar` is committed; `local.properties` is not (set `ANDROID_HOME` in fresh clones).

---

## Adding a test case

**From a debug report** (no photo): create `python/fixtures/text/<name>.json`:

```json
{
  "description": "what is special about this label",
  "lines": [["CHÂTEAU X", 72], ["PAUILLAC", 34], ["2016", 40, 0.97]],
  "expected": {"producer": "Château X", "vintage": 2016, "appellation": "Pauillac"}
}
```

`lines` are `[text, height(, confidence)]` in reading order — take `text`, `height` and
`confidence` from the report's `lines`. Expected keys: `producer, vintage, appellation,
classification (list), grapes (list), abv, volume_ml, country`; omit a key to not check it,
use `null` to require "not found". Both `pytest` and `./gradlew :core:test` pick it up automatically.

**From a photo**: add the image to `python/fixtures/images/`, an entry in
`expected.json` (fields plus `_text`: normalized snippets — lowercase, no accents or
punctuation), and a row in `ATTRIBUTION.md` (only freely licensed photos; this repo is
public). Then regenerate the Kotlin golden (below). Accuracy floors live in
`tests/test_images.py` (Python) and `PpOcrParityTest.kt` (Kotlin).

## Maintenance recipes

Regenerate Kotlin goldens after changing photos or the Python OCR:

```bash
cd python
for f in fixtures/images/*.jpg; do b=$(basename "$f" .jpg)
  .venv/bin/python -m winelabel scan "$f" --json --no-enhance \
    > ../android/core/src/test/resources/golden/$b.json
done
```

Regenerate the recognizer alphabet after changing the rec model:

```bash
cd android
../python/.venv/bin/python -c "
import onnxruntime as ort
m = ort.InferenceSession('app/src/main/assets/models/PP-OCRv6_rec_small.onnx').get_modelmeta().custom_metadata_map['character']
open('app/src/main/assets/models/ppocrv6_keys.txt', 'w', encoding='utf-8', newline='\n').write(m)"
```

Models come from rapidocr's cache: `python/.venv/lib/python3*/site-packages/rapidocr/models/`
(downloaded on first `winelabel scan`).

Toolchain reinstall (user space): Temurin JDK 17 → `~/opt/jdk-17`; Android cmdline-tools →
`~/Android/Sdk/cmdline-tools/latest`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
