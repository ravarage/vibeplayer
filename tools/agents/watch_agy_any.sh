#!/bin/bash
# Exits with one line as soon as any agy run ends, or any agy run is idle (no child process and no
# file change in its checkout for N minutes). usage: watch_agy_any.sh [idle_minutes]
N=${1:-15}
declare -A idle
start=$(pgrep -f '^/home/rava/.local/bin/agy --model' | sort | tr '\n' ' ')
[ -z "$start" ] && { echo "NO AGY RUNNING $(date +%H:%M)"; exit 0; }
while true; do
  sleep 60
  for p in $start; do
    if ! kill -0 $p 2>/dev/null; then echo "AGY RUN ENDED pid=$p $(date +%H:%M)"; exit 0; fi
    cwd=$(readlink /proc/$p/cwd)
    kids=$(pgrep -P $p | wc -l)
    recent=$(find "$cwd" -path "$cwd/native" -prune -o -path '*/build' -prune -o -path '*/.gradle' -prune -o -type f -mmin -1 -print 2>/dev/null | head -1)
    if [ "$kids" -eq 0 ] && [ -z "$recent" ]; then idle[$p]=$(( ${idle[$p]:-0} + 1 )); else idle[$p]=0; fi
    if [ "${idle[$p]}" -ge "$N" ]; then echo "AGY IDLE pid=$p cwd=$cwd for ${N} min $(date +%H:%M)"; exit 0; fi
  done
done
