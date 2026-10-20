#!/bin/bash
# One command for the device checks of P4-15, P11 and the P8-02 shader fix, run by the owner on the PC
# (no agent needed). Builds and installs the debug app, creates the P11 test clips (ffmpeg) and pushes
# them to /sdcard/Movies/vibeplayer-p11, runs the devcheck P11 checks (plus the full regression with
# --full) and leaves everything in one folder: summary.md, screenshots, logs.
#
#   tools/phone_check.sh            # build, install, P11 checks
#   tools/phone_check.sh --full     # the same, then the whole devcheck regression
#   tools/phone_check.sh --no-build # skip gradle and install (app already on the phone)
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
export PATH="$REPO/tools/bin:$PATH"
OUT="/tmp/vp_phone_check/$(date +%Y%m%d-%H%M%S)"
P11_DIR=/sdcard/Movies/vibeplayer-p11
FULL=0
BUILD=1
for a in "$@"; do
  case "$a" in
    --full) FULL=1 ;;
    --no-build) BUILD=0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
mkdir -p "$OUT/assets"
say() { printf '\n== %s\n' "$*"; }

say "Checks before starting"
for t in ffmpeg python3; do command -v "$t" >/dev/null || { echo "missing: $t"; exit 1; }; done
python3 -c "import PIL" 2>/dev/null || { echo "missing: Python Pillow (pip install pillow, or the python-pillow package)"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "phone not on adb (Wi-Fi address in tools/bin/phone_wifi, or plug in USB)"; exit 1; }
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r'), battery $(adb shell dumpsys battery | grep -m1 level | tr -dc 0-9) %"
echo "commit: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"

if [ "$BUILD" = 1 ]; then
  say "Build and install (debug)"
  ./gradlew :app:assembleDebug 2>&1 | tee "$OUT/build.log" | tail -3
  adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
fi

say "P11 test clips"
if adb shell ls "$P11_DIR/p11_chapters.mkv" >/dev/null 2>&1; then
  echo "already on the phone ($P11_DIR)"
else
  A="$OUT/assets"
  cat > "$A/chapters.txt" <<'META'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=30000
title=Prologue
[CHAPTER]
TIMEBASE=1/1000
START=30000
END=120000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=120000
END=210000
title=Part A
[CHAPTER]
TIMEBASE=1/1000
START=210000
END=240000
title=Ending
META
  # 4 min (about 12 MB), 480p moving test pattern, a tone; chapters from chapters.txt
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=854x480:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 \
    -i "$A/chapters.txt" -map 0:v -map 1:a -map_metadata 2 -map_chapters 2 -t 240 \
    -c:v libx264 -preset veryfast -crf 34 -pix_fmt yuv420p -c:a aac -b:a 64k "$A/p11_chapters.mkv"
  # 30 s clip for the encoding check
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=854x480:rate=24 -f lavfi -i sine=frequency=660:sample_rate=48000 \
    -map 0:v -map 1:a -t 30 -c:v libx264 -preset veryfast -crf 34 -pix_fmt yuv420p -c:a aac -b:a 64k "$A/p11_arabic.mkv"
  # subtitles next to them: UTF-8 English, Windows-1256 Arabic (for Settings > Subtitle encoding)
  python3 - "$A" <<'PY'
import sys
d = sys.argv[1]
def srt(lines):
    out = []
    for i, t in enumerate(lines):
        s = i * 5
        out.append(f"{i + 1}\n00:00:{s:02d},500 --> 00:00:{s + 4:02d},000\n{t}\n")
    return "\n".join(out)
open(f"{d}/p11_chapters.srt", "w", encoding="utf-8").write(srt(["Auto-loaded subtitle (UTF-8)", "Second line", "Third line"]))
open(f"{d}/p11_arabic.srt", "wb").write(srt(["مرحبا بالعالم", "هذا اختبار للترميز", "النص العربي يظهر بشكل صحيح"]).encode("cp1256"))
PY
  adb shell mkdir -p "$P11_DIR"
  for f in p11_chapters.mkv p11_chapters.srt p11_arabic.mkv p11_arabic.srt; do adb push "$A/$f" "$P11_DIR/$f" >/dev/null; done
  # let MediaStore index them (for the browser and search); harmless if the broadcast is ignored
  for f in p11_chapters.mkv p11_arabic.mkv; do
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$P11_DIR/$f" >/dev/null 2>&1 || true
  done
  echo "pushed to $P11_DIR"
fi

say "Device checks (P4-15, P11, P8-02)"
ONLY=p11_chapters,p11_skip,p11_more,p11_frame_step,p11_night_mode,p11_audio_session,p11_anime4k_gles,p11_panels_look,no_fatal
set +e
python3 tools/devcheck/run.py --only "$ONLY" --out "$OUT/p11"
RC=$?
if [ "$FULL" = 1 ]; then
  say "Full regression"
  python3 tools/devcheck/run.py --out "$OUT/full"
  RC=$((RC | $?))
fi
set -e

say "Done"
echo "Results: $OUT (summary.md and screenshots in p11/$([ "$FULL" = 1 ] && echo ' and full/'))"
echo "The manual steps (volume boost, subtitle folders and encoding, search, sleep timer, rotate, ViPER) are listed in p11/summary.md."
echo "To share them: copy the folder into the repo as docs/device_runs/$(basename "$OUT") and commit, or upload p11/summary.md."
exit $RC
