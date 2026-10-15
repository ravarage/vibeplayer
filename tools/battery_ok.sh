#!/bin/bash
# Exit 0 when the phone may be used for tests: on a charger, or battery at MIN% or more (default 15).
# The user needs at least 10% left in the morning. Exit 1 = stop phone work and report.
export PATH=/home/rava/vibeplayer/tools/bin:$PATH
MIN=${1:-15}
b=$(timeout 20 adb shell dumpsys battery 2>/dev/null) || { echo "battery: no device"; exit 2; }
level=$(echo "$b" | awk '/ level:/{print $2; exit}')
powered=$(echo "$b" | grep -cE '(AC|USB|Wireless) powered: true')
echo "battery: ${level}% powered=$powered"
[ "$powered" -gt 0 ] && exit 0
[ "${level:-0}" -ge "$MIN" ] && exit 0
echo "battery below ${MIN}% on battery: stop phone work"; exit 1
