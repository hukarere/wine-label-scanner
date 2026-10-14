# winelabel — wine label OCR prototype

Photo of a wine bottle → **all text on the label** + **parsed fields**
(producer, vintage, appellation, classification, grapes, ABV, volume, country).

Python prototype for a later Android (Kotlin) app. OCR is PaddleOCR's
**PP-OCRv6** (multilingual, handles accents) running on **ONNX Runtime** via
[`rapidocr`](https://github.com/RapidAI/RapidOCR). Android can run the same
`.onnx` model files with `onnxruntime-android`, so accuracy measured here
carries over.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
```

OCR models download automatically on first run. The default `small` set is
~32 MB (det 9.9 MB + rec 21.2 MB + orientation 0.6 MB); that is roughly what
the Android app would add to its size unless the models are quantized.

## Usage

```bash
# Text + fields + numbered OCR lines
.venv/bin/winelabel scan photo.jpg

# Machine-readable, plus an image with numbered detection boxes
.venv/bin/winelabel scan photo.jpg --json --debug-image boxes.png

# Accuracy on a folder of labelled photos
.venv/bin/winelabel eval fixtures/images
```

Options: `--no-enhance` (skip CLAHE contrast boost), `--model tiny|small|medium`.

Every field comes with a `confidence` and the `source` OCR line, so a UI can
hide or flag doubtful values (e.g. `< 0.5`) and let the user correct them.

## Tests

```bash
.venv/bin/pytest -q          # 57 tests, ~25 s (real-photo test is the slow one)
```

| Test file | What it covers |
|---|---|
| `test_parser.py` | Field extraction on `fixtures/text/*.json` + unit cases (ABV, volume, vintage, fuzzy names…) |
| `test_layout.py` | Reading order, rotated box height, noise filter |
| `test_ocr_smoke.py` | Renders a synthetic label (straight and rotated 7°), runs real OCR, checks fields |
| `test_images.py` | Accuracy floor on 8 real Wikimedia Commons photos (`fixtures/images/`) |

## Current accuracy (8 real photos, 2-CPU VPS)

| | |
|---|---|
| Fields correct | 30 / 37 (vintage 8/8, grapes 3/3, ABV 2/2) |
| Label text found | 40 / 59 expected snippets |
| Speed | ~2 s per photo (`small` model) |

The remaining misses are OCR limits, not the parser: heavy script fonts
(*Château Poujeaux*), text cut off at the photo/label edge, and very small
print on curved labels. Measured and rejected: the `medium` model (no better,
4× slower), detector threshold/size tuning (±1–2 snippets), upscaling small
photos (inconsistent).

Biggest expected gains next: **better photos** (fill the frame, straight on,
no glare — the app should guide this), and **multi-shot** for curved labels
(left/centre/right photos, merge lines).

## Layout

```
winelabel/
  preprocess.py   load (EXIF rotation), downscale, CLAHE contrast boost
  ocr.py          OcrEngine interface + RapidOcrEngine (only file touching the OCR lib)
  layout.py       rows / reading order / raw text
  parser.py       pure-logic field extraction (no image deps)
  scanner.py      pipeline: image -> lines -> text + fields
  evaluate.py     accuracy on labelled photos
  cli.py          `winelabel scan|eval`
  data/           grapes.txt, regions.txt (region|country), countries.txt (name|aliases)
fixtures/
  text/           OCR-line fixtures: [text, height(, confidence)] + expected fields
  images/         real photos + expected.json + ATTRIBUTION.md
```

## Porting to Android (Kotlin)

| Python | Kotlin |
|---|---|
| `preprocess.py` | `Bitmap` scaling + OpenCV Android (CLAHE), `ExifInterface` |
| `ocr.py` `RapidOcrEngine` | `onnxruntime-android` with the same PP-OCRv6 `.onnx` files (DBNet det → crop/warp → rec + CTC decode), **or** an ML Kit adapter implementing the same interface |
| `model.py`, `layout.py`, `parser.py` | Pure Kotlin, line-for-line (regexes are Java-compatible) |
| `data/*.txt` | `assets/` |
| `fixtures/text/*.json` | `src/test/resources/` — run the same cases in JUnit to prove parity |
| `fixtures/images/` | Instrumented test or a dev screen comparing ONNX vs ML Kit |

Model files are cached by rapidocr under
`.venv/lib/python3*/site-packages/rapidocr/models/` — copy those exact files
(`PP-OCRv6_det_small.onnx`, `PP-OCRv6_rec_small.onnx`,
`ch_ppocr_mobile_v2.0_cls_mobile.onnx`) into the app's assets.
