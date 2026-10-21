#!/bin/bash
# Why can the phone shell reach a server but Vibe Player cannot? About 30 s, read-only. Starts its own server on
# 8765 (adb reverse) unless one is already up, then compares the shell and the app's user id against loopback,
# the internet, and prints the phone's per-app network rules for the app.
set -uo pipefail
cd "$(dirname "$0")/.."
PKG=com.vibeplayer
PORT=8765
OUT=$(mktemp -d /tmp/vp_net_why.XXXX)
mkdir -p "$OUT/srv"
python3 tools/net_test_server.py "$OUT/srv" "$PORT" > "$OUT/server.log" 2>&1 &
SV=$!
trap 'kill $SV 2>/dev/null; adb reverse --remove tcp:$PORT >/dev/null 2>&1' EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
UID_=$(adb shell dumpsys package $PKG | tr -d '\r' | grep -o 'userId=[0-9]*' | head -1 | cut -d= -f2)
echo "app uid: $UID_"

# reach <label> <host> <port> [run-as]: nc's exit code and message. "Timeout" means the packets are dropped;
# "refused" means the host answered (reachable, nothing listening); rc=0 means connected.
reach() {
  local cmd="out=\$(toybox nc -w 5 -W 2 $2 $3 </dev/null 2>&1 >/dev/null); echo \"rc=\$? \$out\""
  local r
  if [ -n "${4:-}" ]; then r=$(adb shell "run-as $PKG sh -c '$cmd'" 2>&1 | tr -d '\r' | tail -1)
  else r=$(adb shell "$cmd" 2>&1 | tr -d '\r' | tail -1); fi
  printf '  %-34s %s\n' "$1" "$r"
}
echo "connections:"
reach "shell -> 127.0.0.1:$PORT (adb)" 127.0.0.1 $PORT
reach "app   -> 127.0.0.1:$PORT (adb)" 127.0.0.1 $PORT app
reach "shell -> internet 1.1.1.1:443" 1.1.1.1 443
reach "app   -> internet 1.1.1.1:443" 1.1.1.1 443 app
GW=$(adb shell ip route 2>/dev/null | tr -d '\r' | grep -o 'via [0-9.]*' | head -1 | cut -d' ' -f2)
[ -n "$GW" ] && { reach "shell -> router $GW:80" "$GW" 80; reach "app   -> router $GW:80" "$GW" 80 app; }

echo "phone settings:"
echo "  local network permission: $(adb shell dumpsys package $PKG | tr -d '\r' | grep -o 'ACCESS_LOCAL_NETWORK: granted=[a-z]*' | head -1)"
echo "  INTERNET permission: $(adb shell dumpsys package $PKG | tr -d '\r' | grep -o 'android.permission.INTERNET: granted=[a-z]*' | head -1)"
echo "  restricted networking mode: $(adb shell settings get global restricted_networking_mode 2>&1 | tr -d '\r')"
echo "  data saver: $(adb shell cmd netpolicy get restrict-background 2>&1 | tr -d '\r')"
echo "  netpolicy lines for the uid:"
adb shell dumpsys netpolicy 2>/dev/null | tr -d '\r' | grep -i "$UID_" | head -8 | sed 's/^/    /'
echo "  connectivity lines for the uid (firewall chains, blocked reasons):"
adb shell dumpsys connectivity 2>/dev/null | tr -d '\r' | grep -iE "$UID_|blocked|firewall|local.?net" | head -15 | cut -c1-200 | sed 's/^/    /'
echo "  appops:"
adb shell cmd appops get $PKG 2>/dev/null | tr -d '\r' | grep -iE 'net|internet|local' | sed 's/^/    /'
