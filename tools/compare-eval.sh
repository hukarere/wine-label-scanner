#!/usr/bin/env bash
# Measure a recognition change on both photo sets and see exactly what it changed.
#
#   tools/compare-eval.sh run <name>         eval fixtures/images (+ fixtures/vinmonopolet if present)
#                                            with every OCR line dumped to build/eval/<name>/; prints
#                                            totals and the producer score; runs parser/layout tests
#   tools/compare-eval.sh diff <old> <new>   per-photo parsed-field changes between two runs
#   tools/compare-eval.sh same <old> <new>   exit 0 only if the OCR output is identical (lines, boxes,
#                                            confidences, fields) — for changes that must not change results
#
# Typical use: `run before` on the committed code (git stash), apply the change, `run after`,
# then `diff before after`. Run from anywhere; output stays in build/ (gitignored).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/eval"
export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk-17}

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

run() {
    local dir="$OUT/$1"
    rm -rf "$dir"; mkdir -p "$dir"
    cd "$ROOT"
    for set in images vinmonopolet; do
        if [ ! -f "fixtures/$set/expected.json" ]; then
            echo "$set: skipped (not present; see tools/fetch_vinmonopolet.py)"; continue
        fi
        ./gradlew -q :core:cli --args="eval fixtures/$set --dump $dir/dump/$set" > "$dir/$set.txt" 2>&1 ||
            { cat "$dir/$set.txt"; exit 1; }
        echo "$set: $(grep -E 'ALL FIELDS|TEXT RECALL' "$dir/$set.txt" | tr -s ' ' | tr '\n' ' ')" \
            "producer $(grep -E '^  producer ' "$dir/$set.txt" | awk '{print $2}')"
    done
    ./gradlew -q :core:test --rerun --tests 'winelabel.Parser*' --tests 'winelabel.LayoutTest' > /dev/null 2>&1 || true
    python3 - "$ROOT/core/build/test-results/test" <<'PY'
import glob, re, sys
run = failed = 0
for p in glob.glob(sys.argv[1] + "/TEST-winelabel.*.xml"):
    if not re.search(r"Parser|Layout", p): continue
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(p).read(2000))
    run += int(m[1]); failed += int(m[2]) + int(m[3])
    if int(m[2]) + int(m[3]): print("  FAILED:", p.split("TEST-")[-1])
print(f"parser/layout tests: {run} run, {failed} failed")
PY
    echo "per-photo reports: $dir/{images,vinmonopolet}.txt"
}

diff_runs() {
    local a="$OUT/$1/dump" b="$OUT/$2/dump"
    [ -d "$a" ] && [ -d "$b" ] || { echo "no such run: $1 or $2 (in $OUT)"; exit 2; }
    python3 - "$a" "$b" <<'PY'
import glob, os, re, sys
a, b = sys.argv[1:]
def fields(path):  # last line of a dump: the parsed fields as {k=v, ...}
    return dict(re.findall(r"(\w+)=(\[[^\]]*\]|[^,}]*)", open(path).read().rstrip().splitlines()[-1]))
changed = 0
for pa in sorted(glob.glob(a + "/*/*.txt")):
    pb = os.path.join(b, os.path.relpath(pa, a))
    if not os.path.exists(pb): continue
    A, B = fields(pa), fields(pb)
    d = [f"{k}: {A[k]} -> {B.get(k)}" for k in A if A[k] != B.get(k)]
    if d:
        changed += 1
        print(os.path.basename(pa)[:-4] + ": " + "; ".join(d))
print(f"{changed} photo(s) with changed fields")
PY
}

case "${1:-}" in
    run)  [ $# -eq 2 ] || usage; run "$2" ;;
    diff) [ $# -eq 3 ] || usage; diff_runs "$2" "$3" ;;
    same) [ $# -eq 3 ] || usage; diff -r "$OUT/$2/dump" "$OUT/$3/dump" > /dev/null && echo "identical OCR output" ||
              { echo "OCR output differs:"; diff -rq "$OUT/$2/dump" "$OUT/$3/dump" | sed 's#.*/##'; exit 1; } ;;
    *)    usage ;;
esac
