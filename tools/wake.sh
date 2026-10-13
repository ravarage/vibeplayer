#!/bin/bash
# Wake the phone and dismiss the (non-secure) lock screen. Run before every on-device check:
# on battery the screen turns off after 30 s of idle and the phone locks.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
sleep 1
adb shell dumpsys power | grep -q 'mWakefulness=Awake' && echo "phone awake" || { echo "phone did NOT wake"; exit 1; }
