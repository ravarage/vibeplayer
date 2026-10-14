#!/bin/bash
# Is the user using the phone? Exit 0 = phone free for tests, 1 = the user is active (wait).
# "Active" = screen on and a foreground app that is not Vibe Player, the launcher or the lock screen,
# or any touch input in the last 3 minutes that our tools did not cause. Agents call this before
# every on-device step and wait (poll every 60 s) while it returns 1. Overnight the user may wake up
# and pick up the phone at any time; never take it from them.
PATH=$(dirname "$0")/bin:$PATH
wake=$(timeout 10 adb shell dumpsys power 2>/dev/null | grep -oE 'mWakefulness=[A-Za-z]+' | head -1)
focus=$(timeout 10 adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)
if [ "$wake" != "mWakefulness=Awake" ]; then echo "free: screen off"; exit 0; fi
case "$focus" in
  *com.vibeplayer*|*ginlemon.flower*|*launcher*|*NotificationShade*|*Keyguard*) echo "free: $focus"; exit 0;;
  *) echo "busy: user has ${focus##* } in front"; exit 1;;
esac
