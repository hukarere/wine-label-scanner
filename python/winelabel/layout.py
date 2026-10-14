"""Turn unordered OCR lines into reading order and raw text."""
from __future__ import annotations

from .model import OcrLine


def group_rows(lines: list[OcrLine]) -> list[list[OcrLine]]:
    """Group lines whose vertical centres overlap into rows, top to bottom,
    each row sorted left to right."""
    rows: list[list[OcrLine]] = []
    for line in sorted(lines, key=lambda l: l.center[1]):
        if rows:
            row = rows[-1]
            row_y = sum(l.center[1] for l in row) / len(row)
            tolerance = 0.5 * min(line.height, min(l.height for l in row))
            if abs(line.center[1] - row_y) <= tolerance:
                row.append(line)
                continue
        rows.append([line])
    return [sorted(row, key=lambda l: l.left) for row in rows]


def reading_order(lines: list[OcrLine]) -> list[OcrLine]:
    return [line for row in group_rows(lines) for line in row]


def raw_text(lines: list[OcrLine]) -> str:
    """All label text, one visual row per line."""
    return "\n".join("  ".join(l.text for l in row) for row in group_rows(lines))
