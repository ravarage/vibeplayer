#!/bin/bash
# About 15 minutes of device checks: start it, leave the phone alone (unlocked, on the desk), share the
# result. Builds and installs the debug app once, then:
#   1. P12: creates the clips in /sdcard/Movies/vibeplayer-p12 (plus a fresh copy without history for the
#      folder-memory checks) and runs the p12 checks;
#   2. P13 network: tools/network_check.sh (test server on this PC through adb reverse).
# Each part leaves summary.md and screenshots in its own folder; the last lines list both.
#
#   tools/shower_check.sh               # build, install, P12 and network checks
#   tools/shower_check.sh --no-build    # the app is already on the phone
#   tools/shower_check.sh --no-network  # P12 only
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
export PATH="$REPO/tools/bin:$PATH"
OUT="/tmp/vp_shower_check/$(date +%Y%m%d-%H%M%S)"
P12_DIR=/sdcard/Movies/vibeplayer-p12
BUILD=1
NETWORK=1
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --no-network) NETWORK=0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
mkdir -p "$OUT/assets"
say() { printf '\n== %s\n' "$*"; }

say "Checks before starting"
for t in ffmpeg python3; do command -v "$t" >/dev/null || { echo "missing: $t"; exit 1; }; done
python3 -c "import PIL" 2>/dev/null || { echo "missing: Python Pillow (pip install pillow)"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "phone not on adb (Wi-Fi address in tools/bin/phone_wifi, or plug in USB)"; exit 1; }
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r'), battery $(adb shell dumpsys battery | grep -m1 level | tr -dc 0-9) %"
echo "commit: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"

if [ "$BUILD" = 1 ]; then
  say "Build and install (debug)"
  ./gradlew :app:assembleDebug 2>&1 | tee "$OUT/build.log" | tail -3
  adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
fi

say "P12 test clips"
A="$OUT/assets"
CLIPS_VERSION=1
ON_PHONE=$(adb shell ls "$P12_DIR" 2>/dev/null | tr -d '\r' || true)  # empty on the first run (no folder yet)
have_all() { for f in p12_ep01.mkv p12_ep02.mkv p12_portrait.mp4; do grep -qx "$f" <<<"$ON_PHONE" || return 1; done; }
if [ "$(adb shell cat "$P12_DIR/.clips_version" 2>/dev/null | tr -d '\r')" = "$CLIPS_VERSION" ] && have_all; then
  echo "already on the phone ($P12_DIR)"
  adb pull "$P12_DIR/p12_ep01.mkv" "$A/p12_ep01.mkv" >/dev/null
else
  python3 - "$A" <<'PY'
import sys
d = sys.argv[1]
lines = []
for i in range(12):
    s = i * 5
    lines.append(f"{i + 1}\n00:00:{s:02d},500 --> 00:00:{s + 4:02d},000\nLine {i + 1}\n")
open(f"{d}/lines.srt", "w", encoding="utf-8").write("\n".join(lines))
open(f"{d}/chapters.txt", "w").write(";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=30000\ntitle=Part 1\n"
                                     "[CHAPTER]\nTIMEBASE=1/1000\nSTART=30000\nEND=60000\ntitle=Part 2\n")
PY
  # 60 s episodes: 480p test pattern, English (440 Hz, default) and Japanese (660 Hz) audio, an SRT line every
  # 5 s, two chapters. A keyframe every second so seeks near the end land at once.
  for ep in 01 02; do
    ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=854x480:rate=24 \
      -f lavfi -i sine=frequency=440:sample_rate=48000 -f lavfi -i sine=frequency=660:sample_rate=48000 \
      -i "$A/lines.srt" -i "$A/chapters.txt" \
      -map 0:v -map 1:a -map 2:a -map 3:s -map_metadata 4 -map_chapters 4 -t 60 \
      -c:v libx264 -preset veryfast -crf 34 -g 24 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 48k -c:s srt \
      -metadata:s:a:0 language=eng -metadata:s:a:0 title=English -metadata:s:a:1 language=jpn -metadata:s:a:1 title=Japanese \
      -metadata:s:s:0 language=eng -disposition:a:0 default -disposition:a:1 0 -disposition:s:0 default \
      "$A/p12_ep$ep.mkv"
  done
  # 20 s clip filmed "upright": stored 1280x720 with a 90 degree display rotation
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=30 -t 20 \
    -c:v libx264 -preset veryfast -crf 32 -g 30 -pix_fmt yuv420p "$A/landscape.mp4"
  ffmpeg -loglevel error -y -display_rotation:v:0 90 -i "$A/landscape.mp4" -c copy "$A/p12_portrait.mp4" \
    || ffmpeg -loglevel error -y -i "$A/landscape.mp4" -c copy -metadata:s:v:0 rotate=90 "$A/p12_portrait.mp4"
  # newer ffmpeg ignores the rotate tag: make sure the clip really carries a rotation
  if ! ffprobe -v error -show_entries stream_side_data=rotation:stream_tags=rotate -of csv=p=0 "$A/p12_portrait.mp4" | grep -qE '(^|,)-?90'; then
    echo "WARNING: p12_portrait.mp4 has no 90 degree rotation (ffmpeg too old?); p12_rotated_clip will fail"
  fi
  adb shell mkdir -p "$P12_DIR"
  for f in p12_ep01.mkv p12_ep02.mkv p12_portrait.mp4; do adb push "$A/$f" "$P12_DIR/$f" >/dev/null; done
  echo "$CLIPS_VERSION" > "$A/.clips_version"
  adb push "$A/.clips_version" "$P12_DIR/.clips_version" >/dev/null
  echo "pushed to $P12_DIR"
fi
# A file without any history, new each run (the folder speed and language checks need one).
for old in $(adb shell ls "$P12_DIR" 2>/dev/null | tr -d '\r' | grep '^p12_fresh_.*\.mkv$' || true); do
  adb shell rm -f "$P12_DIR/$old"
done
FRESH="p12_fresh_$(date +%s).mkv"
adb push "$A/p12_ep01.mkv" "$P12_DIR/$FRESH" >/dev/null
for f in p12_ep01.mkv p12_ep02.mkv p12_portrait.mp4 "$FRESH"; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$P12_DIR/$f" >/dev/null 2>&1 || true
done
echo "fresh copy: $FRESH"

say "Device checks (P12)"
ONLY=p12_rotated_clip,p12_resume_chip,p12_up_next,p12_sub_line,p12_bookmarks,p12_shader_switch,p12_folder_speed,p12_folder_lang,no_fatal
set +e
python3 tools/devcheck/run.py --only "$ONLY" --out "$OUT/p12" 2>&1 | tee "$OUT/p12.log"
RC=${PIPESTATUS[0]}
NET_RC=0
if [ "$NETWORK" = 1 ]; then
  say "Network checks (P13)"
  # already built and installed above
  tools/network_check.sh --no-build 2>&1 | tee "$OUT/network.log"
  NET_RC=${PIPESTATUS[0]}
fi
set -e

say "Done"
echo "== P12 results ($OUT/p12):"
grep -E "^(p12_[a-z_]+|no_fatal) +(PASS|FAIL|SKIP)" "$OUT/p12.log" || true
if [ "$NETWORK" = 1 ]; then
  echo "== Network results ($(grep -o '/tmp/vp_network_check/[0-9-]*/p13' "$OUT/network.log" | tail -1)):"
  grep -E "^(p13_[a-z_]+|no_fatal) +(PASS|FAIL|SKIP)" "$OUT/network.log" || true
fi
echo "Share these lines; for a failure also its trace.txt and logcat.txt from the check's folder."
exit $((RC | NET_RC))
