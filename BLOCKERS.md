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

_none_

## Resolved

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

