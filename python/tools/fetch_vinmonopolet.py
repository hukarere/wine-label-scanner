"""Download a local-only test set of wine packshots from Vinmonopolet (vinmonopolet.no).

The images are copyrighted by Vinmonopolet / the producers, so the output folder is
gitignored and must never be committed. Writes, per product, `<slug>.jpg`, plus
`metadata.json` (the shop's data, for reference) and a draft `expected.json`.

The draft lists what the shop knows. Only part of it is printed on the front label,
so check every entry against its photo and delete what isn't visible before scoring
(see docs/PROJECT_NOTES.md "Vinmonopolet test set").

    .venv/bin/python tools/fetch_vinmonopolet.py [out_dir] [code ...]
"""
from __future__ import annotations

import json
import re
import sys
import time
import unicodedata
import urllib.request
from pathlib import Path

CODES = [
    "18033701", "17040001", "17260501", "19687801", "11260007", "18757101", "20866901",
    "15397501", "6675401", "17510001", "5949001", "18947101", "7311801", "19293601",
    "17471002", "10181101", "18674101", "9804306", "20819906", "14209901", "15180601",
    "18953601", "536501", "13182201", "2077601", "10150901",
]
API = "https://www.vinmonopolet.no/vmpws"
IMAGE = "https://bilder.vinmonopolet.no/cache/2000x2000-0/{code}-1.jpg"
HEADERS = {"User-Agent": "Mozilla/5.0 (wine-label-scanner test-set fetcher)"}
COUNTRIES = {  # Vinmonopolet (Norwegian) -> winelabel/data/countries.txt
    "frankrike": "France", "italia": "Italy", "spania": "Spain", "portugal": "Portugal",
    "tyskland": "Germany", "østerrike": "Austria", "usa": "United States", "chile": "Chile",
    "argentina": "Argentina", "australia": "Australia", "new_zealand": "New Zealand",
    "sør-afrika": "South Africa",
}


def get(url: str) -> bytes:
    time.sleep(1.0)  # be polite
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60) as r:
        return r.read()


def slug(name: str) -> str:
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")


def appellation(code: str) -> dict:
    """The search result has sub_District (the appellation); the detail record doesn't."""
    data = json.loads(get(f"{API}/v2/vmp/products/search?fields=FULL&searchType=product&q={code}"))
    for p in data.get("products", []):
        if p.get("code") == code:
            return p
    return {}


def draft(detail: dict, listing: dict) -> dict:
    content = detail.get("content") or {}
    traits = {t["name"]: t["formattedValue"] for t in content.get("traits", [])}
    abv = traits.get("Alkohol", "").replace("%", "").replace(",", ".").strip()
    grapes = [i["name"] for i in content.get("ingredients", [])]
    volume = (detail.get("volume") or {}).get("value")
    year = detail.get("year")
    region = (listing.get("sub_District") or {}).get("name") or (detail.get("district") or {}).get("name")
    exp = {
        "producer": (detail.get("main_producer") or {}).get("name"),
        "vintage": int(year) if year and year.isdigit() and year != "0000" else None,
        "appellation": region,
        "grapes": grapes or None,
        "abv": float(abv) if abv else None,
        "volume_ml": int(round(volume * 10)) if volume else None,
        "country": COUNTRIES.get((detail.get("main_country") or {}).get("code", ""), None),
        "_text": [],
    }
    return {k: v for k, v in exp.items() if v is not None}


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "fixtures/vinmonopolet")
    codes = sys.argv[2:] or CODES
    out.mkdir(parents=True, exist_ok=True)
    meta_path, exp_path = out / "metadata.json", out / "expected.json"
    metadata = json.loads(meta_path.read_text()) if meta_path.exists() else {}
    expected = json.loads(exp_path.read_text()) if exp_path.exists() else {}
    for code in codes:
        detail = json.loads(get(f"{API}/v3/vmp/products/{code}?fields=FULL"))
        listing = appellation(code)
        name = detail["name"]
        file = f"{slug(name)}.jpg"
        (out / file).write_bytes(get(IMAGE.format(code=code)))
        metadata[file] = {"code": code, "url": "https://www.vinmonopolet.no" + detail.get("url", ""),
                          "detail": detail, "listing": listing}
        expected.setdefault(file, draft(detail, listing))  # never overwrite hand-checked entries
        print(f"{file:<60} {name}")
    meta_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=1))
    exp_path.write_text(json.dumps(expected, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
