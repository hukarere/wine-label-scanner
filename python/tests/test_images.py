"""Regression guard on real photos in fixtures/images (slow: ~15 s on 2 CPUs).

Floors are set just under the current scores; raise them as accuracy improves.
Run `winelabel eval fixtures/images` for the per-image breakdown.
"""
import pytest

from winelabel.evaluate import evaluate

from conftest import FIXTURES

IMAGES = FIXTURES / "images"
MIN_FIELDS = 0.78   # currently 30/37 = 0.81
MIN_TEXT = 0.65     # currently 40/59 = 0.68


@pytest.mark.skipif(not (IMAGES / "expected.json").exists(), reason="no labelled photos")
def test_real_photos_accuracy():
    report = evaluate(IMAGES)
    (fo, fn), (to, tn) = report.field_score, report.text_score
    assert fo / fn >= MIN_FIELDS, f"field accuracy dropped to {fo}/{fn}"
    assert to / tn >= MIN_TEXT, f"text recall dropped to {to}/{tn}"
