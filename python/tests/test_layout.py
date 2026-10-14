from winelabel.layout import group_rows, raw_text, reading_order
from winelabel.model import OcrLine


def box(x, y, w, h):
    return ((x, y), (x + w, y), (x + w, y + h), (x, y + h))


def test_rows_and_order():
    lines = [
        OcrLine("750 ml", 0.9, box(300, 200, 80, 20)),
        OcrLine("13% vol", 0.9, box(20, 203, 90, 20)),   # same row, left of "750 ml"
        OcrLine("2015", 0.9, box(100, 120, 60, 30)),
        OcrLine("Château X", 0.9, box(50, 10, 300, 60)),
    ]
    assert [l.text for l in reading_order(lines)] == ["Château X", "2015", "13% vol", "750 ml"]
    assert len(group_rows(lines)) == 3
    assert raw_text(lines) == "Château X\n2015\n13% vol  750 ml"


def test_height_of_rotated_box():
    # 20px tall box rotated 90°: height is the left/right edge length, not the y-extent
    line = OcrLine("x", 1.0, ((0, 0), (0, 100), (-20, 100), (-20, 0)))
    assert abs(line.height - 20) < 1e-6


def test_empty():
    assert raw_text([]) == ""


def test_noise_filter():
    from winelabel.scanner import is_noise
    b = ((0, 0), (10, 0), (10, 10), (0, 10))
    assert is_noise(OcrLine("里", 0.57, b))
    assert is_noise(OcrLine("Da", 0.56, b))
    assert not is_noise(OcrLine("VQA", 0.6, b))
    assert not is_noise(OcrLine("DO", 0.95, b))
