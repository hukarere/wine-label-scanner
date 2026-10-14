"""Extract structured fields from OCR'd wine label lines.

Pure logic with no image/OCR dependencies, written to port 1:1 to Kotlin.
Every extracted field carries the line it came from and a confidence, so the
UI can show why a value was picked and let the user correct it.
"""
from __future__ import annotations

import datetime as _dt
import difflib
import re
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files

from .layout import group_rows
from .model import OcrLine

FUZZY_MIN_LEN = 6        # names shorter than this must match exactly
FUZZY_MIN_RATIO = 0.88   # SequenceMatcher ratio for OCR-garbled names
FUZZY_LONG_LEN = 12      # long names carry more evidence, so tolerate more damage
FUZZY_LONG_RATIO = 0.8   # e.g. "Cotes de Prows" -> "Côtes de Provence"
GLUED_MIN_LEN = 8        # multi-word names this long are also found with spaces lost


# --------------------------------------------------------------------------
# Result types


@dataclass
class Field:
    value: object
    confidence: float
    source: str  # OCR line the value came from

    def to_dict(self) -> dict:
        return {"value": self.value, "confidence": round(self.confidence, 2), "source": self.source}


@dataclass
class WineLabel:
    producer: Field | None = None
    vintage: Field | None = None
    appellation: Field | None = None
    classification: Field | None = None  # list[str], e.g. ["AOC", "Grand Cru Classé"]
    grapes: Field | None = None          # list[str]
    abv: Field | None = None             # float, % alcohol by volume
    volume_ml: Field | None = None       # int
    country: Field | None = None

    FIELDS = ("producer", "vintage", "appellation", "classification", "grapes", "abv", "volume_ml", "country")

    def values(self) -> dict:
        return {name: (f.value if (f := getattr(self, name)) else None) for name in self.FIELDS}

    def to_dict(self) -> dict:
        return {name: (f.to_dict() if (f := getattr(self, name)) else None) for name in self.FIELDS}


# --------------------------------------------------------------------------
# Text normalisation and reference data


def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def normalize(s: str) -> str:
    """Lowercase, accent-free, punctuation-free words separated by single spaces.

    Dotted abbreviations collapse first so "D.O.C." -> "doc" and "U.S.A." -> "usa".
    """
    s = re.sub(r"\b([A-Za-z])\.(?=[A-Za-z]\b\.?)", r"\1", s)
    s = re.sub(r"\b([A-Za-z])\.(?=\s|$)", r"\1", s)
    s = strip_accents(s).lower()
    s = re.sub(r"[^a-z0-9%]+", " ", s)
    # OCR often runs numbers into words ("7chardonnay", "vintage2004"); split them
    # apart, but keep short unit/ordinal suffixes like "750ml" and "1er" intact.
    s = re.sub(r"(?<=\d)(?=[a-z]{3})|(?<=[a-z]{3})(?=\d)", " ", s)
    return s.strip()


def _data_lines(name: str) -> list[str]:
    text = files("winelabel").joinpath("data", name).read_text(encoding="utf-8")
    return [ln.strip() for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]


@dataclass(frozen=True)
class _Name:
    display: str  # canonical spelling to report
    key: str      # normalized form used for matching
    extra: str = ""


def _sorted_names(names: list[_Name]) -> list[_Name]:
    # Longest names first so "Cabernet Sauvignon" claims its words before "Sauvignon".
    return sorted(names, key=lambda n: (-len(n.key.split()), -len(n.key)))


@lru_cache(maxsize=None)
def grapes() -> list[_Name]:
    return _sorted_names([_Name(g, normalize(g)) for g in _data_lines("grapes.txt")])


@lru_cache(maxsize=None)
def regions() -> list[_Name]:
    out = []
    for ln in _data_lines("regions.txt"):
        name, country = ln.split("|")
        out.append(_Name(name, normalize(name), country))
    return _sorted_names(out)


@lru_cache(maxsize=None)
def countries() -> list[_Name]:
    out = []
    for ln in _data_lines("countries.txt"):
        names = ln.split("|")
        out += [_Name(names[0], normalize(alias)) for alias in names]
    return _sorted_names(out)


# --------------------------------------------------------------------------
# Name matching


@dataclass(frozen=True)
class _Hit:
    name: _Name
    line: int
    word: int
    score: float


