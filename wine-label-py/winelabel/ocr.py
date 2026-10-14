"""OCR engines. Only this module depends on the OCR library.

The Android port swaps RapidOcrEngine for an onnxruntime-android (same
.onnx models) or ML Kit implementation of the same interface.
"""
from __future__ import annotations

from typing import Protocol

import numpy as np

from .model import OcrLine


class OcrEngine(Protocol):
    def recognize(self, img_bgr: np.ndarray) -> list[OcrLine]: ...


class RapidOcrEngine:
    """PP-OCRv6 (multilingual: Latin scripts incl. accents) via ONNX Runtime.

    Models are downloaded on first use and cached by rapidocr. Versions are
    pinned explicitly so the Android app can ship the exact same files.
    """

    def __init__(self, model_type: str = "small", min_score: float = 0.5, **params):
        """`params` are extra rapidocr settings, e.g. {"Det.box_thresh": 0.4}."""
        from rapidocr import ModelType, OCRVersion, RapidOCR

        version, size = OCRVersion.PPOCRV6, ModelType(model_type)
        self._engine = RapidOCR(
            params={
                "Global.log_level": "warning",
                "Global.text_score": min_score,
                "Det.ocr_version": version,
                "Det.model_type": size,
                "Rec.ocr_version": version,
                "Rec.model_type": size,
                **params,
            }
        )

    def recognize(self, img_bgr: np.ndarray) -> list[OcrLine]:
        result = self._engine(img_bgr)
        if result.boxes is None:
            return []
        return [
            OcrLine(
                text=text.strip(),
                confidence=float(score),
                box=tuple((float(x), float(y)) for x, y in box),
            )
            for box, text, score in zip(result.boxes, result.txts, result.scores)
            if text.strip()
        ]
