# Project notes — Wine Label Scanner

Detailed record for resuming work. The short operational brief is [`AGENTS.md`](../AGENTS.md).

- [Status](#status-as-of-2026-10-14)
- [Roadmap](#roadmap)
- [Timeline](#timeline)
- [Architecture](#architecture)
- [Key decisions](#key-decisions)
- [Measurements](#measurements)
- [Gotchas](#gotchas-found-the-hard-way)
- [Vinmonopolet test set](#vinmonopolet-test-set)
- [Testing on the user's phone](#testing-on-the-users-phone)
- [Adding a test case](#adding-a-test-case)
- [Maintenance recipes](#maintenance-recipes)

---

## Status (as of 2026-10-14)

| Area | State |
|---|---|
| Python prototype | Done. 57 tests pass. |
| Kotlin `core` | Done. 55 JVM tests pass, including OCR on the 8 real photos (parity with Python). |
| Android app | Builds (debug, arm64-v8a, 74 MB), lint clean. Installed on the user's phone (Honor 10 Lite HRY-LX1, Android 10, **2.7 GB RAM**, ru locale). |
| On-device | Tested directly from the VPS over an adb tunnel ([Testing on the user's phone](#testing-on-the-users-phone)). `DeviceScanTest` runs both engines on the 8 photos. Fixed: ICU regex crash, locale crash in the debug report. PP-OCR memory: fixed by cutting image copies (bit-identical output) and a **free-memory budget that caps the detector input only when needed** — all 8 Commons photos now run in one process (31/37, was killed on the 5th). |
| Repo | github.com/hukarere/wine-label-scanner (public). No LICENSE yet. APK not published as a Release. |

**Not yet verified anywhere:** the camera → TakePicture flow end to end, the
share-into-app intent, dark theme look, the UI flow driven by taps.

**Test sets:** 8 committed Commons photos + 26 local-only Vinmonopolet packshots (PP-OCR 77/108 vs ML Kit 69/108 on those).

**Next:** producer extraction in the parser (PP-OCR reads most producers but the parser picks or
splits the wrong line), missing regions/grapes, then real-bottle scans → fixtures → choose the engine.

## Roadmap

Prioritized; none started unless noted.

0. ~~PP-OCR memory on 2–3 GB phones~~ — done (budgeted detector cap); watch `det_capped` in debug reports from real use.
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
17. Phone over the tunnel works (both adb builds speak protocol 41: VPS 37.0.1, user's Debian 34.0.4). Restored `DeviceScanTest` (androidTest; both engines on the 8 fixture photos through the app's own code) and added `android/tools/device-test.sh`. First on-device run found: (a) `DebugReport` used `"%.2f".format(x).toDouble()` → `NumberFormatException: "0,58"` in the ru locale — fixed with locale-free rounding; (b) PP-OCR process **killed by the low-memory killer** on the 5th photo. Measured memory, tried ONNX Runtime options (`PpOcrEngine.Config.lowMemory`: no arena, no memory pattern — small gain), detector input caps and tiled detection (see [Memory on the phone](#memory-on-the-phone)). ML Kit on the phone: 22/37 fields, 39/59 text, 412 MB peak.
18. The 8 Commons photos aren't representative. Built a second, **local-only** set of 26 Vinmonopolet packshots (`python/tools/fetch_vinmonopolet.py`, gitignored output) with hand-checked ground truth (committed as `python/fixtures/vinmonopolet.expected.json`): 8 of the shop's vintages didn't match the label, producers are often company names, ABV/volume usually sit on the back label. PP-OCR (JVM and phone, identical) 77/108 fields, ML Kit 69/108; they differ mainly on producer (10 vs 4 of 24). `PpOcrParityTest` and the androidTest APK take `-Pphotos=<folder>`.
19. PP-OCR memory work. Lighter detectors (v6 tiny, v5/v4 mobile) tie on Vinmonopolet but lose 7–9 fields on Commons and save at most ~150 MB → rejected. Cut Java copies: `Scanner.prepare` resizes one channel at a time from the packed pixels; the detector tensor is resized+normalized straight into a direct buffer that ORT uses without copying — **bit-identical OCR output on all 34 photos** (`-Pdump` diff), peaks −40…80 MB. Added `PpOcrEngine.detMaxPixels` (default 0 = off, so JVM/Python parity is unchanged) and `AppScan`/`DeviceMemory`: before each ONNX scan the app caps the detector input to (availMem − threshold − 64 MB) / 170 B, min 1 MP. Debug report gains `det_input`, `det_capped`, `det_max_pixels`, `avail_mem_mb`; the UI says "reduced detail: low memory".

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

**Vinmonopolet set** (26 studio packshots, ~600–780×2000 px, two 1530–1554×2000; 108 fields, 140 snippets — local only, see [Vinmonopolet test set](#vinmonopolet-test-set)):

| Pipeline | Fields | Text | Producer | ABV / volume | Time | Peak RSS (phone) |
|---|---|---|---|---|---|---|
| Python PP-OCR `--no-enhance` | 74/108 | 117/140 | 7/24 | | 1.4 s/photo (VPS) | |
| Kotlin PP-OCR, JVM | 77/108 | 123/140 | 10/24 | 2/5, 1/3 | 1.2 s/photo (VPS) | |
| Kotlin PP-OCR, phone (one photo per process) | **77/108** | **123/140** | 10/24 | 2/5, 1/3 | 5.4 s/photo | median 449 MB, max 782 MB (the two 1554-wide photos) |
| ML Kit, phone | 69/108 | 104/140 | 4/24 | 0/5, 0/3 | 0.4 s/photo | 304 MB |

Per field (PP-OCR / ML Kit): vintage 14/14 both, appellation 14/18 both, grapes 13/16 both,
classification 5/7 both, country 18/21 vs 19/21. Producer misses differ in kind: ML Kit misreads
characters ("Mugao", "Jranco ontwne", "VIGNETI REPETT0"); PP-OCR reads them but the parser picks
or splits the wrong line ("Chäteau" without "Doisy Daëne", "SCHLOSS", "Philippe", the slogan on
Squealing Pig) — parser work. Appellations missing from `regions.txt`: Patagonia, Mornington
Peninsula, Anderson Valley; grapes missing: Refosco, Traminer.

APK (debug): 74 MB = models 32 MB (det 9.9, rec 21.2, cls 0.6) + ONNX Runtime 18 MB + ML Kit ~12 MB + unminified dex ~10 MB.

### Memory on the phone

User's phone: 2.7 GB RAM, ~760 MB available with the usual apps open; the low-memory killer
SIGKILLs the scan process (no Java or native crash log — look for `Process … exited due to signal 9`
and falling `am_low_memory` counts in `adb logcat -b main,system`).

| Engine / input | Peak RSS (VmHWM) | Notes |
|---|---|---|
| PP-OCR, filhot (370×640 → det 736×1280) | 480 MB → **355 MB** with `lowMemory` | |
| PP-OCR, schramsberg (det 1344×2016, 2.7 MP) | 746 MB → **699 MB** with `lowMemory` | native heap 439 MB, Java heap 202 MB |
| PP-OCR, poujeaux (det 1504×2016, 3.0 MP) | killed | a 13 MP camera photo gives the same det size |
| ML Kit, all 8 photos | 412 MB | 22/37 fields, 39/59 text, 15 s total |

The detector needs ~150 B per input pixel of native working memory (desktop ORT 1.31,
1504×2016: +450–530 MB whatever the arena / memory-pattern / optimization-level / thread settings).

Tried and rejected (Python, `--no-enhance`, full-res baseline 32/37 fields, 38/59 text):
- **Cap the detector input's long side** (crops for recognition stay full-res): 1536 → 26/37, 1280 → 25/37, 1024 → 27/37, 960 → 27/37. Losses: schramsberg producer "Sthransbeng", ABV, volume, appellation; sainte_magdeleine appellation, country.
- **Tiled detection** (overlapping tiles of the det tensor, stitched probability map): 1024/64 → 31/37 but only 1/8 identical raw text; 768/96 → 21/37. The model uses global context, so tiles don't reproduce the full-image output.

Also rejected: **lighter detector models** (rapidocr `Det.model_type`/`Det.ocr_version`, rec unchanged):

| Detector | Commons fields | Vinmonopolet fields | Det. memory at 1504×2016 (desktop ORT) |
|---|---|---|---|
| PP-OCRv6 small (current) | 32/37 | 74/108 | +518 MB |
| PP-OCRv6 tiny | 23/37 | 74/108 | +365 MB |
| PP-OCRv5 mobile | 24/37 | 75/108 | +466 MB |
| PP-OCRv4 mobile | 25/37 | 72/108 | +507 MB |

Cap cost when it does engage (Python, long side ≤ 1600): Commons 32→30, Vinmonopolet 74→70
(small print: ABV, a producer, an appellation); 1024–1536 cost the same on Vinmonopolet.

**What's in place** (timeline 19): fewer Java copies (bit-identical) + a free-memory budget.
On the phone after the change:

| Run | Peak RSS | Result |
|---|---|---|
| casillero (det 1568×2016), budget, 809 MB free | 718 MB | full size, same fields |
| casillero, forced `DET_MAX_PIXELS=1500000` | **462 MB** | det 1088×1376, same fields, 8.6 s vs 12.6 s |
| all 26 Vinmonopolet in one process | 716 MB | 77/108, 123/140 — no kill, no cap |
| all 8 Commons in one process (killed on the 5th before) | 764 MB | 31/37, 38/59; schramsberg slightly capped at 713 MB free |

`BYTES_PER_DET_PIXEL = 170` comes from peak-vs-detector-pixels on the phone (0.94 MP → 355 MB,
1.88 → 486, 3.16 → 703). If real-use reports show kills or frequent caps, revisit it.

## Gotchas found the hard way

- **ONNX Runtime Java API mangles UTF-8 model metadata** (bytes decoded one by one and truncated: 18,166 entries instead of 18,708; spaces were lost because the space class index shifted). Fix: `ppocrv6_keys.txt`.
- **Android regex is ICU**: `(?U)` / `(?iu)` compile on the JVM but throw `PatternSyntaxException` on Android, inside `LabelParser.<clinit>` → `NoClassDefFoundError` for every scan.
- **Locale-dependent formatting**: `"%.2f".format(x)` gives `"0,58"` in ru/de/fr; `.toDouble()` on it throws. Round numerically (`DebugReport.round`). Only the on-screen percentage uses the locale on purpose.
- **Low-memory kills leave no crash log** — see [Memory on the phone](#memory-on-the-phone).
- **Caught exceptions are invisible** in `adb logcat AndroidRuntime:E` — that tag only logs crashes.
- **Kotlin nests block comments**: `fixtures/text/*.json` inside KDoc broke compilation.
- **rapidocr 3.x** requires enum values (`OCRVersion.PPOCRV6`, `ModelType`) for model params, not strings. Its default PP-OCRv6 model is multilingual; `lang_type="latin"` is rejected for v6.
- **PP-OCR models expect BGR** channel order (rapidocr feeds OpenCV images).
- The multilingual model "reads" ornaments as CJK characters → the short-low-confidence noise filter.
- **Gradle** needs `JAVA_HOME`; a failed `:core:test` skips `:app:assembleDebug` in the same run.
- **Gradle doesn't see fixture changes**: fixtures aren't declared task inputs, so after editing
  `expected.json`/photos `:core:test` may report a cached result. Use `--rerun`.
- `gradle-wrapper.jar` is committed; `local.properties` is not (set `ANDROID_HOME` in fresh clones).

---

## Vinmonopolet test set

26 product photos from Vinmonopolet (Norway's state retailer). **Copyrighted — never commit
them**; `python/fixtures/vinmonopolet/` is gitignored. Recreate in a fresh clone:

```bash
cd python && .venv/bin/python tools/fetch_vinmonopolet.py     # images + metadata.json + expected.json link
```

The **hand-checked ground truth is committed** as `python/fixtures/vinmonopolet.expected.json`
(facts and short label snippets, no images). `fixtures/vinmonopolet/expected.json` is a symlink to it,
created by the fetch script, so all tools read the one committed file. The shop's raw data
(`metadata.json`, local only) differs from the labels in many places — never use it as ground truth.
To add products: pass their codes to the script; it adds each new one to the committed file as a
draft from the shop's data marked `"_draft": true`. Check it against the photo, fix it, remove the
marker, then commit. Existing entries are never overwritten.
Ground-truth rules used: score only what is printed on the front/neck label; vintage as printed
(not the shop's current vintage); producer = winery/estate name if printed, omitted when only a
brand is shown; country if printed or implied by a printed appellation.

```bash
.venv/bin/winelabel eval fixtures/vinmonopolet --no-enhance                    # Python
cd ../android && ./gradlew :core:test --tests winelabel.PpOcrParityTest -Pphotos=vinmonopolet   # JVM (scores in the test XML)
./gradlew :app:assembleDebugAndroidTest -Pphotos=vinmonopolet                   # phone: then install + tools/device-test.sh
```

## Testing on the user's phone

The user's computer has the phone on USB and forwards its adb server to the VPS:

```bash
# user's computer (keep open; Ctrl+C closes it)
ssh -N -R 5038:localhost:5037 grg@<vps>
# VPS
~/Android/Sdk/platform-tools/adb -P 5038 devices      # HRY-LX1 ... device
```

Port 5038 so it never clashes with a local adb server on 5037. adb client and server must
speak the same protocol (the `1.0.41` in `adb version`). While the tunnel is open, anyone
logged in to the VPS controls the phone.

```bash
cd android && export JAVA_HOME=~/opt/jdk-17
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
A="$HOME/Android/Sdk/platform-tools/adb -P 5038"
$A install -r -t app/build/outputs/apk/debug/app-debug.apk                      # ~1 min over the tunnel
$A install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
tools/device-test.sh ONNX                       # or MLKIT; optional 2nd arg: a.jpg,b.jpg
$A pull /sdcard/Android/data/winelabel.app/files/device-test   # per-photo debug reports + summary.txt
```

`device-test.sh` prints the instrumentation result, the peak RSS (VmHWM polled every second),
the `WineLabelTest` summary and any low-memory kill. The `signal 9` line at the very end of
a passing run is the normal teardown. The camera can be triggered, but it photographs
whatever the phone is pointing at.

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
