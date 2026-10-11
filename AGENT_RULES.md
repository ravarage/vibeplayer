# AGENT RULES

For every implementing agent. Read this file, then SPEC.md, ARCHITECTURE.md, PROGRESS.md and the
USER FEEDBACK section of TASKS.md before touching anything.

## Start of every session
1. Run `git status` and `git log -5`.
2. If there are uncommitted changes or a task marked IN PROGRESS in PROGRESS.md, another agent was
   interrupted. Inspect with `git diff`, read the task's notes in PROGRESS.md, and **finish that task
   first**. Do not throw away work (no `git reset --hard`, `git checkout .`, `git clean`, `git stash drop`).
3. Check BLOCKERS.md. If an open blocker covers the next task, stop and report it.

## Working a task
- Only one agent works at a time unless TASKS.md marks tasks as parallel safe.
- Parallel agents: the main line runs in `/home/rava/vibeplayer` on `main`. A parallel agent works
  only in its own git worktree and branch (`par/<name>`), takes only the tasks listed for that track
  in TASKS.md, never commits to `main` and never edits files outside its track's paths (PROGRESS.md
  rows for its own tasks excepted). The reviewer merges the branch.
- Work one task at a time, in order (lowest unfinished ID first).
- Before starting, set the task to `IN PROGRESS` with your agent name in PROGRESS.md.
- Build after every task. A task is done only when it builds **and** meets its acceptance criteria
  on the device.
- One final commit per task; its message starts with the task ID: `P1-03: attach surface lifecycle`.
- Commit often enough that an interruption loses little work. Intermediate commits use
  `P1-03 wip: <what works>` and update the task's PROGRESS.md note with **the next step**.
  WIP commits must compile unless the note says otherwise.
- Update PROGRESS.md after each task (status `DONE`, agent, notes) and commit it together with the
  task. Write `pending` in the Commit column; never amend to insert a hash. The next session's first
  commit (or the reviewer) replaces `pending` with the real short hash.
- Agent column: the name given in your prompt: `AGY-1`, `AGY-2` or `CODEX`.
- Statuses: `TODO`, `IN PROGRESS`, `DONE`, `BLOCKED`. Only the reviewer edits "Last reviewed commit".
- Stay inside the task. Ideas and follow-ups go in the PROGRESS.md note, not into code.

## Checking video on the device
Log lines do not prove a picture is on screen. Any acceptance item that involves video being shown
(playback, rotation, resume, fallback, subtitles, zoom, PiP) must pass `tools/check_video.py` while the
clip plays (`--still` when paused): it screencaps the phone and fails on a black or frozen screen.
State its output line in the PROGRESS.md note. For subtitles, overlays and UI, also save a screenshot
(`adb exec-out screencap -p > /tmp/x.png`) and look at it. Leave media volume as you found it.

The phone is the user's daily phone and its alarm must ring. Only the media stream (STREAM_MUSIC) may
be changed. Never change alarm, ring or notification volume, ringer mode, Do Not Disturb, the clock
app or its alarms, system settings, or reboot the phone.

## Hard limits
- Never change the fixed tech decisions in SPEC.md. If something seems wrong, write it in BLOCKERS.md,
  set the task to `BLOCKED` and stop.
- Never edit acceptance criteria to make a task pass. Never mark a task DONE that was not run on the device.
- Never paste long build logs into files. Summarise errors in a few lines.
- No `-O3` or `-Ofast`. Tuning flags only in P9.
- Do not add dependencies beyond what the task needs; note every new one in the PROGRESS.md note.
- Needs sudo or a missing device: add a BLOCKERS.md entry and stop. A design decision: the user
  is away, so pick the option that best fits SPEC.md, record it as a non-blocking BLOCKERS entry for
  the reviewer, and continue. Do not work around it.
- Do not edit the USER FEEDBACK section; only the user writes there. Feedback there outranks task text.

## Dependency policy
Latest stable versions everywhere.
- During P0, look up the current latest stable version of each of these from official sources and
  record them in PROGRESS.md (Versions table) before using them: Android Gradle Plugin, Gradle,
  Kotlin, Compose BOM, AndroidX libraries (Room, Media/MediaSession, Activity, Lifecycle,
  Navigation), Baseline Profile / Macrobenchmark, NDK, CMake, mpv, FFmpeg, dav1d, libass, libplacebo,
  freetype, harfbuzz, fribidi.
- compileSdk and targetSdk: the latest stable Android API (the device runs Android 17).
- Build JDK: the JetBrains Runtime bundled with Android Studio.
- Do not use alpha, beta, RC or nightly versions unless a task explicitly says so. If the latest
  stable version of something fails to build, try the previous stable version and note it in
  PROGRESS.md.
- The mpv-android buildscripts pin library versions; bump them to the latest stable releases and
  record the final versions used.
- Once a version is recorded and working, do not change it again outside a task that says so.

## Commands
Filled in and kept current by the tasks that create them (P0-06, P0-07).

| Purpose | Command |
|---|---|
| Fetch native sources | `native/fetch.sh` |
| Build native (only if `native/` changed) | `native/build.sh` |
| Build debug app | `./gradlew :app:assembleDebug` |
| Install | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Launch | `adb shell am start -n com.vibeplayer/.MainActivity` |
| Play a test clip | `adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Movies/vibeplayer-test/h264_1080p.mp4 -t video/* com.vibeplayer` |
| Logs | `adb logcat -s VP VP-Perf mpv` |
| Is video really visible? | `tools/check_video.py` (exit 0 = visible, 1 = black/frozen) |

## Code conventions
- Follow ARCHITECTURE.md for packages, threading and state flow. If the code must deviate, update
  ARCHITECTURE.md in the same commit and say so in the note.
- Log tags: `VP` (app), `VP-Perf` (timings), `mpv` (engine log).
- Never call blocking mpv functions on the main thread.
