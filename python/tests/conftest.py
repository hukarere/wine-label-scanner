import json
from pathlib import Path

from winelabel.model import OcrLine

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures"


def lines_from_fixture(rows: list) -> list[OcrLine]:
    """Build OcrLines from fixture rows [text, height(, confidence)], stacked top to bottom."""
    out, y = [], 0.0
    for row in rows:
        text, h = row[0], float(row[1])
        conf = float(row[2]) if len(row) > 2 else 0.98
        w = max(len(text), 1) * h * 0.55
        out.append(OcrLine(text, conf, ((0, y), (w, y), (w, y + h), (0, y + h))))
        y += h * 1.6
    return out


def load_text_fixtures() -> list[tuple[str, dict]]:
    return [(p.stem, json.loads(p.read_text(encoding="utf-8")))
            for p in sorted((FIXTURES / "text").glob("*.json"))]
