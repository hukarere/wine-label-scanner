"""End-to-end: render a synthetic label, run the real PP-OCR models, parse.

Slow-ish (~seconds) and needs the ONNX models (downloaded on first run).
"""
from pathlib import Path

import cv2
import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFont

from winelabel.parser import normalize
from winelabel.scanner import scan_image

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/Library/Fonts/Arial.ttf",
    "C:/Windows/Fonts/arial.ttf",
]


def font(size):
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


def render_label() -> np.ndarray:
    img = Image.new("RGB", (1000, 1200), (238, 228, 205))  # cream paper
    d = ImageDraw.Draw(img)
    rows = [
        ("GRAND VIN", 40, 60),
        ("Château Lafîte Brûlé", 80, 140),
        ("PAUILLAC", 50, 300),
        ("2015", 90, 400),
        ("Appellation Pauillac Contrôlée", 34, 560),
        ("Cabernet Sauvignon · Merlot", 34, 640),
        ("Mis en bouteille au Château", 26, 800),
        ("13,5% vol", 30, 900),
        ("750 ml", 30, 960),
        ("Produit de France", 26, 1040),
    ]
    for text, size, y in rows:
        f = font(size)
        w = d.textlength(text, font=f)
        d.text(((1000 - w) / 2, y), text, font=f, fill=(35, 25, 20))
    return cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2BGR)


def rotate(img: np.ndarray, degrees: float) -> np.ndarray:
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), degrees, 1.0)
    return cv2.warpAffine(img, m, (w, h), borderValue=(238, 228, 205))


EXPECTED = {
    "producer": "Château Lafîte Brûlé",
    "vintage": 2015,
    "appellation": "Pauillac",
    "grapes": ["Cabernet Sauvignon", "Merlot"],
    "abv": 13.5,
    "volume_ml": 750,
    "country": "France",
}


@pytest.mark.parametrize("angle", [0, 7])
@pytest.mark.parametrize("enhance", [True, False])
def test_synthetic_label(angle, enhance):
    img = render_label()
    if angle:
        img = rotate(img, angle)
    result = scan_image(img, enhance=enhance)
    assert "Contrôlée" in result.raw_text  # accents survive OCR
    values = result.label.values()
    # Small diacritics on names (î) are sometimes dropped by OCR; compare names accent-free.
    assert normalize(values["producer"]) == normalize(EXPECTED["producer"]), result.raw_text
    for field, expected in EXPECTED.items():
        if field != "producer":
            assert values[field] == expected, (field, values[field], result.raw_text)
