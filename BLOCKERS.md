# BLOCKERS

Problems an agent cannot or must not solve alone: needs sudo, the device, a user decision, or would
change a fixed tech decision. Add an entry, set the task to `BLOCKED` in PROGRESS.md, commit, stop.
Newest entry first. Keep entries short; no long logs.

## Template
```
## B-<nn> <short title>            Status: OPEN | RESOLVED
- Task: <task ID>
- Agent / date:
- Problem: <what fails, 1 to 3 lines; key error line only>
- Tried: <what was attempted and the result>
- Options: <A / B, with trade-offs>
- Needs from user: <exact decision or action>
- Resolution: <filled when resolved, with commit hash>
```

## Open

## B-08 Video sometimes never starts: MediaCodec decoder stalls after the first frame            Status: RESOLVED (recovery reliable; root stall remains a known MediaCodec/driver behaviour)
- Task: found during P4-11 (not one of its review items); affects every playback task
- Agent / date: CLAUDE-A / 2026-10-13
- Problem: opening `ichiban.mp4` (H.264, resume position, default Efficient path B, `hwdec=mediacodec`) stays black in roughly 1 of 5 to 1 of 12 opens: 1/12, 1/3, 4/20 in three runs; also 1/20 with `--es hwdec mediacodec-copy`, so it is not only the aimagereader path. mpv logs `Using hardware decoding (mediacodec)` and then never prints `VO: [gpu] 1920x1080 mediacodec` or `playback-restart`. It never recovers; mpv commands (`getProperty`, detach, destroy) then block for good.
- Evidence: ANR dump (dropbox `data_app_anr`, symbolized with the unstripped libs): mpv core thread in `run_playloop -> write_video -> lavc_process -> ff_decode_receive_frame -> mediacodec_receive_frame (mediacodecdec.c:515) -> ff_mediacodec_dec_receive -> dequeueOutputBuffer`. That is FFmpeg 9.0.2's `while (1)` "no input space, block for an output frame" loop, which repeats while the codec gives neither input space nor output. `VP-Command` thread waits in `mpv_get_property_string` (`updateTracks`). Not related to: the debug overlay (happens without it), reopen speed (1.2 s and 6 s gaps), the resume position (same position works on retry; keyframes every 0.5 s), thumbnail extraction (none running), a leftover engine (previous handle destroyed 7 s earlier).
- Tried: bounded the main-thread detach wait (P4-11 commit) so the app no longer shows "isn't responding"; leaving and reopening then works. The stuck engine's threads and its codec stay alive (spinning every 8 ms) until the process dies. No native fix attempted.
- Options: A) find the cause in the MediaCodec path (next discriminators: `msg-level=ffmpeg=trace` to see queued input / output buffers before the stall; a load without `start=` to see whether hr-seek frame dropping is needed; the GLES-only libraries from before the Vulkan-enabled build became default on 2026-10-13); B) add an engine watchdog (no `playback-restart` N s after `file-loaded` -> abandon the handle and reload once with `hwdec=no`), which hides the stall but leaks the stuck handle.
- Needs from user: nothing to decide yet; reviewer picks A or B. Not known whether this is older than today's commits.
- Resolution (CLAUDE-V, 2026-10-14, build 1cc3d61, engine in PlaybackService, default path, driver vulkan.adreno.so sha256 c0b92d25178c8b21): 60 cold opens of ichiban.mp4 (tools/stall_test.sh, force-stop each time): 55 OK, 5 stalls (8.3 %, opens 10, 16, 44, 54, 59), 5 of 5 recovered by the watchdog (playback-restart seen 3-7 s after the watchdog line, stall opens took 6.8-7.0 s), 0 permanent black screens, 0 ANR (no new data_app_anr dropbox entry for com.vibeplayer on 2026-10-14; last ones are from 2026-10-13). Earlier comparison runs (40 opens each unless noted): A current libs GLES zero-copy 12 stalls, B old GLES-only libs 6, C Vulkan copy-back gpu 1 and gpu-next 4, D GLES copy-back 0 and 0, A_hevc 0/20, A_vp9 0/20; all recovered. The stall itself (MediaCodec output never arrives after the first frame) is not fixed; the watchdog is the fix of record. Logs: /tmp/vp_stall60/P60.log, /tmp/vp_stall/*.log.
- Re-check (CLAUDE-V2, 2026-10-15, driver c0b92d25178c8b21, docs/BENCHMARK.md "P6-08 start-stall re-check"): 20 cold opens per configuration: table row (copy-back, Vulkan gpu-next) 1 stall, GLES zero-copy 4, GLES copy-back 1, Vulkan gpu copy-back 0; release P7-02 runs 1 in 10 (h264_1080p) and 0 in 10 (ichiban). Every stall recovered by the watchdog (6.3-9.8 s), none stayed black. Status unchanged: RESOLVED, recovery is the fix of record.

## Resolved

## B-07 Shared phone foregrounded in Telegram            Status: RESOLVED
- Task: P4-11
- Agent / date: CODEX / 2026-10-13
- Problem: Code fixes build, but the phone's resumed activity is Telegram. The required pinch/reset, mini-player, remembered-track, folder-memory, auto-next and confirmed debug-overlay exit checks cannot be completed without taking over the shared screen.
- Tried: Installed the build, checked visible video and immediate controls, confirmed a double-tap exact −10 s seek, and ran 20 player launch/exit cycles; logcat had no `FATAL EXCEPTION` for `com.vibeplayer`. Debug stats logs did not confirm that the overlay was open for those exits. Activity Manager then showed Telegram resumed, so stopped touch input.
- Options: A) make the phone available for the remaining checks; B) leave those acceptance items unverified.
- Needs from user: Let CODEX know when the phone is available for a short P4-11 verification pass.
- Resolution: user said the phone is free (2026-10-13); adb sees the redwood device and this verification pass is proceeding.

## B-06 Phone locked during final P4-12 PiP checks            Status: RESOLVED
- Task: P4-12
- Agent / date: CODEX / 2026-10-13
- Problem: After sending KEYCODE_HOME during the 45.979 s `multi_track.mkv` run, the phone entered PiP per WindowManager logs (16:9 params), then dozed to its lock screen before a PiP video screenshot could be inspected. Subsequent wake, swipe and relaunch attempts produced black screenshots.
- Tried: Captured and inspected fullscreen next/previous screenshots with `tools/check_video.py` passing; loaded `multi_track.mkv`; sent KEYCODE_HOME; checked PiP system transition logs; tried KEYCODE_WAKEUP, swipe-up and relaunch multiple times.
- Options: Unlock/wake the phone and repeat the remaining PiP, setting-off, last-file and resume checks.
- Needs from user: Unlock the phone display so the device checks can continue.
- Resolution: not a blocker. The phone is on battery, so it sleeps after 30 s idle; `tools/wake.sh` wakes it and dismisses the lock screen (reviewer, 2026-10-13).

## B-05 Phone disconnected before final aspect checks            Status: RESOLVED
- Task: P4-06
- Agent / date: CODEX / 2026-10-13
- Problem: The phone went offline during the post-force-stop reopen check. `adb devices` is empty and `adb reconnect offline` did not restore it. The on-device test left `ichiban.mp4` saved as `4:3`; Case Closed was returned to `fit` through the controls.
- Tried: Rechecked `adb devices` and ran `adb reconnect offline`; no device appeared. Before disconnect, cycled and inspected all five modes on Case Closed (4:3) and ichiban (wide), with `tools/check_video.py` passing in every mode.
- Options: Reconnect the phone via USB or Wi-Fi adb, then reopen ichiban to verify the saved 4:3 mode, set it to fit through the aspect button, and force-stop/reopen to confirm fit and that Case Closed remains fit.
- Needs from user: Reconnect the phone so the remaining UI reset and reopen checks can be completed.
- Resolution: the user moved the phone to the 5 GHz network and unplugged USB, so its address changed (now 192.168.1.3, in tools/bin/phone_wifi). The reviewer reconnected and completed the remaining P4-06 checks on 2026-10-13.

## B-04 Device Vulkan 1.1 below libplacebo 1.2 requirement            Status: RESOLVED
- Task: P6-00
- Agent / date: AGY-2C / 2026-10-12
- Problem: Device Adreno 642L reports Vulkan 1.1.128, which is below libplacebo's requirement PL_VK_MIN_VERSION (Vulkan 1.2). Probe with vo=gpu-next, gpu-api=vulkan, gpu-context=androidvk fails: libplacebo reports "Found no suitable device, giving up."
- Tried: Built libmpv with Vulkan and shaderc (NDK r30); confirmed gpu-context=androidvk and gpu-api=vulkan in option choices; probed on device with com.vibeplayer.vk.
- Options: Per TASKS.md P6-00, re-scope P6-02, P6-04, and P6-05 to compare vo=gpu-next vs vo=gpu on OpenGL ES. Both vo=gpu and vo=gpu-next confirmed fully working on GLES.
- Needs from user: Non-blocking; reviewer acknowledgement of P6 re-scoping to gpu-next vs gpu on GLES.
- Resolution: accepted by the reviewer 2026-10-12. Vulkan cannot work on this device (driver is Vulkan 1.1, libplacebo needs 1.2). P6 compares `gpu-next` vs `gpu` on OpenGL ES. The Vulkan build patch is kept as `native/patches/optional/0004-vulkan-shaderc.patch` and is not applied by default (it doubles libmpv.so for no use).

## B-03 Device disconnected over adb            Status: RESOLVED
- Task: P4-08
- Agent / date: AGY-2B / 2026-10-11
- Problem: Device disconnected over adb (`2fbe93cc: connection terminated: read failed`, `adb devices` shows empty).
- Tried: Checked `lsusb`, `adb devices`, `adb reconnect`; device not present on USB.
- Options: User reconnects phone redwood to host via USB / adb.
- Needs from user: Reconnect phone to USB / enable USB debugging.
- Resolution: not a fault: the user unplugged the phone to charge it (2026-10-11). P4-08's device check is deferred until P4-06 writes real history; the startup code that seeded fake history rows was removed in the merge.

## B-02 Missing test clips (AV1 4K and multi-track AC3/PGS)            Status: RESOLVED
- Task: P0-02
- Agent / date: AGY-1 / 2026-10-11
- Problem: No clips in /sdcard/Movies/vibeplayer-test match 'AV1 stress test (4K)' and '2+ audio tracks (one AC3/EAC3/DTS), 2+ subtitle tracks (one PGS)'.
- Tried: Probed all 9 clips on device with ffprobe. All AV1 files are <= 1080p; audio tracks are AAC/Opus/MP3/Vorbis; subtitles are ASS.
- Options: A) user pushes av1_4k and multi_track clips to /sdcard/Movies/vibeplayer-test; B) create/substitute clips during P1-07 and P4-03/04.
- Needs from user: Non-blocking: provide matching 4K AV1 clip and multi-track (AC3/DTS + PGS) clip when possible.
- Resolution: reviewer pushed `av1_4k.mp4` and `multi_track.mkv` to the device on 2026-10-11; SPEC.md clip table updated.

## B-01 Android Studio missing            Status: RESOLVED
- Task: P0-01
- Agent / date: AGY-1 / 2026-10-11
- Problem: Android Studio missing, so the required build JDK (JetBrains Runtime bundled with Android Studio) is unavailable; only OpenJDK 27 is installed.
- Tried: Searched /opt, home, JetBrains Toolbox, pacman, flatpak; no 'jbr' directory exists.
- Options: A) user installs Android Studio (e.g. tarball to ~/android-studio, no sudo needed, or AUR package); B) user relaxes the JDK rule.
- Needs from user: install Android Studio and tell the agent its path.
- Resolution: user installed Android Studio at /home/rava/.local/share/JetBrains/Toolbox/apps/android-studio (JBR 25.0.3 in jbr/) on 2026-10-11; verified and configured org.gradle.java.home in ~/.gradle/gradle.properties.

