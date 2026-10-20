#!/bin/bash
# The long phone runs, for a night with the PC on and the phone on its charger (about 2.5 hours):
#   1. P6-05 stability: 30 min of hevc10_4k.mp4 on Vulkan and on OpenGL ES (drops, crashes, temperature)
#   2. P8-04 shader benchmark: 5 min of ichiban.mp4 per preset (none, Anime4K A/B/C, GSR) on Vulkan
#   3. the full devcheck regression (every check, P11 and P12 included)
# Benchmark rows are written to the results folder, not to docs/BENCHMARK.md, so the repo stays clean for
# tools/release.sh; share the folder (or its summary.txt) in the morning.
#
#   tools/overnight.sh            # build and install the debug app first
#   tools/overnight.sh --no-build
set -uo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
export PATH="$REPO/tools/bin:$PATH"
OUT="/tmp/vp_overnight/$(date +%Y%m%d-%H%M%S)"
BUILD=1
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
mkdir -p "$OUT"
: > "$OUT/BENCHMARK_rows.md"  # run_matrix.py appends only to an existing file
SUMMARY="$OUT/summary.txt"
say() { printf '\n== %s  (%s)\n' "$*" "$(date +%H:%M)" | tee -a "$SUMMARY"; }
battery() { adb shell dumpsys battery | awk '/ level:/{l=$2} /(AC|USB|Wireless) powered: true/{p=1} END{print l "%" (p ? " charging" : " on battery")}'; }

say "Checks before starting"
adb get-state >/dev/null 2>&1 || { echo "phone not on adb"; exit 1; }
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r'), battery $(battery)" | tee -a "$SUMMARY"
echo "commit: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)" | tee -a "$SUMMARY"
case "$(battery)" in
  *charging*) ;;
  *) echo "Put the phone on its charger first: 2.5 hours of 4K playback would drain it."; exit 1 ;;
esac

if [ "$BUILD" = 1 ]; then
  say "Build and install (debug)"
  ./gradlew :app:assembleDebug 2>&1 | tee "$OUT/build.log" | tail -3
  adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1 | tee -a "$SUMMARY"
fi

RC=0
say "P6-05 stability (2 x 30 min)"
python3 tools/bench/run_matrix.py --matrix stability --allow-charging --out-dir "$OUT/stability" \
  --markdown-doc "$OUT/BENCHMARK_rows.md" 2>&1 | tee "$OUT/stability.log" | tail -15
RC=$((RC | ${PIPESTATUS[0]}))
echo "battery after stability: $(battery)" | tee -a "$SUMMARY"

say "P8-04 shader benchmark (5 x 5 min, Vulkan)"
python3 tools/bench/run_matrix.py --matrix shaders --allow-charging --out-dir "$OUT/shaders" \
  --markdown-doc "$OUT/BENCHMARK_rows.md" 2>&1 | tee "$OUT/shaders.log" | tail -15
RC=$((RC | ${PIPESTATUS[0]}))

say "Full regression"
python3 tools/devcheck/run.py --out "$OUT/full" 2>&1 | tee "$OUT/full.log" | tail -60
RC=$((RC | ${PIPESTATUS[0]}))
grep -E "^[a-z0-9_]+ +(PASS|FAIL|SKIP)" "$OUT/full.log" >> "$SUMMARY" || true

say "Done"
echo "battery at the end: $(battery)" | tee -a "$SUMMARY"
[ -f "$OUT/BENCHMARK_rows.md" ] && { echo; cat "$OUT/BENCHMARK_rows.md"; } >> "$SUMMARY"
echo "Results: $OUT (summary.txt has the benchmark rows and the check table)"
exit $RC
