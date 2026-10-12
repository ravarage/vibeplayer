#!/bin/bash
# Watchdog for all background agents. Prints one line and exits on the first event:
#  - a new task commit on main
#  - agy idle: no child process and no file change in the repo for IDLE_MIN minutes
#  - codex idle: no child process and no new log output for IDLE_MIN minutes,
#    or stuck at "Reading additional input from stdin" (waiting for input) for 2 minutes
#  - an agent that was running at start is gone (agy only; codex exit is reported by its own task)
# usage: watch_all.sh [idle_minutes] [codex_log]
cd /home/rava/vibeplayer
IDLE_MIN=${1:-10}; CLOG=${2:-}
h=$(git rev-parse HEAD); ai=0; ci=0
agy_pid() { pgrep -f '^/home/rava/.local/bin/agy --model' | head -1; }
cdx_pid() { pgrep -f '^codex exec' | head -1; }
had_agy=$(agy_pid)
while true; do
  sleep 60
  if [ "$(git log --format=%s $h..HEAD | grep -cE '^P[0-9]-[0-9]+( fix| wip)?:')" -gt 0 ]; then echo "COMMIT"; git log --oneline $h..HEAD; break; fi
  a=$(agy_pid)
  if [ -n "$had_agy" ] && [ -z "$a" ]; then echo "AGY EXITED"; break; fi
  if [ -n "$a" ]; then
    kids=$(pgrep -P "$a" | wc -l)
    recent=$(/usr/bin/find app/src player/src tools PROGRESS.md BLOCKERS.md app/build/outputs -type f -mmin -2 2>/dev/null | head -1)
    if [ "$kids" -gt 0 ] || [ -n "$recent" ]; then ai=0; else ai=$((ai+1)); fi
    if [ "$ai" -ge "$IDLE_MIN" ]; then echo "AGY IDLE $ai min (no child process, no file change): probe quota, then restart it"; break; fi
  fi
  c=$(cdx_pid)
  if [ -n "$c" ] && [ -n "$CLOG" ] && [ -f "$CLOG" ]; then
    age=$(( $(date +%s) - $(stat -c %Y "$CLOG") ))
    ckids=$(pgrep -P "$c" | wc -l)
    if [ "$(wc -l < "$CLOG")" -le 2 ] && [ "$age" -ge 120 ]; then echo "CODEX WAITING FOR INPUT (log stuck at start): restart with </dev/null"; break; fi
    if [ "$ckids" -gt 0 ] || [ "$age" -lt 120 ]; then ci=0; else ci=$((ci+1)); fi
    if [ "$ci" -ge "$IDLE_MIN" ]; then echo "CODEX IDLE $ci min (no child process, no log output): check and restart"; break; fi
  fi
done
date +%H:%M
