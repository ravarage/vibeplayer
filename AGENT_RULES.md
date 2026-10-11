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
- Agent column: tool plus account, e.g. `agy-first`, `agy-second`, `codex`.
- Statuses: `TODO`, `IN PROGRESS`, `DONE`, `BLOCKED`. Only the reviewer edits "Last reviewed commit".
- Stay inside the task. Ideas and follow-ups go in the PROGRESS.md note, not into code.

## Hard limits
- Never change the fixed tech decisions in SPEC.md. If something seems wrong, write it in BLOCKERS.md,
  set the task to `BLOCKED` and stop.
- Never edit acceptance criteria to make a task pass. Never mark a task DONE that was not run on the device.
- Never paste long build logs into files. Summarise errors in a few lines.
- No `-O3` or `-Ofast`. Tuning flags only in P9.
- Do not bump pinned versions (ARCHITECTURE.md) outside a task that says so.
- Do not add dependencies beyond what the task needs; note every new one in the PROGRESS.md note.
- Needs sudo, a missing device, missing test clips, or a decision by the user: add a BLOCKERS.md
  entry and stop. Do not work around it.
- Do not edit the USER FEEDBACK section; only the user writes there. Feedback there outranks task text.

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

## Code conventions
- Follow ARCHITECTURE.md for packages, threading and state flow. If the code must deviate, update
  ARCHITECTURE.md in the same commit and say so in the note.
- Log tags: `VP` (app), `VP-Perf` (timings), `mpv` (engine log).
- Never call blocking mpv functions on the main thread.
