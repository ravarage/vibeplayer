# devcheck - on-device regression checks

Run after every merge, with the phone on adb (Wi-Fi or USB, picked by `tools/bin/adb`) and the test clips in
`/sdcard/Movies/vibeplayer-test/`:

    python3 tools/devcheck/run.py                      # everything, ~15-20 min
    python3 tools/devcheck/run.py --only open_h264,pip # a subset
    python3 tools/devcheck/run.py --skip stall --stall-opens 10 --out /tmp/dc1
    python3 tools/devcheck/run.py --list

Needs Python 3 and Pillow. It wakes the phone, records app version / git commit / Vulkan driver hash, runs
the checks, prints a table, writes `summary.md` plus screenshots under `--out` (default
`/tmp/vp_devcheck/<time>`), restores media volume, speed 1x, aspect fit, original track choices, force-stops
the app and presses Home. Exit code 0 only if nothing FAILED (SKIP is not a failure; read the reason).
Look at the screenshots of any check you care about: the pixel test says "something moves", not "it looks right".

Unit tests (no phone): `python3 -m unittest discover tools/devcheck/tests`

## Safety
`safety.py` filters every adb call (default deny). Only: shell input tap/swipe/keyevent HOME,BACK,WAKEUP,APP_SWITCH,
`am start`/`force-stop` of com.vibeplayer, screencap/uiautomator/cat/rm of `/data/local/tmp/vp_*`, dumpsys of a
fixed list, `cmd media_session volume --stream 3` only, logcat read/clear, `ls` under /sdcard, pull of
`/data/local/tmp/vp_*`. Everything else (uninstall, pm clear, settings put, reboot, other packages, other volume
streams, pipes/redirects) raises `Refused` and the check FAILs.

## Checks and how each decides
| check | PASS when |
|---|---|
| open_h264 / open_hevc10 / open_vp9 / open_av1_4k / open_av1_ass | cold start (force-stop, logcat clear) logs `VP-Perf playback-restart` from the app PID within 25 s, then three screenshots show non-black (>3 %) and a changed-pixel ratio >0.2 % between consecutive shots; no `B-08 watchdog` line. av1_ass also opens the sw-AV1 Clevatess clip; ASS styling itself needs a human look at `open_av1_ass/*_ass.png` |
| hwdec | `onPropertyChange: hwdec-current = X` (debug build) or, as fallback, `Stats: hwdec=X` after a long-press on the title opens the debug overlay; X not in {"", no, none} for H.264, HEVC, VP9. SKIP if neither source exists |
| controls_tap | uiautomator shows/hides the pause button after one tap and the gesture layer logs `TAP` (image-diff fallback if the dump fails) |
| play_pause | `togglePause` logged on button tap, picture static (<0.2 % change) while paused, moving again after the second tap |
| double_tap | `Gesture detected: DOUBLE_TAP_RIGHT/LEFT` and `seekRelative 10 / -10 s` on multi_track.mkv (up to 3 tries each), picture moving |
| swipe_seek | 500 px swipe commits `deltaSec` in [8,24] (expect +20 at 40 ms/px), 300 px back swipe in [-16,-3], picture moving |
| brightness_volume | `LEFT_VERTICAL` / `RIGHT_VERTICAL` logged, music volume (STREAM_MUSIC) changed by the volume swipe, then restored; indicator `NN %` captured via uiautomator when possible (screenshots saved) |
| lock_unlock | lock icon -> `Unlock` button; horizontal swipe while locked produces no `HORIZONTAL` gesture; after Unlock it does |
| orientation_home | screenshot is landscape for the wide clip; Home enters PiP; expanding returns to a landscape moving picture |
| pip | Home logs PiP entered; `dumpsys activity activities` has a pinned com.vibeplayer task with bounds; bounds aspect within 10 % of dwidth/dheight; crop of the bounds is non-black and changes between shots; expand (double tap on the window, else `am start` fallback) leaves PiP and picture moves |
| pip_autoadvance | multi_track.mkv seeked near its end, Home to PiP, `Auto-play: ... ended -> <next in natural order>` within 40 s, `playback-restart`, still pinned, PiP crop moving |
| resume | after a periodic `Saved position >= 8 s`, force-stop and relaunch logs `Resume: resuming from N ms` within ~12 s of the saved value and picture moves |
| track_memory | for multi_track.mkv and Case Closed, audio and subtitle: pick another row in the sheet, `Persisted file ...` logged, force-stop, reopen: `Updated tracks ... aid/sid` equals the pick; original choice restored |
| folder_track_memory | choose the other audio language in Case Closed (`Persisted folder audio: lang=L`), then a sibling clip without per-file choice that has audio L opens on a track with lang L. SKIP if no conclusive sibling |
| speed | 1.5x chip logs `setSpeed`/`Persisted speed=1.5`; reopen shows speed 1.5; reset to 1x |
| aspect | five taps give setAspect fit-cycle (crop, stretch, 16:9, 4:3, fit; any rotation accepted), pictures non-black, ends on fit |
| prev_next | next, prev, prev, next from ichiban.mp4: `loadfile res=0 for <expected neighbour>` (natural order of the folder listing), playback-restart, title shows the file name |
| autoplay | swipe-seek to ~4 s before EOF; `Auto-play: X ended -> <next>` and next plays; last file in the folder logs `Auto-play: no next file` and nothing is loaded afterwards |
| browser | Continue Watching header, thumbnails of the test folder (luma mean>20, stddev>14 on >=70 % of rows, waits up to ~25 s), tapping ichiban.mp4 plays a moving picture |
| settings_version | gear opens settings, `Vibe Player <versionName>` (scrolls) matches `dumpsys package` |
| stall | N (default 5, `--stall-opens`) cold opens of ichiban.mp4 each reach playback-restart within 8 s |
| no_fatal | no `FATAL EXCEPTION` / `ANR in com.vibeplayer` / native crash seen in logcat or the crash buffer (scanned before every logcat clear and after each check) |

