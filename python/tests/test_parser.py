import pytest

from winelabel.evaluate import values_equal
from winelabel.model import OcrLine
from winelabel.parser import (
    extract_abv, extract_vintage, extract_volume, match_names, grapes, normalize, parse_label,
)

from conftest import lines_from_fixture, load_text_fixtures

FIXTURES = load_text_fixtures()


@pytest.mark.parametrize("name,fixture", FIXTURES, ids=[n for n, _ in FIXTURES])
def test_fixture(name, fixture):
    label = parse_label(lines_from_fixture(fixture["lines"]), this_year=2026)
    actual = label.values()
    wrong = {
        field: {"expected": exp, "got": actual[field]}
        for field, exp in fixture["expected"].items()
        if not values_equal(exp, actual[field])
    }
    assert not wrong, wrong


def L(text, h=20.0):
    return OcrLine(text, 0.99, ((0, 0), (100, 0), (100, h), (0, h)))


@pytest.mark.parametrize("text,expected", [
    ("13,5% vol", 13.5),
    ("13.5 % VOL.", 13.5),
    ("Alc. 14.5% by vol", 14.5),
    ("ALC 12% BY VOLUME", 12.0),
    ("alcool 12,5 %", 12.5),
    ("14% ABV", 14.0),
    ("100% Merlot", None),
    ("Sangiovese 80%", None),
])
def test_abv(text, expected):
    f = extract_abv([L(text)])
    assert (f.value if f else None) == expected


@pytest.mark.parametrize("text,expected", [
    ("750 ml", 750), ("750ML", 750), ("75 cl", 750), ("75cl", 750), ("0,75 l", 750),
    ("e 0,75 L", 750), ("1.5 L", 1500), ("375 mL", 375), ("Since 1855", None), ("13,5% vol", None),
])
def test_volume(text, expected):
    f = extract_volume([L(text)])
    assert (f.value if f else None) == expected


@pytest.mark.parametrize("texts,expected", [
    (["2015"], 2015),
    (["Established 1890", "2012"], 2012),
    (["Since 1772"], None),
    (["Grand Cru Classé en 1855", "2010"], 2010),
    (["2031"], None),  # future year
    (["Millésime 2005"], 2005),
    (["750 ml 2019"], 2019),
])
def test_vintage(texts, expected):
    f = extract_vintage([L(t) for t in texts], this_year=2026)
    assert (f.value if f else None) == expected


def test_vintage_prefers_standalone_large_year():
    lines = [L("Cuvée 1998 edition", 10), L("2018", 40)]
    assert extract_vintage(lines, this_year=2026).value == 2018


def test_normalize():
    assert normalize("Château  Côte-Rôtie!") == "chateau cote rotie"
    assert normalize("D.O.C.G.") == "docg"
    assert normalize("U.S.A.") == "usa"
    assert normalize("7chardonnay VINTAGE2004") == "7 chardonnay vintage 2004"
    assert normalize("750ml 1er Cru") == "750ml 1er cru"


def test_longest_grape_wins():
    hits = match_names(["Cabernet Sauvignon & Sauvignon Blanc"], grapes())
    assert [h.name.display for h in hits] == ["Cabernet Sauvignon", "Sauvignon Blanc"]


def test_fuzzy_grape_match():
    hits = match_names(["Cabernet Sauvignan"], grapes())
    assert [h.name.display for h in hits] == ["Cabernet Sauvignon"]
    assert hits[0].score < 1


def test_short_names_need_exact_match():
    # "Gamy" is close to "Gamay" but too short to trust a fuzzy match
    assert match_names(["Gamy"], grapes()) == []


def test_empty_input():
    assert all(v is None for v in parse_label([]).values().values())


def test_glued_multiword_name():
    # OCR lost the spaces on small print
    hits = match_names(["100%NORTHCOASTSPARKLINGWINE"], __import__("winelabel.parser", fromlist=["regions"]).regions())
    assert [h.name.display for h in hits] == ["North Coast"]


def test_truncated_long_name_matches_prefix():
    from winelabel.parser import regions
    hits = match_names(["Cotes de Prows"], regions())
    assert [h.name.display for h in hits] == ["Côtes de Provence"]


@pytest.mark.parametrize("a,b", [
    ("Cotes de Nuits", "Côtes de Provence"),
    ("Sauvignon Gris", "Sauvignon Blanc"),
])
def test_similar_names_do_not_cross_match(a, b):
    from winelabel.parser import regions
    assert b not in [h.name.display for h in match_names([a], grapes() + regions())]


def test_classification_split_across_lines():
    label = parse_label([L("APPELLATION D'ORIGINE"), L("E PROTÉGEE")])
    assert label.classification.value == ["AOP"]


def test_classification_pair_does_not_duplicate():
    label = parse_label([L("Grand Cru Classé en 1855"), L("Pauillac")])
    assert label.classification.value == ["Grand Cru Classé"]


def test_country_from_classification():
    label = parse_label([L("Some Winery", 40), L("VQA"), L("2017")])
    assert label.country.value == "Canada"


def test_producer_symbols_and_split_row():
    lines = [
        OcrLine("Christina", 0.95, ((0, 0), (300, 0), (300, 100), (0, 100))),
        OcrLine("Dhom®", 0.9, ((320, 5), (500, 5), (500, 95), (320, 95))),
        OcrLine("Der Riesling", 0.99, ((0, 300), (300, 300), (300, 340), (0, 340))),
    ]
    assert parse_label(lines).producer.value == "Christina Dhom"


def test_volume_plural():
    assert extract_volume([L("750 MLS")]).value == 750
