"""Command line interface.

    winelabel scan photo.jpg [--json] [--debug-image out.png] [--no-enhance]
    winelabel eval fixtures/images [--no-enhance]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

from .evaluate import evaluate
from .ocr import RapidOcrEngine
from .parser import WineLabel
from .scanner import ScanResult, scan

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff"}


def draw_debug(result: ScanResult, out: Path) -> None:
    """Draw numbered detection boxes; numbers match the CLI line listing."""
    img = result.image.copy()
    thickness = max(1, round(max(img.shape[:2]) / 600))
    for i, line in enumerate(result.lines, 1):
        pts = [[round(x), round(y)] for x, y in line.box]
        color = (0, 180, 0) if line.confidence >= 0.8 else (0, 140, 255)
        cv2.polylines(img, [np.array(pts)], True, color, thickness)
        x, y = pts[0]
        cv2.putText(img, str(i), (x, max(y - 4, 12)), cv2.FONT_HERSHEY_SIMPLEX,
                    0.4 * thickness, (0, 0, 255), thickness)
    cv2.imwrite(str(out), img)


def format_label(label: WineLabel) -> str:
    out = []
    for name, f in label.to_dict().items():
        if f is None:
            out.append(f"  {name:<15} -")
            continue
        value = ", ".join(map(str, f["value"])) if isinstance(f["value"], list) else f["value"]
        out.append(f"  {name:<15} {value}   ({f['confidence']:.2f})")
    return "\n".join(out)


def cmd_scan(args: argparse.Namespace) -> int:
    result = scan(args.image, enhance=not args.no_enhance, engine=make_engine(args))
    if args.debug_image:
        draw_debug(result, Path(args.debug_image))
    if args.json:
        print(json.dumps({
            "raw_text": result.raw_text,
            "fields": result.label.to_dict(),
            "lines": [{"text": l.text, "confidence": round(l.confidence, 3),
                       "height": round(l.height, 1), "box": l.box} for l in result.lines],
            "seconds": round(result.seconds, 2),
        }, ensure_ascii=False, indent=2))
        return 0
    print("=== Text on label ===")
    print(result.raw_text or "(no text found)")
    print("\n=== Fields ===")
    print(format_label(result.label))
    print(f"\n=== OCR lines ({len(result.lines)}, {result.seconds:.2f}s) ===")
    for i, l in enumerate(result.lines, 1):
        print(f"  {i:>2}. [{l.confidence:.2f} h={l.height:4.0f}] {l.text}")
    return 0


def cmd_eval(args: argparse.Namespace) -> int:
    """Run every image in DIR against DIR/expected.json and report accuracy."""
    report = evaluate(args.dir, enhance=not args.no_enhance, engine=make_engine(args))
    for im in report.images:
        print(f"\n## {im.name} ({im.seconds:.2f}s)")
        for fname, (exp, got, ok) in im.fields.items():
            print(f"  {'ok ' if ok else 'BAD'} {fname:<15} expected={exp!r} got={got!r}")
        if im.missing_text:
            print(f"  text missed {len(im.missing_text)}/{im.text_total}: {im.missing_text}")
    print("\n=== Field accuracy ===")
    for fname, (ok, n) in report.per_field().items():
        print(f"  {fname:<15} {ok}/{n}")
    ok, n = report.field_score
    tok, tn = report.text_score
    print(f"  {'ALL FIELDS':<15} {ok}/{n}")
    print(f"  {'TEXT RECALL':<15} {tok}/{tn}")
    print(f"  {'TIME':<15} {report.seconds:.1f}s")
    return 0


def make_engine(args: argparse.Namespace):
    return RapidOcrEngine(model_type=args.model)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="winelabel", description="Read text and fields from a wine label photo.")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan", help="scan one image")
    s.add_argument("image")
    s.add_argument("--json", action="store_true", help="machine-readable output")
    s.add_argument("--debug-image", metavar="PNG", help="write image with numbered detection boxes")
    s.add_argument("--no-enhance", action="store_true", help="skip contrast enhancement")
    s.add_argument("--model", choices=["tiny", "small", "medium"], default="small", help="PP-OCRv6 model size")
    s.set_defaults(func=cmd_scan)
    e = sub.add_parser("eval", help="measure accuracy on a folder with expected.json")
    e.add_argument("dir")
    e.add_argument("--no-enhance", action="store_true")
    e.add_argument("--model", choices=["tiny", "small", "medium"], default="small")
    e.set_defaults(func=cmd_eval)
    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