Manual (printed in summary.md, never faked): pinch zoom, haptics, audio quality.

## Known weak spots
Written without the phone in hand; first real run will need tuning.
- Double tap uses two concurrent `input tap` processes to land inside the 250 ms window; the gap is not controllable, hence 3 tries.
- Nearly every UI action depends on `uiautomator dump`; it can fail while a SurfaceView updates. The code falls back to dp-based coordinates for the top bar and centre buttons, but track/speed sheet rows and the browser have no fallback (SKIP/FAIL with the reason).
- Compose track rows must appear as clickable nodes in the dump; if they do not, track checks SKIP.
- hwdec, dwidth/dheight, speed and duration come from the debug-only `onPropertyChange` log. On a release build those checks degrade to SKIP or weaker evidence.
- PiP bounds parsing is written against several `dumpsys` formats but not verified on Android 17.
- Brightness is only restored by an opposite swipe (the app-level window brightness is not read back).
- Pixel tests cannot tell correct from wrong colours or ASS styling.

## Hooks that would make checks reliable
(App changes, not done here.)
1. `contentDescription`/testTag on every control: track rows ("audio_track_<id>"), speed presets, play/pause ("play_pause"), prev/next, lock/unlock, the seek bar, folder and video rows ("video_<name>"), thumbnail images ("thumb_<name>").
2. A release-safe `VP-Check` log line (tag `VP-Check`, no debug gating) with key=value: `state=playing|paused|ended pos_ms= dur_ms= hwdec= speed= aspect= aid= sid= path= pip=true|false`, emitted on every change and every 2 s. Removes the dependence on debug `onPropertyChange` lines.
3. Debug/instrumentation intent extras (honoured only when `BuildConfig.DEBUG` or a debug-menu flag): `start_ms` (seek to position after load), `debug_overlay=true` (stats overlay on), `action=pause|play|next|prev|seek:<ms>|aspect|speed:<x>` delivered via a broadcast receiver, so gestures can be tested separately from navigation.
4. A broadcast `com.vibeplayer.DEBUG_DOUBLE_TAP` (left/right) to exercise the skip path without relying on `input tap` timing.
5. Log the PiP rectangle (`PiP bounds=l,t,r,b`) from `onPictureInPictureModeChanged` / `setPictureInPictureParams`, and log `sourceRectHint`.
6. Log a `thumb=ok|black|missing name=` line when a list thumbnail is bound.
