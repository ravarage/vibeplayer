#!/bin/bash
# B-08 comparison: count opens that never reach playback-restart.
# usage: tools/stall_test.sh <label> <opens> <file in /sdcard/Movies/vibeplayer-test> [extra am-start args...]
# example: tools/stall_test.sh C1 40 ichiban.mp4 --es probe_vo gpu-next --es probe_renderer vulkan
# Each open: force-stop (or, with MODE=back, leave the player with BACK and stay in the same process), start by intent, wait up to 8 s for "playback-restart" in logcat.
# Writes <label>.log (per open result) and <label>_stall_<n>.txt (last 40 mpv lines of each stall)
# into $OUT (default /tmp/vp_stall). Does not send touch input. Run tools/wake.sh semantics inside.
export PATH="$(cd "$(dirname "$0")" && pwd)/bin:$PATH"
label=$1; n=$2; file=$3; shift 3
OUT=${OUT:-/tmp/vp_stall}; mkdir -p "$OUT"
url="file:///sdcard/Movies/vibeplayer-test/$file"
ok=0; stall=0; recovered=0
: > "$OUT/$label.log"
for i in $(seq 1 "$n"); do
  # do not take the screen from the user: wait while another app is in front on an awake screen
  while :; do
    awake=$(timeout 10 adb shell dumpsys power | grep -c 'mWakefulness=Awake')
    top=$(timeout 10 adb shell dumpsys activity activities | grep -oE 'topResumedActivity=.*' | grep -oE '[a-zA-Z0-9_.]+/[A-Za-z0-9_.$]+' | head -1)
    case "$awake:$top" in 0:*|1:com.vibeplayer/*|1:ginlemon*|1:) break;; esac
    echo "open $i: waiting, $top is in front" >&2; sleep 20
  done
  if [ "${MODE:-forcestop}" = back ]; then
    # reopen inside the running process: leave the player with BACK (only when it is in front)
    if [ "${top#com.vibeplayer/}" != "$top" ] && [ "${top%PlayerActivity}" != "$top" ]; then timeout 10 adb shell input keyevent KEYCODE_BACK; sleep 1.5; fi
  else
    timeout 10 adb shell am force-stop com.vibeplayer
  fi
  timeout 10 adb logcat -c
  timeout 10 adb shell input keyevent KEYCODE_WAKEUP; timeout 10 adb shell wm dismiss-keyguard
  timeout 15 adb shell "am start -a android.intent.action.VIEW -d '$url' -t 'video/*' -n com.vibeplayer/.ui.player.PlayerActivity $*" >/dev/null
  res=STALL; t0=$(date +%s.%N); pid=""
  for k in $(seq 1 18); do
    sleep 0.5
    [ -z "$pid" ] && pid=$(timeout 10 adb shell pidof com.vibeplayer | tr -d '\r')
    [ -z "$pid" ] && continue
    L=$(timeout 10 adb logcat -d --pid "$pid" -s VP-Perf:I VP:E)
    if echo "$L" | grep -q 'B-08 watchdog'; then res=STALL; break; fi
    if echo "$L" | grep -q 'playback-restart'; then res=OK; break; fi
  done
  dt=$(python3 -c "import time,sys; print(round(time.time()-float(sys.argv[1]),1))" "$t0")
  if [ $res = STALL ]; then
    stall=$((stall+1))
    # lines of this run only (logcat was cleared before the open and is filtered by pid)
    timeout 15 adb logcat -d -v time --pid "$pid" -s mpv:V VP:V | grep -vE 'onPropertyChange: (time-pos|demuxer|cache)' | grep -B40 -m1 'B-08 watchdog' > "$OUT/${label}_stall_$i.txt"
    [ -s "$OUT/${label}_stall_$i.txt" ] || timeout 15 adb logcat -d -v time --pid "$pid" -s mpv:V VP:V | tail -40 > "$OUT/${label}_stall_$i.txt"
    timeout 15 adb logcat -d -v time | grep -iE 'vulkan|adreno|qgl|tombstone|FATAL EXCEPTION|ANR in|F DEBUG' | tail -40 > "$OUT/${label}_stall_${i}_other.txt"
    # give the safety net time, then see whether it recovered
    for k in $(seq 1 16); do sleep 0.5; if timeout 10 adb logcat -d --pid "$pid" -s VP-Perf:I | grep -q 'playback-restart'; then recovered=$((recovered+1)); res="STALL->RECOVERED"; break; fi; done
  else ok=$((ok+1)); fi
  echo "$label open $i $res ${dt}s pid=$pid hwdec=$(timeout 10 adb logcat -d --pid "$pid" -s VP:I | grep -oE 'hwdec-current = [a-z-]+' | tail -1 | cut -d' ' -f3) $(timeout 10 adb logcat -d --pid "$pid" -s mpv:V | grep -oE "VO: \[[a-z-]+\]|Initializing GPU context '[a-z]+'" | sort -u | tr '\n' ' ')" | tee -a "$OUT/$label.log"
done
timeout 10 adb shell am force-stop com.vibeplayer
timeout 10 adb shell input keyevent KEYCODE_HOME
echo "$label RESULT file=$file opens=$n ok=$ok stalls=$stall recovered_by_watchdog=$recovered rate=$(python3 -c "print(round(100*$stall/$n,1))")%" | tee -a "$OUT/$label.log"