def _fuzzy_score(text: str, key: str) -> float:
    score = difflib.SequenceMatcher(None, text, key).ratio()
    # Long names are often cut off at the label's curved edge ("Côtes de Proven..."):
    # also compare against the name's prefix, with a small penalty.
    if len(key) >= FUZZY_LONG_LEN and len(text) >= 0.7 * len(key):
        prefix = key[: len(text)]
        score = max(score, 0.95 * difflib.SequenceMatcher(None, text, prefix).ratio())
    return score


def match_names(lines: list[str], names: list[_Name], fuzzy: bool = True) -> list[_Hit]:
    """Find non-overlapping occurrences of reference names in the lines.

    Three passes over words still unclaimed: exact word sequences, then
    multi-word names glued into one token ("NORTHCOASTSPARKLINGWINE"), then
    fuzzy matches (OCR slips like "Cabernet Sauvignan"). Results are in
    reading order.
    """
    words = [normalize(ln).split() for ln in lines]
    used = [[False] * len(w) for w in words]
    hits: list[_Hit] = []

    def scan_glued() -> None:
        for name in names:
            glued = name.key.replace(" ", "")
            if " " not in name.key or len(glued) < GLUED_MIN_LEN:
                continue
            for li, lw in enumerate(words):
                for i, token in enumerate(lw):
                    if not used[li][i] and len(token) > len(glued) and glued in token:
                        used[li][i] = True
                        hits.append(_Hit(name, li, i, 0.9))

    def scan(exact: bool) -> None:
        for name in names:
            key_words = name.key.split()
            k = len(key_words)
            if not exact and len(name.key) < FUZZY_MIN_LEN:
                continue
            min_ratio = FUZZY_LONG_RATIO if len(name.key) >= FUZZY_LONG_LEN else FUZZY_MIN_RATIO
            for li, lw in enumerate(words):
                for i in range(len(lw) - k + 1):
                    if any(used[li][i : i + k]):
                        continue
                    window = lw[i : i + k]
                    if exact:
                        if window != key_words:
                            continue
                        score = 1.0
                    else:
                        score = _fuzzy_score(" ".join(window), name.key)
                        if score < min_ratio:
                            continue
                    used[li][i : i + k] = [True] * k
                    hits.append(_Hit(name, li, i, score))

    scan(exact=True)
    scan_glued()
    if fuzzy:
        scan(exact=False)
    return sorted(hits, key=lambda h: (h.line, h.word))


# --------------------------------------------------------------------------
# Individual field extractors. Each takes the lines in reading order.

_NUM = r"\d{1,2}(?:[.,]\d{1,2})?"
_ABV_PATTERNS = [
    # 13,5% vol | 13.5 % alc./vol | 14% ABV | 12.5% by vol
    (re.compile(rf"({_NUM})\s*%\s*(?:alc|vol|abv|by\s+vol)", re.I), 0.95),
    # Alc. 13.5% | Alcohol 14.5% by volume | Alc. 13,5 % Vol.
    (re.compile(rf"(?:alc|alcohol|alcool|alkohol|alcol)\.?\s*(?:[a-z]+\.?\s*){{0,2}}?({_NUM})\s*%", re.I), 0.95),
    # bare 13.5% — only trusted when in a plausible wine range
    (re.compile(r"(?<![\d.,])(\d{1,2}[.,]\d)\s*%"), 0.6),
]


def extract_abv(lines: list[OcrLine]) -> Field | None:
    for pattern, conf in _ABV_PATTERNS:
        for line in lines:
            for m in pattern.finditer(line.text):
                value = float(m.group(1).replace(",", "."))
                if 4.5 <= value <= 23:
                    return Field(value, conf * line.confidence, line.text)
    return None


_VOLUME = re.compile(
    r"(?<![\d.,])(\d{1,4}(?:[.,]\d{1,3})?)\s*(ml|cl|ltr|lt|litres?|liters?|l)s?(?![a-z])", re.I
)
_TO_ML = {"ml": 1, "cl": 10}
_COMMON_ML = {187, 200, 250, 375, 500, 620, 700, 750, 1000, 1500, 3000, 5000, 6000}


def extract_volume(lines: list[OcrLine]) -> Field | None:
    best: Field | None = None
    for line in lines:
        for m in _VOLUME.finditer(line.text):
            unit = m.group(2).lower()
            ml = round(float(m.group(1).replace(",", ".")) * _TO_ML.get(unit, 1000))
            if not 100 <= ml <= 6000:
                continue
            conf = (0.95 if ml in _COMMON_ML else 0.6) * line.confidence
            if best is None or conf > best.confidence:
                best = Field(ml, conf, line.text)
    return best


