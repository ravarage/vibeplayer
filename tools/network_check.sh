#!/bin/bash
# P13 network checks, about 5 minutes. Serves test clips from this PC (tools/net_test_server.py: seeking,
# folder listing), makes them http://127.0.0.1:8765 on the phone through `adb reverse` (works over USB and
# Wi-Fi adb, no firewall changes), then runs the p13 device checks: a video link, HLS, a web folder with next
# episode and subtitle, an M3U channel list. SFTP, SMB, WebDAV, FTP, DLNA and real IPTV are by hand (summary.md).
#
#   tools/network_check.sh            # build and install first
#   tools/network_check.sh --no-build
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
export PATH="$REPO/tools/bin:$PATH"
OUT="/tmp/vp_network_check/$(date +%Y%m%d-%H%M%S)"
SERVE="${XDG_CACHE_HOME:-$HOME/.cache}/vibeplayer-net-test"
PORT=8765
BUILD=1
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
mkdir -p "$OUT" "$SERVE/hls"
say() { printf '\n== %s\n' "$*"; }

say "Checks before starting"
for t in ffmpeg python3; do command -v "$t" >/dev/null || { echo "missing: $t"; exit 1; }; done
adb get-state >/dev/null 2>&1 || { echo "phone not on adb"; exit 1; }
echo "commit: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"

if [ "$BUILD" = 1 ]; then
  say "Build and install (debug)"
  ./gradlew :app:assembleDebug 2>&1 | tee "$OUT/build.log" | tail -3
  adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
fi

say "Test clips ($SERVE)"
if [ ! -f "$SERVE/.version1" ]; then
  python3 - "$SERVE" <<'PY'
import sys
d = sys.argv[1]
lines = [f"{i + 1}\n00:00:{i * 5:02d},500 --> 00:00:{i * 5 + 4:02d},000\nNetwork line {i + 1}\n" for i in range(10)]
open(f"{d}/p13_ep01.srt", "w").write("\n".join(lines))
PY
  for ep in 01 02; do
    ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=854x480:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 \
      -t 50 -c:v libx264 -preset veryfast -crf 34 -g 24 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 48k "$SERVE/p13_ep$ep.mkv"
  done
  # an HLS stream as IPTV and many sites serve it (4 s segments)
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=854x480:rate=24 -f lavfi -i sine=frequency=550:sample_rate=48000 \
    -t 40 -c:v libx264 -preset veryfast -crf 34 -g 48 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 48k \
    -f hls -hls_time 4 -hls_playlist_type vod "$SERVE/hls/stream.m3u8"
  cat > "$SERVE/list.m3u" <<M3U
#EXTM3U
#EXTINF:-1 tvg-logo="" group-title="News",Channel One
http://127.0.0.1:$PORT/hls/stream.m3u8
#EXTINF:-1 group-title="Movies",Channel Two
#EXTVLCOPT:http-user-agent=VibeTest/1.0 (Android, network check)
http://127.0.0.1:$PORT/p13_ep02.mkv
M3U
  touch "$SERVE/.version1"
fi

say "Test server and adb reverse"
python3 tools/net_test_server.py "$SERVE" "$PORT" > "$OUT/server.log" 2>&1 &
SERVER=$!
cleanup() {
  kill "$SERVER" 2>/dev/null || true
  adb reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
  adb shell rm -f /data/local/tmp/vp_net_check >/dev/null 2>&1 || true
}
trap cleanup EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
adb shell touch /data/local/tmp/vp_net_check
# Android 17 local network permission: granted for the run so its dialog does not cover the screen.
adb shell pm grant com.vibeplayer android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || echo "(could not pre-grant ACCESS_LOCAL_NETWORK; allow it when asked)"
echo "phone sees http://127.0.0.1:$PORT -> this PC ($SERVE)"

say "Device checks (P13)"
set +e
python3 tools/devcheck/run.py --only p13_http_link,p13_hls,p13_folder_next,p13_m3u_channels,no_fatal --out "$OUT/p13"
RC=$?
set -e
say "Done"
echo "Results: $OUT/p13 (server requests in $OUT/server.log)"
exit $RC
