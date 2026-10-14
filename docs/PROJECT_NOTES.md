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
| Python prototype | **Retired** (timeline 20) after the Kotlin port matched it; in git history before that commit. |
| Kotlin `core` | The single implementation. 58 JVM tests pass, including OCR on the 8 real photos; desktop CLI `:core:cli` (`scan`, `eval`). JVM results equal the phone's. |
| Android app | Builds (debug, arm64-v8a, 74 MB), lint clean. Installed on the user's phone (Honor 10 Lite HRY-LX1, Android 10, **2.7 GB RAM**, ru locale). |
| On-device | Tested directly from the VPS over an adb tunnel ([Testing on the user's phone](#testing-on-the-users-phone)). `DeviceScanTest` runs both engines on the 8 photos. Fixed: ICU regex crash, locale crash in the debug report. PP-OCR memory: fixed by cutting image copies (bit-identical output) and a **free-memory budget that caps the detector input only when needed** — all 8 Commons photos now run in one process (31/37, was killed on the 5th). |
| Repo | github.com/hukarere/wine-label-scanner (public). No LICENSE yet. APK not published as a Release. |

**Not yet verified anywhere:** the camera → TakePicture flow end to end, the
share-into-app intent, dark theme look, the UI flow driven by taps.

**Test sets:** 8 committed Commons photos (PP-OCR 31/37) + 26 local-only Vinmonopolet packshots (PP-OCR 83/108, ML Kit 71/108).

**Next:** missing regions/grapes (Patagonia, Mornington Peninsula, Anderson Valley; Refosco,
Traminer), release build, then real-bottle scans → fixtures.

## Roadmap

Prioritized; none started unless noted.

0. ~~PP-OCR memory on 2–3 GB phones~~ — done (budgeted detector cap); watch `det_capped` in debug reports from real use.
1. **Collect real-scan debug reports** → add as fixtures (see [Adding a test case](#adding-a-test-case)) → parser fixes.
2. ~~Choose an engine~~ — decided 2026-10-14: **PP-OCR is the default; ML Kit stays as a user toggle** (fast option: 0.4 s vs 5 s per scan). Cost accepted: ~12 MB APK, its INTERNET/ACCESS_NETWORK_STATE permissions, ML Kit only testable on the phone — re-check it there occasionally (`tools/device-test.sh MLKIT`), not on every parser change.
3. **Release build**: enable R8 (`isMinifyEnabled`, check ONNX Runtime keep rules), release signing key, smaller APK (~10 MB of debug dex goes away).
4. **Capture quality**: in-app guidance; multi-shot for curved labels (left/centre/right photos, merge lines by text similarity).
5. Producer extraction: improved 10 → 16/24 (timeline 22). Left: brand vs producer (labels print the brand larger; maybe show both), names only in "bottled by X" lines, OCR misreads of script fonts.
6. Optional: CLAHE contrast enhancement (the retired Python prototype measured +2 text snippets, −2 fields on the 8 photos — unclear benefit).
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
20. **Retired the Python prototype.** It doubled every parser change, and the Kotlin `core` on the JVM is the more faithful test bed (it gives the phone's exact results; Python scored 74 vs 77/108 on Vinmonopolet). Moved `python/fixtures/` → `fixtures/` and the fetch script → `tools/`; added the desktop CLI (`core/src/cli`: `scan`, `eval`, shared `Evaluate` used by the tests too); ported the two Python-only tests (noise filter, synthetic rendered label at 0°/7°) → 58 Kotlin tests. CLI `eval --dump` output is identical to the pre-move baseline on all 34 photos. The golden OCR output in `core/src/test/resources/golden/` stays as a frozen reference.
21. **Detector size rounding** now matches rapidocr: multiples of 32 with halves to even (`Math.rint`), so a 2000 px side becomes 1984, not 2016 — this hits every camera photo (the app shrinks them to a 2000 px long side). Changed exactly the 7 affected photos of 34: fields unchanged (31/37, 77/108), Commons text 38→39/59, ~1.6 % fewer detector pixels. Phone: identical (31/37, 39/59), all 8 in one process peak 731 MB (was 764).
22. **Producer extraction** (measured on both sets after each step; Commons unchanged at 31/37 throughout):
    vertical text measured by its letter height, not its length (`OcrLine.height`); +0.5 bonus for a name printed twice (capsule/neck + label; fuzzy, ratio ≥ 0.85); classification-only lines ("Beerenauslese") no longer candidates; lines of 6+ words (5 without a producer word) skipped as slogans; generic end words (winery, wines, estate, vineyard(s), cellar(s)) get +0.2 instead of +0.5; the chosen line is joined with a stacked or side-by-side piece of similar letter size (ratio ≥ 0.8, or any size after a bare keyword like "DOMAINE", or a connector like "del"), never with places, grapes, legal lines or pieces of 4+ words.
    **Vinmonopolet 77 → 83/108, producer 10 → 16/24**; phone identical (83/108), ML Kit 69 → 71/108. Remaining producer misses: OCR misreads (Schramsberge, Chatean, SAINTE MAGDEL cut off, Gigi Ron, Doisy "Daine", "SUFPRENE"), brand printed larger than the producer (Casillero del Diablo / Concha y Toro, Post Scriptum / Prats & Symington, Busy Lissy / Bunn Strebel), K Vintners only in "bottled by…", First Drop.

---

## Architecture

### Pipeline (`core`; a port of rapidocr 3.x with its default settings)

```
photo
 └─ prepare: decode + EXIF-rotate (app: BitmapFactory, ≥ 2000 px kept; CLI/tests: ImageIO), downscale long side ≤ 2000 (area)
     └─ OCR engine → list of OcrLine(text, confidence, 4-point box)
          PP-OCR (PpOcrEngine):
            bound sides (≤2000, ≥30, multiples of 32) → vertical padding if very wide
            → DET: DBNet, resize so short side ≥ 736 (×32; app: capped to detMaxPixels from free memory), (x/255−0.5)/0.5, BGR, NCHW
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

### Parser (`parse/LabelParser.kt`)

- `normalize`: collapse dotted abbreviations (D.O.C.→doc), NFKD strip accents, lowercase, non-`[a-z0-9%]`→space, split digit runs from ≥3-letter runs.
- `match_names` over reference lists (longest names first), three passes on unclaimed words: exact word sequence → glued token (multi-word name ≥ 8 chars inside one token) → fuzzy (difflib ratio ≥ 0.88; ≥ 0.80 for names ≥ 12 chars, also vs. the name's prefix ×0.95 for truncation; names < 6 chars exact only).
- Extractors: ABV (3 regex tiers, 4.5–23 %), volume (ml/cl/l → ml, 100–6000, common sizes trusted), vintage (1950–2049, ≤ current year, skip founding/classification lines, prefer standalone/large/"vintage"-hinted), classification (ordered pattern list with blanking + adjacent-line pairs that must cross the break; strict case-sensitive `DO`), appellation ("Appellation X Contrôlée" phrase, else region names near markers/more words/larger text), grapes, country (phrase → any name → appellation's country → classification's country), producer (keyword bonus + text height, excluding bottling/legal/founding lines and lines fully explained by other fields; merges same-row pieces; strips ®/@ noise).

### Kotlin modules (`android/core/src/`)

| Path | What |
|---|---|
| `main/.../model/` | `OcrLine`, `RgbImage` (0xRRGGBB ints) |
| `main/.../layout/Layout.kt` | rows, reading order, raw text |
| `main/.../parse/` | `LabelParser`, `Text` (normalize), `Reference` (lists in `main/resources/winelabel/data/`), `SequenceMatcher` (difflib ratio) |
| `main/.../ocr/` | `OcrEngine`, `PpOcrEngine` (port of rapidocr), `ImageOps` (resize, warp, rotations), `Geometry` (components, hull, min-area rect, unclip, box score) |
| `main/.../Scanner.kt` | prepare → OCR → noise filter → reading order → parse |
| `cli/.../cli/` | desktop only: `Main` (`scan`, `eval`), `Evaluate` (scoring, report, `--dump`; used by the tests), `Desktop` (ImageIO, models, debug image) |

`core` compiles against `com.microsoft.onnxruntime:onnxruntime` (`compileOnly`, desktop); the
app supplies `onnxruntime-android` — same `ai.onnxruntime` API.

### App (`android/app/src/main/kotlin/winelabel/app/`)

- `MainActivity` — Compose host; accepts `ACTION_SEND image/*` (share a photo into the app).
- `ScanViewModel` — `UiState` Idle / Working / Done(result, info) / Error(message, details); engine choice persisted in SharedPreferences; keeps the last image for "Re-scan with other engine"; logs errors with tag `WineLabel`.
- `AppScan` / `DeviceMemory` — the scan path shared by the UI and `DeviceScanTest`: free-memory budget → `PpOcrEngine.detMaxPixels`, scan, `ScanInfo` (timings, detector size, cap, free memory).
- `Engines` — lazily loads the ONNX models from `assets/models/` (+ keys file); `MlKitEngine` adapter.
- `AndroidImages` — URI → downsampled decode (long side kept ≥ 2000) → EXIF rotation → `RgbImage`.
- `ui/ScanScreen` — engine chips, Take photo (`TakePicture` + FileProvider → **no camera permission**), Choose photo (`PickVisualMedia`), photo with box overlay, fields with confidence (faded < 50 %), raw text, Copy text / Share / Copy debug report / Re-scan.
- `DebugReport` — JSON: app version, device, engine, image size, total and per-stage ms, detector input/cap/free memory, raw text, fields, lines with boxes.

### Tests

| Suite | Count | What |
|---|---|---|
| `core/src/test/` (JVM) | 63 | parser fixtures (`fixtures/text`), parser/layout units, noise filter, synthetic rendered label (0°/7°), OCR on the 8 photos (floors + similarity to the frozen golden output), ICU regex guard |
| `app/src/androidTest/` (phone) | 2 | `DeviceScanTest`: each engine on a photo set through the app's own scan path; floors only for `images` |

---

## Key decisions

| Decision | Why |
|---|---|
| Python prototype first | No device or emulator on the VPS; Python could be tested end to end here. |
| Python retired later | Once the Kotlin `core` matched it and ran on the JVM with the phone's exact results, a second parser only doubled the work. |
| No emulator | No KVM; ARM64 images under software emulation crash-loop (no input devices in the guest kernel). Test on the user's phone over an adb tunnel instead. |
| PP-OCR via ONNX (rapidocr) | Same model files on the desktop and on Android → accuracy measured here carries over. ML Kit can only be measured on a phone. |
| PP-OCRv6 **small** | `medium` was no more accurate on the photos and 4× slower. One multilingual model handles accents (é, ô, ü). |
| ML Kit kept as a toggle | First for comparison; then kept by the user's choice (2026-10-14) as the fast option, although PP-OCR is more accurate (31 vs 22/37, 77 vs 69/108). |
| No OpenCV on Android | Would add ~30 MB; the needed geometry is ~300 lines of Kotlin. |
| No CLAHE in Kotlin | On the 8 photos CLAHE traded 2 fields for 2 text snippets — no clear win. |
| arm64-v8a only | Covers essentially all current phones; halves native-library size. |
| `TakePicture` intent instead of CameraX | No camera permission and no preview code; the system camera does the work. |
| Models committed to git | Clone-and-build simplicity; each file < 100 MB. |
| One fixtures folder | JVM tests, the CLI and the on-device test (as test-APK assets) all read `fixtures/`. |

## Measurements

8 real photos (`fixtures/images`), 37 expected fields, 59 expected text snippets. VPS, 2 CPUs. Python rows are from the retired prototype.

| Pipeline | Fields | Text snippets | Notes |
|---|---|---|---|
| Python, before parser improvements | 24/37 | 40/59 | baseline |
| Python, CLAHE (default) | 30/37 | 40/59 | ~2 s/photo |
| Python, `--no-enhance` | 32/37 | 38/59 | |
| Kotlin `core` on the JVM | 31/37 | 38/59 | OCR text similarity to Python 0.971; 1–3 s/photo |
| Kotlin, half-even size rounding (timeline 21) | 31/37 | **39/59** | current (producer work, timeline 22, changed nothing here) |

Parameter sweep (Python, before the later parser fixes): medium model 24/37 and 37/59 at 4× the time; detector `limit_side_len` 960/1280 and lower thresholds changed results by ±1–2; upscaling small photos helped only one image.

Remaining misses are OCR-limited: script fonts (Château Poujeaux), text cut off at the photo edge (Sainte Magdeleine), small print on curved labels, embossed glass.

**Vinmonopolet set** (26 studio packshots, ~600–780×2000 px, two 1530–1554×2000; 108 fields, 140 snippets — local only, see [Vinmonopolet test set](#vinmonopolet-test-set)):

| Pipeline | Fields | Text | Producer | ABV / volume | Time | Peak RSS (phone) |
|---|---|---|---|---|---|---|
| Python PP-OCR `--no-enhance` | 74/108 | 117/140 | 7/24 | | 1.4 s/photo (VPS) | |
| Kotlin PP-OCR, JVM | 77/108 | 123/140 | 10/24 | 2/5, 1/3 | 1.2 s/photo (VPS) | |
| Kotlin PP-OCR, phone (one photo per process) | **77/108** | **123/140** | 10/24 | 2/5, 1/3 | 5.4 s/photo | median 449 MB, max 782 MB (the two 1554-wide photos) |
| ML Kit, phone | 69/108 | 104/140 | 4/24 | 0/5, 0/3 | 0.4 s/photo | 304 MB |
| **Kotlin PP-OCR after producer work** (timeline 22), JVM = phone | **83/108** | 123/140 | **16/24** | 2/5, 1/3 | | 720 MB (26 in one process) |
| ML Kit, phone, after producer work | 71/108 | 104/140 | | | | 294 MB |

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

Tried and rejected (measured with the retired Python prototype, `--no-enhance`, full-res baseline 32/37 fields, 38/59 text):
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
- **PP-OCR models expect BGR** channel order (rapidocr feeds OpenCV images).
- The multilingual model "reads" ornaments as CJK characters → the short-low-confidence noise filter.
- **Gradle** needs `JAVA_HOME`; a failed `:core:test` skips `:app:assembleDebug` in the same run.
- **Gradle doesn't see fixture changes**: fixtures aren't declared task inputs, so after editing
  `expected.json`/photos `:core:test` may report a cached result. Use `--rerun`.
- `gradle-wrapper.jar` is committed; `local.properties` is not (set `ANDROID_HOME` in fresh clones).

---

## Vinmonopolet test set

26 product photos from Vinmonopolet (Norway's state retailer). **Copyrighted — never commit
them**; `fixtures/vinmonopolet/` is gitignored. Recreate in a fresh clone:

```bash
python3 tools/fetch_vinmonopolet.py      # from the repo root: images + metadata.json + expected.json link
```

The **hand-checked ground truth is committed** as `fixtures/vinmonopolet.expected.json`
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
cd android
./gradlew -q :core:cli --args="eval fixtures/vinmonopolet"        # JVM, per-photo report
./gradlew :app:assembleDebugAndroidTest -Pphotos=vinmonopolet     # phone: then install + tools/device-test.sh
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

**From a debug report** (no photo): create `fixtures/text/<name>.json`:

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
use `null` to require "not found". `./gradlew :core:test` (`ParserFixtureTest`) picks it up automatically.

**From a photo**: add the image to `fixtures/images/`, an entry in
`expected.json` (fields plus `_text`: normalized snippets — lowercase, no accents or
punctuation), and a row in `ATTRIBUTION.md` (only freely licensed photos; this repo is
public). Add its golden OCR output (below). Accuracy floors live in `PpOcrParityTest.kt`
(and `DeviceScanTest.kt` for the phone). Photos that can't be published go into a local-only
folder like the Vinmonopolet set, with only their ground truth committed.

## Maintenance recipes

Golden OCR output for a new photo (the existing goldens are frozen output of the retired Python
prototype; `PpOcrParityTest` only checks mean text similarity ≥ 0.9, so don't regenerate them
just because the pipeline changed slightly):

```bash
cd android
./gradlew -q :core:cli --args="scan fixtures/images/<photo>.jpg --json" > core/src/test/resources/golden/<photo>.json
```

Regenerate the recognizer alphabet after changing the rec model (needs the `onnx` package in a
throwaway venv):

```bash
python3 -m venv /tmp/onnxenv && /tmp/onnxenv/bin/pip install -q onnx
cd android && /tmp/onnxenv/bin/python -c "
import onnx
meta = {p.key: p.value for p in onnx.load('app/src/main/assets/models/PP-OCRv6_rec_small.onnx').metadata_props}
open('app/src/main/assets/models/ppocrv6_keys.txt', 'w', encoding='utf-8', newline='\n').write(meta['character'])"
```

Models are rapidocr 3.10's ONNX conversions from its ModelScope repository, e.g.
`https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx`.

Toolchain reinstall (user space): Temurin JDK 17 → `~/opt/jdk-17`; Android cmdline-tools →
`~/Android/Sdk/cmdline-tools/latest`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