_YEAR = re.compile(r"(?<![\d.,])(19[5-9]\d|20[0-4]\d)(?![\d%])(?!\s*(?:ml|cl)\b)", re.I)
# Years on these lines are founding/classification dates, not the vintage.
_NOT_VINTAGE = re.compile(
    r"\b(since|est|estd|established|founded|fondee?|depuis|dal|seit|desde|sinds|anno|"
    r"classification|classement|classe en|family owned)\b"
)
_VINTAGE_HINT = re.compile(r"\b(vintage|millesime|annata|cosecha|jahrgang|vendange|harvest)\b")


def extract_vintage(lines: list[OcrLine], this_year: int | None = None) -> Field | None:
    this_year = this_year or _dt.date.today().year
    max_h = max((l.height for l in lines), default=1) or 1
    best: tuple[float, Field] | None = None
    for line in lines:
        norm = normalize(line.text)
        if _NOT_VINTAGE.search(norm):
            continue
        for m in _YEAR.finditer(line.text):
            year = int(m.group(1))
            if year > this_year:
                continue
            standalone = line.text.strip() == m.group(1)
            score = (2 if standalone else 0) + (2 if _VINTAGE_HINT.search(norm) else 0) + line.height / max_h
            conf = (0.95 if standalone or _VINTAGE_HINT.search(norm) else 0.7) * line.confidence
            if best is None or score > best[0]:
                best = (score, Field(year, conf, line.text))
    return best[1] if best else None


# (normalized regex, canonical label). Checked in order; more specific first.
_CLASSIFICATIONS = [
    (r"\bdocg\b|denominazione di origine controllata e garantita", "DOCG"),
    (r"\bdoc\b|denominazione di origine controllata", "DOC"),
    (r"\bdoca\b|\bdoq\b|denominacion de origen calificada", "DOCa"),
    (r"\bdop\b|denominazione di origine protetta|denominacao de origem protegida", "DOP"),
    (r"\bigt\b|indicazione geografica tipica", "IGT"),
    (r"\bigp\b|indication geographique protegee|indicazione geografica protetta", "IGP"),
    (r"\baop\b|appellation d origine protegee|appellation \w+(?: \w+){0,3} protegee", "AOP"),
    (r"\baoc\b|appellation d origine controlee|appellation \w+(?: \w+){0,3} controlee", "AOC"),
    (r"\bava\b|american viticultural area", "AVA"),
    (r"\bvqa\b|vintners quality alliance", "VQA"),
    (r"\bpremier grand cru classe\b", "Premier Grand Cru Classé"),
    (r"\bgrand cru classe\b", "Grand Cru Classé"),
    (r"\bcru bourgeois\b", "Cru Bourgeois"),
    (r"\b(?:premier|1er) cru\b", "Premier Cru"),
    (r"\bgrand cru\b", "Grand Cru"),
    (r"\bpradikatswein\b", "Prädikatswein"),
    (r"\bqualitatswein\b", "Qualitätswein"),
    (r"\bgrosses gewachs\b|\bgg\b", "Grosses Gewächs"),
    (r"\btrockenbeerenauslese\b", "Trockenbeerenauslese"),
    (r"\bbeerenauslese\b", "Beerenauslese"),
    (r"\bauslese\b", "Auslese"),
    (r"\bspatlese\b", "Spätlese"),
    (r"\bkabinett\b", "Kabinett"),
    (r"\bgran reserva\b", "Gran Reserva"),
    (r"\breserva\b", "Reserva"),
    (r"\bcrianza\b", "Crianza"),
    (r"\briserva\b", "Riserva"),
    (r"\bsuperiore\b", "Superiore"),
]
# Spanish/Portuguese "DO" is too common a word to match case-insensitively.
_DO = re.compile(r"\bD\.?O\.?(?![A-Za-z])|Denominaci[oó]n de Origen(?! Calificada)", re.I)
_DO_STRICT = re.compile(r"\bD\.?O\.?(?![A-Za-z])")


