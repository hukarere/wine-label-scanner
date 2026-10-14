"""End-to-end pipeline: image -> OCR lines -> raw text + parsed fields."""
from __future__ import annotations

import time
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import numpy as np

from .layout import raw_text, reading_order
from .model import OcrLine
from .ocr import OcrEngine, RapidOcrEngine
from .parser import WineLabel, parse_label
from .preprocess import load_image, prepare


@dataclass
class ScanResult:
    lines: list[OcrLine]  # reading order
    raw_text: str
    label: WineLabel
    image: np.ndarray     # the preprocessed image OCR ran on (for debug drawing)
    seconds: float


def is_noise(line: OcrLine) -> bool:
    """Short, low-confidence reads are usually ornaments or drawings, not text."""
    return len(line.text) <= 2 and line.confidence < 0.7


@lru_cache(maxsize=1)
def default_engine() -> RapidOcrEngine:
    return RapidOcrEngine()


def scan_image(img_bgr: np.ndarray, enhance: bool = True, engine: OcrEngine | None = None) -> ScanResult:
    start = time.perf_counter()
    img = prepare(img_bgr, enhance=enhance)
    lines = reading_order([l for l in (engine or default_engine()).recognize(img) if not is_noise(l)])
    return ScanResult(lines, raw_text(lines), parse_label(lines), img, time.perf_counter() - start)


def scan(path: str | Path, enhance: bool = True, engine: OcrEngine | None = None) -> ScanResult:
    return scan_image(load_image(path), enhance=enhance, engine=engine)
