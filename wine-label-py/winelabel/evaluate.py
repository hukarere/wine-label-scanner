"""Accuracy measurement on a folder of labelled photos (see fixtures/images)."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .ocr import OcrEngine
from .parser import normalize
from .scanner import scan


def values_equal(expected, actual) -> bool:
    if isinstance(expected, list):
        return isinstance(actual, list) and sorted(expected) == sorted(actual)
    if isinstance(expected, float) or isinstance(actual, float):
        return expected is not None and actual is not None and abs(float(expected) - float(actual)) < 0.05
    if isinstance(expected, str) and isinstance(actual, str):
        return normalize(expected) == normalize(actual)
    return expected == actual


@dataclass
class ImageReport:
    name: str
    seconds: float
    fields: dict[str, tuple[object, object, bool]]  # field -> (expected, actual, ok)
    missing_text: list[str]
    text_total: int
    raw_text: str


@dataclass
class EvalReport:
    images: list[ImageReport] = field(default_factory=list)

    @property
    def field_score(self) -> tuple[int, int]:
        oks = [ok for im in self.images for (_, _, ok) in im.fields.values()]
        return sum(oks), len(oks)

    @property
    def text_score(self) -> tuple[int, int]:
        total = sum(im.text_total for im in self.images)
        return total - sum(len(im.missing_text) for im in self.images), total

    @property
    def seconds(self) -> float:
        return sum(im.seconds for im in self.images)

    def per_field(self) -> dict[str, tuple[int, int]]:
        out: dict[str, list[int]] = {}
        for im in self.images:
            for name, (_, _, ok) in im.fields.items():
                out.setdefault(name, [0, 0])
                out[name][0] += ok
                out[name][1] += 1
        return {k: (v[0], v[1]) for k, v in out.items()}


def evaluate(folder: str | Path, enhance: bool = True, engine: OcrEngine | None = None) -> EvalReport:
    folder = Path(folder)
    expected_all = json.loads((folder / "expected.json").read_text(encoding="utf-8"))
    report = EvalReport()
    for name, expected in expected_all.items():
        result = scan(folder / name, enhance=enhance, engine=engine)
        actual = result.label.values()
        fields = {
            f: (exp, actual.get(f), values_equal(exp, actual.get(f)))
            for f, exp in expected.items() if not f.startswith("_")
        }
        text = " ".join(normalize(l.text) for l in result.lines)
        squashed = text.replace(" ", "")
        snippets = expected.get("_text", [])
        # Count a snippet as found even if OCR merged/split words differently.
        missing = [s for s in snippets if s not in text and s.replace(" ", "") not in squashed]
        report.images.append(ImageReport(name, result.seconds, fields, missing, len(snippets), result.raw_text))
    return report