def extract_classification(lines: list[OcrLine]) -> Field | None:
    found: list[str] = []
    sources: list[str] = []
    # Long legal lines are often split by OCR ("APPELLATION D'ORIGINE" / "PROTÉGÉE"),
    # so after single lines also try each pair of consecutive lines.
    pairs = [
        (OcrLine(f"{a.text} {b.text}", min(a.confidence, b.confidence), a.box), len(normalize(a.text)))
        for a, b in zip(lines, lines[1:])
    ]
    for line, split_at in [(l, None) for l in lines] + pairs:
        norm = normalize(line.text)
        claimed = norm
        for pattern, label in _CLASSIFICATIONS:
            m = re.search(pattern, claimed)
            # A pair only adds matches that cross the line break.
            if m and split_at is not None and not m.start() < split_at < m.end():
                continue
            if m and label not in found:
                found.append(label)
                sources.append(line.text)
                # Blank out the match so "grand cru classe" doesn't also yield "Grand Cru".
                claimed = claimed[: m.start()] + " " * (m.end() - m.start()) + claimed[m.end() :]
        if split_at is None and "DOCa" not in found and "DO" not in found:
            m = _DO.search(line.text)
            if m and (_DO_STRICT.search(line.text) or "denominaci" in m.group(0).lower()):
                found.append("DO")
                sources.append(line.text)
    if not found:
        return None
    return Field(found, 0.9, " / ".join(dict.fromkeys(sources)))


_APPELLATION_PHRASE = re.compile(
    r"appellation\s+(.+?)\s+(?:contr[oô]l[ée]e|prot[ée]g[ée]e)", re.I
)
_MARKER = re.compile(
    r"\b(appellation|aoc|aop|docg|doc|doca|do|dop|igt|igp|ava|denominazione|denominacion|"
    r"controlee|protegee|controllata|garantita|calificada|qualitatswein|vqa)\b"
)


def extract_appellation(lines: list[OcrLine]) -> Field | None:
    texts = [l.text for l in lines]
    # 1. Explicit French form: "Appellation Pauillac Contrôlée".
    for line in lines:
        m = _APPELLATION_PHRASE.search(line.text)
        if m and not normalize(m.group(1)).startswith(("d origine", "origine")):
            captured = m.group(1).strip()
            hits = match_names([captured], regions())
            if hits:
                return Field(hits[0].name.display, 0.95 * line.confidence, line.text)
            return Field(captured, 0.8 * line.confidence, line.text)

    # 2. Known region names; prefer those next to an appellation marker,
    #    then the most specific (more words), then the largest text.
    hits = match_names(texts, regions())
    if not hits:
        return None

    def near_marker(i: int) -> bool:
        return any(_MARKER.search(normalize(texts[j])) for j in (i - 1, i, i + 1) if 0 <= j < len(texts))

    best = max(
        hits,
        key=lambda h: (near_marker(h.line), len(h.name.key.split()), h.score, lines[h.line].height),
    )
    conf = (0.9 if near_marker(best.line) else 0.7) * best.score * lines[best.line].confidence
    return Field(best.name.display, conf, texts[best.line])


def extract_grapes(lines: list[OcrLine]) -> Field | None:
    hits = match_names([l.text for l in lines], grapes())
    if not hits:
        return None
    names = list(dict.fromkeys(h.name.display for h in hits))
    sources = list(dict.fromkeys(lines[h.line].text for h in hits))
    conf = min(h.score * lines[h.line].confidence for h in hits)
    return Field(names, conf, " / ".join(sources))


_COUNTRY_PHRASE = re.compile(
    r"\b(?:product of|produce of|produced in|wine of|made in|produit de|produit en|"
    r"prodotto in|producto de|hecho en|produto de|wein aus|erzeugnis aus|vin de|vino de|vinho de)"
    r"\s+(?:la |l |the )?(.+)"
)


# Classifications that only exist in one country. (AOP/DOP/IGP are EU-wide.)
_CLASSIFICATION_COUNTRY = {
    "AOC": "France", "DOCG": "Italy", "DOC": "Italy", "IGT": "Italy", "DOCa": "Spain",
    "AVA": "United States", "VQA": "Canada", "Prädikatswein": "Germany", "Qualitätswein": "Germany",
    "Premier Grand Cru Classé": "France", "Grand Cru Classé": "France", "Cru Bourgeois": "France",
}


