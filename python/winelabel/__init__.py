"""Wine label OCR prototype."""
from .model import OcrLine
from .parser import Field, WineLabel, parse_label

__all__ = ["OcrLine", "Field", "WineLabel", "parse_label"]
