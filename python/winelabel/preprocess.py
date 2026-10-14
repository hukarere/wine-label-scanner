"""Image loading and enhancement before OCR."""
from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

MAX_SIDE = 2000


def load_image(path: str | Path) -> np.ndarray:
    """Load an image as BGR, honouring the EXIF orientation set by phone cameras."""
    with Image.open(path) as im:
        im = ImageOps.exif_transpose(im).convert("RGB")
        return cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2BGR)


def prepare(img: np.ndarray, enhance: bool = True, max_side: int = MAX_SIDE) -> np.ndarray:
    """Downscale large photos and optionally boost local contrast.

    CLAHE on the lightness channel helps with gold foil, embossing and dark
    labels without blowing out already well-lit text.
    """
    h, w = img.shape[:2]
    scale = max_side / max(h, w)
    if scale < 1:
        img = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    if enhance:
        lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        l = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(l)
        img = cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2BGR)
    return img
