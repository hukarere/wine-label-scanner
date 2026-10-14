"""Plain data types shared by the OCR, layout and parser stages."""
from __future__ import annotations

import math
from dataclasses import dataclass

Point = tuple[float, float]


@dataclass(frozen=True)
class OcrLine:
    """One detected text line.

    `box` holds 4 corners in clockwise order starting top-left, as returned
    by PP-OCR detection (rotated text gives a rotated quadrilateral).
    """

    text: str
    confidence: float
    box: tuple[Point, Point, Point, Point]

    @property
    def height(self) -> float:
        """Text height in pixels: mean length of the left and right edges."""
        (x0, y0), (x1, y1), (x2, y2), (x3, y3) = self.box
        return (math.dist((x0, y0), (x3, y3)) + math.dist((x1, y1), (x2, y2))) / 2

    @property
    def center(self) -> Point:
        xs = [p[0] for p in self.box]
        ys = [p[1] for p in self.box]
        return sum(xs) / 4, sum(ys) / 4

    @property
    def left(self) -> float:
        return min(p[0] for p in self.box)
