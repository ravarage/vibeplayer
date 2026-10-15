#!/bin/bash
# Exits with one line when: all agy runs have finished, or any agy run is idle (no child process and
# no file change in its checkout) for N minutes. usage: watch_agy_all.sh [idle_minutes]
N=${1:-10}
declare -A idle
sleep 60
while true; do
  sleep 60
  pids=$(pgrep -f '^/home/rava/.local/bin/agy --model')
  [ -z "$pids" ] && { echo "ALL AGY RUNS FINISHED $(date +%H:%M)"; break; }
  for p in $pids; do
    cwd=$(readlink /proc/$p/cwd) || continue
    kids=$(pgrep -P "$p" | wc -l)
    recent=$(/usr/bin/find "$cwd" -maxdepth 6 -type f -mmin -2 -not -path '*/.git/*' 2>/dev/null | head -1)
    if [ "$kids" -gt 0 ] || [ -n "$recent" ]; then idle[$p]=0; else idle[$p]=$(( ${idle[$p]:-0} + 1 )); fi
    if [ "${idle[$p]}" -ge "$N" ]; then echo "AGY IDLE ${idle[$p]} min in $cwd (pid $p) $(date +%H:%M)"; exit 0; fi
  done
done