def extract_country(
    lines: list[OcrLine], appellation: Field | None = None, classification: Field | None = None
) -> Field | None:
    # 1. "Product of France" style phrases.
    for line in lines:
        m = _COUNTRY_PHRASE.search(normalize(line.text))
        if m:
            hits = match_names([m.group(1)], countries(), fuzzy=False)
            if hits and hits[0].word == 0:
                return Field(hits[0].name.display, 0.95 * line.confidence, line.text)
    # 2. A country name anywhere on the label.
    hits = match_names([l.text for l in lines], countries(), fuzzy=False)
    if hits:
        return Field(hits[0].name.display, 0.8 * lines[hits[0].line].confidence, lines[hits[0].line].text)
    # 3. Implied by a known appellation.
    if appellation:
        for r in regions():
            if r.display == appellation.value:
                return Field(r.extra, 0.6 * appellation.confidence, appellation.source)
    # 4. Implied by a national classification system.
    if classification:
        for label in classification.value:
            if label in _CLASSIFICATION_COUNTRY:
                return Field(_CLASSIFICATION_COUNTRY[label], 0.5, classification.source)
    return None


_PRODUCER_WORDS = re.compile(
    r"\b(chateau|domaine|clos|maison|bodegas?|weingut|schloss|tenuta|cantina|cantine|azienda|"
    r"fattoria|castello|quinta|herdade|vina|winery|wines|estate|vineyards?|cellars?)\b"
)
# Lines that mention a producer word but are bottling/legal statements.
_BOTTLING = re.compile(
    r"\b(mis en bouteille|bottled|imbottigliato|embotellado|engarrafado|abgefullt|"
    r"gutsabfullung|erzeugerabfullung|produced by|produit par|imported by|importe par|"
    r"distributed|contains sulfites|contient des sulfites|enthalt sulfite)\b"
)


def extract_producer(lines: list[OcrLine], claimed: set[int]) -> Field | None:
    """`claimed` holds indices of lines already used by other fields."""
    max_h = max((l.height for l in lines), default=1) or 1
    candidates = []
    for i, line in enumerate(lines):
        norm = normalize(line.text)
        if _BOTTLING.search(norm) or _NOT_VINTAGE.search(norm) or len(re.sub(r"[^a-z]", "", norm)) < 3:
            continue
        # A producer keyword is a bonus, not an override: big unmarked names
        # ("CLOUDY BAY") should still beat small print like "Maison fondée en 1772".
        if _PRODUCER_WORDS.search(norm):
            candidates.append((0.5 + line.height / max_h, i, 0.9))
        elif i not in claimed:
            candidates.append((line.height / max_h, i, 0.6))
    if not candidates:
        return None
    _, i, conf = max(candidates)
    # Big names are often split into pieces on one row ("Christina" | "Dhom").
    best = lines[i]
    row = next(r for r in group_rows(lines) if best in r)
    parts = [l for l in row if l is best or (l.height >= 0.7 * best.height and lines.index(l) not in claimed)]
    text = " ".join(_clean_name(l.text) for l in parts)
    return Field(text, conf * min(l.confidence for l in parts), " | ".join(l.text for l in parts))


def _clean_name(text: str) -> str:
    """Drop trademark symbols and OCR noise around a name ("Schramsberg®" read as "Schramsberg@")."""
    return re.sub(r"^[^\w\"'(]+|[^\w\"')]+$", "", text.strip())


# --------------------------------------------------------------------------


def _claimed_lines(lines: list[OcrLine], label: WineLabel) -> set[int]:
    """Indices of lines whose whole content is explained by another field."""
    claimed = set()
    for i, line in enumerate(lines):
        norm = normalize(line.text)
        rest = norm
        for f in (label.vintage, label.abv, label.volume_ml, label.appellation):
            if f and f.source == line.text:
                claimed.add(i)
        for names in (regions(), grapes(), countries()):
            for h in match_names([line.text], names, fuzzy=False):
                rest = rest.replace(h.name.key, " ")
        if _MARKER.search(norm) or not re.sub(r"[^a-z]", "", rest):
            claimed.add(i)
    return claimed


def parse_label(lines: list[OcrLine], this_year: int | None = None) -> WineLabel:
    """Extract fields from lines given in reading order."""
    label = WineLabel()
    if not lines:
        return label
    label.vintage = extract_vintage(lines, this_year)
    label.abv = extract_abv(lines)
    label.volume_ml = extract_volume(lines)
    label.classification = extract_classification(lines)
    label.appellation = extract_appellation(lines)
    label.grapes = extract_grapes(lines)
    label.country = extract_country(lines, label.appellation, label.classification)
    label.producer = extract_producer(lines, _claimed_lines(lines, label))
    return label
