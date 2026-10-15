# Android 17 background-audio hardening (P10-10)

Source: https://developer.android.com/about/versions/17/changes/bg-audio

## Rules (as read 2026-10-15)

- All apps on Android 17: background audio interaction needs a visible activity (PiP counts) or a foreground
  service that is not `SHORT_SERVICE`.
- targetSdk 37 (this app): the foreground service must also have while-in-use (WIU) capability. WIU comes from
  a service started while the app is visible, or through an exemption: notification click, widget, media key
  event from an external device, system-started. A background-started FGS generally has no WIU.
- Failures are silent: `AudioTrack.write` silenced (mpv ao=audiotrack), `requestAudioFocus` returns
  `AUDIOFOCUS_REQUEST_FAILED`, `setStreamVolume` and friends ignored. Running service alone is not enough.
- Guidance: keep the FGS alive through transient failures (< 10 min); stop it on permanent end (AUDIOFOCUS_LOSS,
  media key pause, completion without auto-play).

App facts: manifest has the `mediaPlayback` service type and both FOREGROUND_SERVICE permissions.
`PlayerViewModel` calls `startForegroundService` while the activity is visible (WIU) and binds.
`PlaybackService` is a plain `Service` with a media3 `MediaSession` (not `MediaSessionService`).
While bound (activity, PiP) `boundClients > 0` and the service stays foreground. With nobody bound it stays
foreground while playing, leaves the foreground (detach) when paused, and stops after 10 min paused.

## Audit

| # | Path | Before | Verdict | Rule / reasoning | Fix |
|---|---|---|---|---|---|
| 1 | Playback in the activity, incl. PiP and mini-player auto-advance | bound, FGS from visible start | compliant | visible activity incl. PiP is exempt | none |
| 2 | Screen off / app backgrounded while playing | FGS started while visible stays foreground | compliant | FGS WIU from a visible start persists | none |
| 3 | Notification play/pause button | `PendingIntent.getService` -> `onStartCommand` calls `startForeground` (MEDIA) before `togglePause` | compliant | notification click is a WIU exemption; foreground first | none |
| 4 | Notification next/previous/seek buttons | same `onStartCommand` path, `skipToNeighbour` | compliant | notification click exemption | none |
| 5 | Headset / lock screen / system media controls: play | adapter `handleSetPlayWhenReady` -> `engine.play()`; the state collector requested focus BEFORE `startForeground`, so a detached (paused, unbound) service got `REQUEST_FAILED` and paused again | non-compliant | focus request and audio need an FGS; media key start is an exemption only once `startForeground` runs | `prepareAudioResume()` starts the FGS before `engine.play()`; the collector starts it before `requestAudioFocus` |
| 6 | Media key / lock screen next, previous | `skipToNeighbour` -> `openVideo(playAfterLoad)` with no FGS if detached | non-compliant | same | `skipToNeighbour` ensures the FGS first |
| 7 | Auto-advance to the next file, screen off | FGS (WIU from the visible start) running; EOF pause abandons focus, the next load re-requests it | compliant | FGS with WIU is live; the 1 s stop check is skipped while a load runs | none |
| 8 | Resume after `AUDIOFOCUS_GAIN` (transient loss: call, navigation prompt) | the loss paused, then `scheduleStopCheck` detached the FGS (unbound, paused); the GAIN resume ran with no FGS | non-compliant | guidance: keep FGS through transient loss (< 10 min); a background-started FGS has no WIU | `shouldBeInForeground(..., resumeOnFocusGain)` keeps the FGS while a transient-loss resume is pending; `ensureForegroundForResume` before `play()` as a safety net |
| 9 | Permanent focus loss (`AUDIOFOCUS_LOSS`) | pauses, detaches, 10 min timeout | compliant | guidance says stop, but no audio starts; user resume goes through paths 3 or 5 | none (service kept so the notification stays) |
| 10 | Resume after the 10 min paused timeout stopped the service | service gone, notification removed; only the activity (visible, `startForegroundService`) restarts it | compliant | new FGS from a visible activity has WIU | none |
| 11 | Resume after the user paused and the service detached (unbound) | notification button: path 3. Media key: path 5 | see 3, 5 | | fixed with 5 |
| 12 | `startForeground` fails (not allowed) on resume | `Log.e` only; audio would start silenced | non-compliant (silent failure) | failures are silent | resume is refused: the engine is paused again and `Audio focus denied (foreground=false)` is logged |
| 13 | Becoming-noisy pause, `setDuck` (mpv volume) | no AudioManager call | compliant | not restricted | none |
| 14 | Volume gestures / hardware keys (`setStreamVolume`) | only in `PlayerActivity` while visible (also PiP) | compliant | visible activity; the service never touches stream volume | none |
| 15 | Benchmark runs (no foreground notice) | bind only | compliant | the benchmark activity is visible | none |
| 16 | Media3 `MediaSessionService` migration | not done | n/a | page recommends it, plain FGS is the documented alternative | not required |

## Device test plan (phone needed; not run in this task)

Follow AGENT_RULES.md (phone_idle.sh, wake.sh, media volume stays 0).

1. `adb shell cmd audio set-enable-hardening throw` (loud: focus/volume throw, writes return errors); also `enable`.
   Restore with `adb shell cmd audio set-enable-hardening disable` afterwards.
2. `adb logcat -c; adb logcat -s VP AudioHardening AudioService` in the background; `adb shell dumpsys audio | grep -i -A3 AudioHardening`.
   `level: full` = FGS without WIU, `level: partial` = no FGS. Neither may appear for com.vibeplayer in a case below.
3. Cases (each: audio must be audible/`dumpsys audio` shows the app as focus owner and playing, no AudioHardening line,
   no `Audio focus denied` in VP log):
   a. Play a file, press Home, screen off 1 min, wake: still playing.
   b. Pause from the notification, wait 15 s (service detaches), press the notification play: resumes.
   c. Same pause, then `adb shell input keyevent KEYCODE_MEDIA_PLAY` (or headset): resumes (path 5).
   d. `KEYCODE_MEDIA_NEXT` / `PREVIOUS` while paused and detached (path 6).
   e. Auto-advance a short clip to the next file with the screen off (path 7).
   f. Transient focus loss: start another player or call, then release; ours resumes (path 8). VP log shows no detach during the loss.
   g. Wait the 10 min timeout (or shorten temporarily), then open the app and play (path 10).
   h. PiP playback and auto-advance in PiP (path 1).
4. Compare with `disable` to separate hardening failures from other bugs.
