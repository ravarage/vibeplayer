# TASKS

## USER FEEDBACK
_Only the user writes here. Agents: read before every session; this outranks task text._

-

---

Format: **ID Title** — description. *Files*: likely touched. *Accept*: checks on the device.
Clips are in `/sdcard/Movies/vibeplayer-test/` (SPEC.md). Tasks are sequential unless marked
**[parallel safe]**. "Overlay" means the debug overlay from P1-08.

## Parallel tracks
- **Main track** (`main`, /home/rava/vibeplayer): all tasks in order, skipping tasks done by a track below.
- **App track** (`par/app`, /home/rava/vibeplayer-worktrees/app) **[parallel safe]**: P0-07, P2-01..P2-09,
  P4-01, P4-08. Touches only Gradle files, `app/`, `player/build.gradle.kts` and its PROGRESS.md rows;
  nothing under `native/` or `player/src/main/cpp`. Uses the SDK at `~/Android/Sdk` via `local.properties`.
  Where an acceptance item needs playback, skip that item and say so in the note.
- **Platform track** (`par/platform`, /home/rava/vibeplayer-worktrees/platform) **[parallel safe]**: P10-01..P10-05.
  Platform features from the Android 17 research (Surface.setFrameRate, thermal adaptation, display cutout,
  back handling audit, exit diagnostics).


## P0 Native build and first playback

**P0-01 Toolchain and build JDK** — Record host tool versions. Locate Android Studio and point Gradle
at its bundled JetBrains Runtime (`org.gradle.java.home` in the user-level gradle.properties, not in
the repo). *Files*: PROGRESS.md (Versions). *Accept*: JBR path and version recorded; if Android Studio
is not installed, BLOCKERS entry asking the user to install it.

**P0-02 Device check and clip mapping** — Confirm the device over adb and record facts. The user's
clips in `/sdcard/Movies/vibeplayer-test/` keep their own file names: probe each one (codec, bit depth,
resolution, audio and subtitle tracks) and replace the file column of SPEC.md's test clip table with
the real names. *Files*: ARCHITECTURE.md (device facts), SPEC.md (test clips). *Accept*: `adb devices`
shows redwood; SDK level, Vulkan and GLES versions recorded; every clip mapped; purposes with no
matching clip listed in a BLOCKERS entry (non-blocking: continue with the next task).

**P0-03 Fetch mpv-android and bump native versions** — `native/fetch.sh` clones mpv-android at a
pinned commit. Look up the latest stable releases of mpv, FFmpeg, dav1d, libass, libplacebo, freetype,
harfbuzz, fribidi and patch the buildscripts' version pins to them. *Files*: native/fetch.sh,
native/patches/*, .gitignore, PROGRESS.md (Versions). *Accept*: fresh run reproduces the same tree;
every native version recorded with its source; no alpha/beta/RC/nightly.

**P0-04 Download SDK, NDK, deps** — Run the buildscripts' download step; confirm what it fetches.
*Files*: native/build.sh (download stage), ARCHITECTURE.md. *Accept*: SDK, NDK and all dependency
sources present at the recorded versions; latest stable NDK and CMake recorded; the "candidate" note in Native build replaced by facts.

**P0-05 FFmpeg trim** — Patch the FFmpeg configure flags per SPEC (disable programs, doc, encoders,
muxers, devices, avdevice; libdav1d on; decoders/demuxers stay broad). Confirm mpv builds without
avdevice. *Files*: native/patches/*. *Accept*: patch applies cleanly on a fresh fetch; FFmpeg
configure output shows no encoders/muxers and libdav1d enabled.

**P0-06 Build native for arm64** — `native/build.sh` builds everything arm64-only at `-O2` and copies
the `.so` files to `player/src/main/jniLibs/arm64-v8a/`. *Files*: native/build.sh, AGENT_RULES.md
(commands). *Accept*: `libmpv.so` is aarch64 (`readelf -h`); no libavdevice; a second run is a fast
no-op; build time noted; any fallback to a previous stable version noted in PROGRESS.md.

**P0-07 Gradle skeleton** — Look up and record latest stable AGP, Gradle, Kotlin, Compose BOM and
AndroidX versions, then create `:app` and `:player` with a version catalog, Compose,
`abiFilters arm64-v8a`, compileSdk/targetSdk = latest stable API, minSdk = device SDK, empty
MainActivity. *Files*: settings/build gradle files, gradle/libs.versions.toml, app/, player/,
PROGRESS.md (Versions). *Accept*: `./gradlew :app:assembleDebug` succeeds on the JBR; app installs and
shows a blank Compose screen; versions recorded.

**P0-08 JNI bridge minimal** — `mpv_jni.c` + `MpvNative`: create, setOptionString, initialize,
command, destroy; JavaVM handed to FFmpeg; mpv log to logcat tag `mpv`. *Files*: player/src/main/cpp/*,
player/.../MpvNative.kt. *Accept*: on launch logcat shows the mpv version string; no crash on exit.

**P0-09 Verify mpv options and codecs** — Dump `vo`, `hwdec`, `gpu-context`, `gpu-api`, `ao` help and
the decoder/demuxer lists from the built libmpv. *Files*: ARCHITECTURE.md (Confirmed mpv options,
Playback modes). *Accept*: confirmed option table written; every codec/demuxer in SPEC's minimum list
is present; any gap becomes a BLOCKERS entry.

**P0-10 Play one file** — MainActivity with a SurfaceView plays `h264_1080p.mp4` (storage permission
requested). *Files*: app/.../MainActivity.kt, manifest. *Accept*: video and audio play to the end;
`ao` is audiotrack in the log.

## P1 Player core

_Reviewer notes on P1-01/P1-02 (commits 1f6cf15, 749ce1a). Fix in the task named:_
- _P1-04: `time-pos` is observed as DOUBLE and logged on every change, i.e. a JNI call, a boxed Double
  and a logcat line per video frame. Remove the per-update logs (pause/duration/position/eof). Forward
  `time-pos` from C only when it moved by 100 ms or more, and `observeProperty`/unobserve it with
  controls visibility (ARCHITECTURE.md: no position updates while controls are hidden). Add
  `unobserveProperty` to the bridge._
- _P1-06: the option table is hard-coded in `PlayerEngine.initHandle()`; move it to `PlaybackMode`._
- _P1-07: `fallback` is set whenever `hwdec-current` is `no`, which is also true before any file is
  loaded and for AV1 (software by design). Report fallback only when a file is playing, its video codec
  is in `hwdec-codecs`, and `hwdec-current` is `no`._

_**Reviewer finding 2026-10-12, fix before anything else: the player shows a BLACK SCREEN with audio**
(the user saw it; `tools/check_video.py` confirms 0% non-black). Log: `[vo/gpu] Failed initializing any
suitable GPU context!` then `Error opening/initializing the selected video_out (--vo) device.` at file
load, before `Surface attached`. Cause: the handle starts with `vo=gpu` and the file is loaded before
the surface exists, so vo init fails and mpv drops the video track; the later attach never brings it
back. Required behaviour: initialise with `vo=null` and `force-window=no`; the vo for the mode is set
only in attach (after `wid`), and attach must also restore video if it was dropped (`vid=auto` unless
the user chose none). Loading before or after the surface exists must both end with a picture. Every
earlier "verified, no black screen" note was log-based and is void: re-verify P1-03 with
`tools/check_video.py` (see AGENT_RULES.md, "Checking video on the device") after start, after 5
rotations and after home/return._

_Reviewer notes on P1-03 (commit 97054a8). Fix first in P1-04, before the player screen work:_
- _`PlayerEngine.detachSurface()` only posts to the command thread, so `surfaceDestroyed` returns
  while mpv may still be drawing; Android destroys the Surface as soon as that callback returns.
  Make detach block the caller until the native detach has finished (run it on the command thread and
  wait, with a timeout of about 2 s and a log line if it expires). This is the one permitted blocking
  call on the main thread. Order attach/detach/setSurfaceSize strictly: a detach must never be
  overtaken by a queued attach of the old surface._
- _`usleep(20000)` in `attachSurface`/`detachSurface` is a guess. Replace it with a real wait: after
  setting `vo=null`, poll `current-vo` (or `vo-configured`) until it is empty, bounded to 500 ms._
- _Re-verify on the device: 20 rotations and 20 home/return cycles and screen off/on during playback,
  logcat free of EGL/`BufferQueue has been abandoned` errors._

**P1-01 Event thread and state** — Native event loop, `observeProperty`, `PlayerState`, separate
position flow. *Files*: mpv_jni.c, PlayerEngine.kt, PlayerState.kt. *Accept*: logcat shows pause,
duration and position changes arriving as flow updates.

**P1-02 PlayerEngine API** — `load`, `play`, `pause`, `seekTo`, `release` on a single command thread.
*Files*: PlayerEngine.kt. *Accept*: no mpv call on main (StrictMode or thread assert); load/seek/release
100 times in a loop without crash or leak.

**P1-03 PlayerActivity and surface lifecycle** — Move playback out of MainActivity into
`ui.player.PlayerActivity` (ARCHITECTURE.md, Activities); MainActivity keeps only a trivial launcher
until the browser branch is merged. Implement the attach/detach sequence from ARCHITECTURE.md
(Surface handling). Survive rotate, home and return, screen off and on. *Files*: PlayerActivity.kt,
PlayerEngine.kt, mpv_jni.c, ui/player/VideoSurface.kt, manifest. *Accept*: 10 rotations and 10
home/return cycles, playback continues, no crash, no black screen, no native crash in logcat.

**P1-04 Player screen** — Compose controls over the surface: play/pause, time labels, seek bar, title,
back; auto-hide after 3 s; immersive fullscreen; keep screen on. *Files*: ui/player/*.
*Accept*: controls work and hide; system bars hidden during playback.

**P1-05 Scrubbing** — Fast keyframe seeks while dragging, exact seek on release; position label
follows the thumb. *Files*: ui/player/SeekBar.kt, PlayerEngine.kt. *Accept*: dragging across a 4K
file stays responsive; released position is exact within 1 s.

**P1-06 Efficient mode candidates** — Implement both efficient paths from ARCHITECTURE.md behind a
debug switch; play `h264_1080p.mp4` 5 minutes on each and record power and drops. *Files*:
PlaybackMode.kt, docs/BENCHMARK.md. *Accept*: `hwdec-current` is a mediacodec variant for the H.264,
HEVC and VP9 clips on each working path; numbers recorded. Path B is the default.

**P1-07 Software fallback and dav1d threads** — Detect and expose fallback; confirm AV1 uses dav1d on
all cores. *Files*: PlayerEngine.kt, PlayerState.kt. *Accept*: `av1_1080p.mkv` plays with
`hwdec-current=no`; `top -H` shows 8 busy decoder threads on `av1_4k.mkv`; forcing a hwdec failure
still plays.

**P1-08 Debug overlay** — Toggle (long-press title) showing vo, hwdec-current, mode, resolution, fps,
both drop counters. Stats flow observed only while open. *Files*: ui/player/DebugOverlay.kt.
*Accept*: values update live; closed overlay causes no stats updates in logcat.

**P1-09 Open-with intent** — Handle `ACTION_VIEW` for `video/*` (file and content URIs); orientation
follows video aspect. *Files*: manifest, PlayerActivity.kt, ui/player/*. *Accept*: the "Play a test
clip" command in AGENT_RULES.md works (targets PlayerActivity); opening from a file manager works.

**P1-10 ASS subtitles on both efficient paths** — Check styled ASS (embedded fonts) and software
fallback on both efficient paths; record results next to the P1-06 numbers. Path B stays the default
(ARCHITECTURE.md); do not block. *Files*: docs/BENCHMARK.md, ARCHITECTURE.md. *Accept*: per-path result
for the Bocchi clip (fonts, positions, karaoke) recorded; path B renders it correctly.

## P2 Browser and thumbnails

**P2-01 MediaStore repository** — `READ_MEDIA_VIDEO` permission flow; query videos; group by bucket.
*Files*: data/media/*. *Accept*: logcat lists folders and counts matching the device gallery.

**P2-02 Folder list screen** — Folders with name, video count. *Files*: ui/browser/FolderList.kt.
*Accept*: all video folders shown, including `vibeplayer-test`.

**P2-03 Video list screen** — Videos in a folder with title, duration, size, resolution; tap plays.
*Files*: ui/browser/VideoList.kt. *Accept*: tap opens the player; back returns to the list.

**P2-04 Navigation and refresh** — Nav graph (folders → videos → player); ContentObserver refresh;
sort by name/date/size (saved in DataStore). *Files*: ui/Nav.kt, data/prefs/*. *Accept*: a file
pushed with adb appears without restart; sort order survives restart.

**P2-05 Thumbnail generator** — Background extraction (retriever first, mpv `screenshot-raw`
fallback), max 2 parallel, cancelled when scrolled away. *Files*: data/thumbs/ThumbnailGenerator.kt.
*Accept*: thumbnails for all 7 clips including AV1; scrolling stays smooth while generating.

**P2-06 Thumbnail cache** — Disk cache keyed by path+size+mtime, memory LRU, shown in both lists.
*Files*: data/thumbs/ThumbnailCache.kt, ui/browser/*. *Accept*: second launch shows thumbnails
instantly with no extraction in logcat; replacing a file regenerates its thumbnail.

**P2-07 Launch player from browser** — onPlayVideo starts an explicit ACTION_VIEW intent (file Uri,
type video/*) for class name "com.vibeplayer.ui.player.PlayerActivity" via setClassName; catch exception
and show Toast if missing. *Files*: ui/browser/*. *Accept*: log shows the intent being fired with the right Uri.

**P2-08 Thumbnail robustness** — Negative cache with marker file (version constant); investigate
MediaMetadataRetriever failures on hevc10_4k.mp4 and AV1 clips; non-blocking sends in ContentObserver;
decode disk-cache bitmaps at display size. *Files*: data/thumbs/*, data/media/MediaRepository.kt.
*Accept*: second launch does no extraction attempts at all (logcat); list scroll stays smooth.

**P2-09 Settings screen scaffold** — ui/settings/SettingsScreen reachable from a gear icon in browser
top bar; DataStore-backed preferences for playback mode, renderer, gestures, skip seconds, background audio,
shader preset; exposed as AppSettings Flow. *Files*: ui/settings/*, data/prefs/*. *Accept*: values survive app restart.

**P2-10 Thumbnails for every video (software fallback)** — **User request (2026-10-12): low priority,
but required. Do after P5, before P7.** `MediaMetadataRetriever` returns nothing for AV1 10-bit, 4K AV1
and 10-bit HEVC on this phone, so those files show a placeholder. Replace the placeholder
`MpvFallbackFrameExtractor` with a real one: a second mpv handle (never the playback handle) with
`vo=null`, `ao=null`, `aid=no`, `sid=no`, `hwdec=no`, `pause=yes`, `start=10%` (not a black first
frame), `vd-lavc-threads=2`, `vd-lavc-skiploopfilter=all`, `vd-lavc-fast=yes`, `hr-seek=no`; load the
file, wait for the first frame, grab it with `screenshot-raw` scaled to the thumbnail size
(`vf=scale=320:-2` or equivalent), convert BGR0 to a `Bitmap`, destroy the handle. Add the JNI call
needed to return the `screenshot-raw` node (width, height, stride, byte array). One extraction at a
time on the thumbnail worker, cancellable, with a 10 s timeout that writes the negative-cache marker;
bump `NEGATIVE_CACHE_VERSION` so files that failed before are retried. Must not run while a video is
playing in the player (pause the queue), and must not make scrolling the list janky.
*Files*: mpv_jni.c, MpvNative.kt, a small ThumbnailEngine in :player, data/thumbs/*.
*Accept*: every clip in the test folder shows a real picture (screenshot of the list, no placeholders),
including `av1_4k.mp4`, `hevc10_4k.mp4`, Bocchi, Clevatess and Bad Girl; time per software thumbnail
logged under `VP-Perf`; `dumpsys gfxinfo` shows no jank spike while they generate; no leaked handles
after 50 generations.

**P2-11 Storage access scoping** — Restrict app storage access to video files only. Remove
`MANAGE_EXTERNAL_STORAGE` (all-files access) from the manifest and any permission checking/requesting
code; retain `READ_MEDIA_VIDEO` and `READ_EXTERNAL_STORAGE` (maxSdkVersion 32). Verify that the file
browser and playlist only list video files (no audio or image files). Auto-loading external subtitle
files (`.srt`/`.ass` next to a video, P4-04) relies on mpv's `sub-auto=fuzzy` which cannot read non-media
files in arbitrary shared directories on Android 13+ without all-files access; record this limitation
where applicable.
*Files*: app/src/main/AndroidManifest.xml, PROGRESS.md, TASKS.md.
*Accept*: builds without `MANAGE_EXTERNAL_STORAGE`, browser lists only videos, unit tests pass.

## P3 Gestures

**P3-01 Gesture layer** — One `pointerInput` layer with zones (left half, right half, horizontal),
direction lock after slop; single tap toggles controls. *Files*: ui/player/Gestures.kt.
*Accept*: logcat shows the correct gesture type for each motion; controls still clickable.

**P3-02 Brightness** — Left vertical swipe sets window brightness, with indicator. *Accept*: full
swipe covers min to max; resets on leaving the player. *Files*: ui/player/Gestures.kt, Indicators.kt.

**P3-03 Volume** — Right vertical swipe sets media volume, with indicator. *Files*: same.
*Accept*: tracks hardware volume keys both ways.

**P3-04 Swipe seek** — Horizontal swipe seeks with a "+00:15 / 12:34" overlay; applied on release
with keyframe preview during the drag. *Files*: same. *Accept*: works paused and playing.

**P3-05 Double tap skip** — Double tap left/right skips 10 s with ripple and accumulating "+20 s".
*Files*: same. *Accept*: repeated taps accumulate; a single tap is not delayed noticeably.

**P3-06 Pinch zoom** — Pinch sets `video-zoom`, two-finger drag pans; double pinch-out or button
resets. *Files*: Gestures.kt, PlayerEngine.kt. *Accept*: smooth zoom on 4K; reset restores fit.

**P3-07 Lock** — Lock button disables all gestures and controls except unlock. *Files*: ui/player/*.
*Accept*: locked screen ignores taps, swipes and back; unlock restores.

**P3-08 Haptics** — Haptic tick on skip, on swipe-seek steps and at seek bar ends. *Files*: same.
*Accept*: felt on device; none while locked.

**P3-09 Hold for fast playback** — **User request (2026-10-14).** Press and hold on the right half
of the video: playback runs at the "hold speed" while the finger stays down and returns to the file's
own speed on release (YouTube / MX Player style). Setting "Hold speed" in the gestures section
(1.5x, 2x default, 3x, 4x). While held: a small pill at the top centre ("2x" with a fast-forward
icon), one haptic tick on start, pitch correction stays on, audio keeps playing. Does not persist
as the file's speed, does not count as a speed change for P4-07, is ignored while locked, in the
mini player and while a sheet is open. Must not break the other gestures: a hold that turns into a
vertical drag stays a volume swipe (decide by slop before the long-press timeout), a horizontal drag
stays a swipe seek, single and double tap unchanged, and the hold does not show the controls.
*Files*: ui/player/Gestures.kt, Indicators.kt, PlayerEngine.kt (a temporary speed override that
does not touch the saved speed), data/prefs/AppSettings.kt, ui/settings/SettingsScreen.kt.
*Accept*: on the device, `adb shell input swipe x y x y 3000` (a 3 s hold at one point on the right
half) shows the pill, `speed` reads the hold speed during the hold and the file's speed after;
time-pos advances about twice as fast during a 2x hold; a vertical swipe on the right still changes
volume; setting the hold speed to 3x takes effect; nothing is persisted for the file.

## P4 Tracks and per-file memory

**P4-01 Room setup** — Database, `FileSettings` entity and DAO per ARCHITECTURE. *Files*: data/db/*.
*Accept*: insert/read round trip logged on device; schema exported.

_**Reviewer findings on P3 and P4-02..05 (2026-10-12), fix first, commit as `P4-02 fix: ...`:**_
- _**Stale position.** `time-pos` is unobserved while the controls are hidden, but
  `PlayerViewModel.saveCurrentPosition()`, swipe seek and double-tap skip all read
  `playerEngine.position.value`. With controls hidden (the normal case) resume saves the position from
  the last time the controls were visible, and swipe seek starts from it. Fix: add
  `suspend fun currentPositionMs(): Long` to `PlayerEngine` that reads `time-pos` on the command thread;
  use it for saving. Make swipe seek and double-tap skip use relative seeks (`seek <delta> relative`),
  with the overlay's base position taken from `currentPositionMs()` at gesture start. Verify: play with
  controls hidden for 40 s, press back, reopen: it must resume within 10 s of where it stopped; a
  swipe seek with hidden controls must land at old position + delta._
- _**Whole player recomposes on every tick.** `PlayerActivity` collects `playerEngine.position` at the
  root of `setContent` and passes `positionMs` down. Pass the `StateFlow` (or a lambda) instead and
  collect it only inside the seek bar and time labels._
- _`saveCurrentPosition(forceSync = true)` in `onCleared` uses `runBlocking` on the main thread: also
  save in `onStop` asynchronously so `onCleared` rarely has anything left to write; keep the blocking
  save bounded (timeout 500 ms)._

_Reviewer notes from the P1 review (2026-10-12):_
- _P4-02: restore the position with the `start=<seconds>` per-file option of `loadfile`, not with a
  `seek` sent right after `loadfile` (the file is not loaded yet, so that seek is lost).
  `PlayerEngine.reconfigure()` has the same bug: fix it there too._
- _P6-02: keep `Renderer.VULKAN`. Offer it when a one-time runtime probe succeeds (create the Vulkan
  context once at first use and cache the result keyed by `Build.FINGERPRINT` + the Vulkan driver
  version; **not** `FEATURE_VULKAN_HARDWARE_VERSION`, which still reports 1.1 on this phone although
  the driver is 1.3) and the loaded libmpv lists `vulkan` in its `gpu-api` choices. If Vulkan init
  fails at playback time, fall back to GLES automatically with a log line and a note in the debug
  overlay._
- _P7: `libdav1d: Error parsing OBU data` is logged once at the start of every AV1 file; find out
  whether it is harmless (extradata probe) or costs start time._

**P4-02 Resume position** — Save on pause, every 10 s and on exit; restore on open; restart from 0 if
within the last 5%. *Files*: PlayerViewModel.kt, data/db/*. *Accept*: force-stop mid-file, reopen,
resumes within 10 s.

**P4-03 Audio track picker** — Sheet listing audio tracks (language, codec, channels).
*Files*: ui/player/TrackSheet.kt, PlayerState.kt. *Accept*: switching on `multi_track.mkv` changes
audio; AC3/EAC3/DTS track plays.

**P4-04 Subtitle picker** — Subtitle tracks plus Off; auto-load external subs next to the file.
*Files*: same. *Accept*: ASS, SRT and PGS tracks render; Off hides them.

**P4-05 Speed** — Speed control 0.25x to 4x with pitch correction. *Files*: ui/player/SpeedSheet.kt.
*Accept*: audio stays in sync at 2x.

**P4-06 Aspect ratio** — Cycle fit, crop, stretch, 16:9, 4:3 with a toast-style label.
*Files*: ui/player/*, PlayerEngine.kt. *Accept*: each mode visibly correct on a 4:3 and a 21:9 source.

**P4-07 Persist per-file choices** — **User request (2026-10-12): when a file is resumed, the
selected audio track and subtitle track (or subtitles off) must come back.** Save audio track,
subtitle track/off, speed and aspect whenever they change and on exit; restore them when the file is
opened, before or together with the first frame (pass `aid`/`sid`/`speed` as per-file `loadfile`
options next to `start`, not as late property sets). Match tracks by id and fall back to language +
title if the id no longer exists. *Files*: PlayerViewModel.kt, PlayerEngine.kt, data/db/*.
*Accept*: on `multi_track.mkv` and the Case Closed clip pick a non-default audio and subtitle track,
exit, force-stop, reopen: both are selected again (check `aid`/`sid` and the picker sheet screenshot);
another file is unaffected.

**P4-10 Default languages and per-folder track memory** — **User request (2026-10-12).** Do right
after P4-07; it extends the same code.
- Settings (DataStore, shown in the settings screen under "Languages"): preferred audio languages
  (ordered list, default `en`), preferred subtitle languages (default `en`), and subtitle behaviour
  when the audio is in a preferred language: `Forced / Signs & Songs only` (default), `Full subtitles`,
  `Off`. When the audio is not in a preferred language: full subtitles in the preferred subtitle
  language.
- Track choice when a file opens, first match wins:
  1. the per-file choice saved by P4-07;
  2. the folder choice (below);
  3. the language defaults from settings;
  4. mpv's default.
- A track counts as "Forced / Signs & Songs" if mpv marks it forced (`track-list/N/forced`) or its
  title matches, case-insensitive, `sign`, `song`, `forced`, `s&s`. A full-dialogue track is one that
  is not. Language codes: treat 2- and 3-letter forms as equal (`en`/`eng`, `ja`/`jpn`/`jp`).
- Folder memory: when the user picks an audio or subtitle track by hand, store for the file's parent
  folder the choice as (language, title, forced/signs kind, or "off"), not as a track id. Other files
  in that folder that have no per-file choice select the best matching track: same language and title,
  else same language and kind, else fall through to rule 3. New Room table `FolderSettings(folderPath,
  audioLang, audioTitle, subLang, subTitle, subKind, subEnabled, updatedAt)` with a proper migration
  (schema v2 exported; existing history must survive).
- Selection logic lives in a pure Kotlin class (`player` or `data` module, no Android types) with unit
  tests for: English audio + signs track present, Japanese-only audio, folder choice overriding the
  defaults, file choice overriding the folder, track missing in the next file.
- Apply the choice as the file loads (per-file `loadfile` options when the ids are known from the
  folder/file record, otherwise right at `file-loaded` before the first subtitle shows).
*Files*: data/db/*, data/prefs/AppSettings.kt, ui/settings/*, PlayerViewModel.kt, a new TrackSelector.kt
and its test. *Accept*: unit tests pass; on the Case Closed clip (eng + jpn audio, 2 ASS tracks) with
defaults, English audio is selected and the signs/songs subtitle if one exists, else none; switching to
Japanese audio + full subtitles on one file in a folder makes another file in that folder open the
same way (use copies of a test clip in a second folder if needed); the picker sheet screenshot shows
the selected rows.

**P4-11 Review fixes (correctness)** — Fix the correctness defects from `docs/REVIEW_UI_2026-10-12.md`.
Confirm each one in the code first (the review was by reading only), then fix:
item 1 (volume indicator pops up on every recomposition), item 3 (gesture layer uses stale zoom /
brightness / volume / duration state: zoom snaps back after reset, swipe seek clamps to the old file's
duration), item 4 (`RejectedExecutionException` after `release()`: crash when leaving the player with
the debug overlay open), item 5 (blocking save on the main thread in `onStop`/`onCleared`), item 6
(single tap delayed 200 ms; double-tap window mismatch), item 13 (resume and folder keys for
`content://` files are built from a file-descriptor number), item 14 (`time-pos` observed twice /
left on in the mini player), item 16 (two quick opens can load the wrong file or save under the wrong
key), and the three "unverified" notes in item 20 if they turn out to be real.
*Files*: ui/player/*, PlayerViewModel.kt, PlayerEngine.kt. *Accept*: for each item a line in the
PROGRESS.md note saying "fixed", "not a bug because ..." or "could not reproduce"; on the device:
zoom, reset, zoom again keeps working; swipe seek on a second file opened from the mini player uses
the new duration; leaving the player with the debug overlay open does not crash (20 times); a
`content://` video resumes after reopen; mini player and all gestures still pass their checks.

**P4-12 Auto-play next file in the folder** — **User request (2026-10-12, important). Core done by the
reviewer.** When a file ends, the next video file of the same folder (natural name order) starts from
the beginning; setting "Auto-play Next" (default on). `FolderPlaylist.kt`, `PlayerViewModel.onPlaybackEnded`
and `skipToNeighbour(forward)` exist. Remaining for an agent: previous/next buttons in the controls
calling `skipToNeighbour`; the title in the controls and the mini player aspect must follow the new
file; a short "Next: <name>" hint in the last seconds is optional. *Accept*: on the device a short
clip ends and the next file plays with picture (also inside the mini player and with the screen
locked controls); the last file in a folder just stops; with the setting off nothing advances;
next/previous buttons work; folder track memory (P4-10) applies to the auto-played file.

**P4-13 Subtitle and audio sync, subtitle style** — MX Player parity (reviewer addition 2026-10-14,
the user asked for more work). In the player: subtitle delay and audio delay in 50 ms steps (a small
sheet with -/+ buttons and reset, mpv `sub-delay` / `audio-delay`), saved per file with the other
per-file choices (Room migration), subtitle size (`sub-scale`) and vertical position (`sub-pos`)
as global settings, and "override ASS style" off by default (styled anime subtitles must keep their
look; when on, `sub-ass-override=force` with the chosen size). *Files*: ui/player/*, PlayerEngine.kt,
data/db/*, data/prefs/*, ui/settings/*. *Accept*: a delay change visibly moves subtitles on the
device, is restored after reopen, another file is unaffected; size/position change SRT subtitles;
Bocchi's styled ASS is unchanged with override off.

**P4-08 History in browser** — Progress bar on thumbnails, "Continue watching" row.
*Files*: ui/browser/*. *Accept*: partially watched clips show progress; row order = most recent first.

**P4-09 Name, icon and version** — App label "Vibe Player" (launcher, recents, settings title, browser
top bar). Launcher icon from `branding/icon.svg` (512 px artwork with its own rounded background):
build an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` with foreground and background layers, the
play mark inside the 66% safe zone, plus a monochrome layer for themed icons) and legacy PNG mipmaps;
use it for `android:icon` and `android:roundIcon`. `rootProject.name = "VibePlayer"`. Version scheme:
`versionName` = `0.<highest finished phase>.<patch>` (now `0.4.0`; `1.0.0` when P9 is done),
`versionCode` increases by 1 with every versionName change; show "Vibe Player 0.4.0" in the settings
screen from `BuildConfig`. Keep `applicationId` and package `com.vibeplayer`. *Files*: app/build.gradle.kts,
settings.gradle.kts, res/mipmap-*, res/values/strings.xml, manifest, ui/settings/*. *Accept*: screenshot
of the launcher shows the new icon and the name "Vibe Player"; settings shows the version.

## P5 PiP and background

**P5-01 PlaybackService** — Move engine ownership into a foreground service; ViewModel binds to it.
*Files*: playback/PlaybackService.kt, PlayerViewModel.kt, manifest. *Accept*: all P1 to P4 behaviour
unchanged; rotation does not restart playback.

**P5-02 MediaSession and notification** — Session with metadata, play/pause/seek/skip actions.
*Files*: playback/*. *Accept*: notification and lock screen controls work; headset button works.

**P5-03 Background audio** — Setting to continue audio when leaving the player; video output
detached while in background; audio focus and unplug (becoming noisy) handled. *Files*: playback/*.
*Accept*: audio continues with screen off; pauses on call or unplug; video returns on reopen.

**P5-04 Mini player (picture-in-picture)** — **User priority (2026-10-12): do this right after P4-07,
before P5-01; it must not depend on the service.** The user watches video in a floating mini player
while playing games and using other apps. Use Android picture-in-picture on `PlayerActivity`
(`supportsPictureInPicture`, `resizeableActivity`): it floats over games and other apps, the user can
drag it anywhere and pinch or double-tap to resize it, and the system keeps the aspect ratio.
- Enter by a mini-player button in the controls and automatically on home / app switch while playing
  (`setAutoEnterEnabled(true)`); not when paused.
- Aspect ratio = the video's display aspect (`dwidth`/`dheight`), clamped to the range Android allows
  (1:2.39 to 2.39:1); update the params when the video size or file changes. Set `sourceRectHint` to
  the video rectangle and `setSeamlessResizeEnabled(true)` so entering and resizing are smooth.
- Playback and audio continue without a restart or black frame when entering, resizing and leaving;
  `onPause` must not pause playback while in PiP; `onStop` in PiP (window dismissed) stops playback
  and saves the position.
- In PiP: all controls, gestures, indicators and sheets are hidden, only video and subtitles show.
  Remote actions: rewind 10 s, play/pause, forward 10 s (icons update with the pause state).
- Tapping the expand button returns to the full player in the same state.
*Files*: ui/player/PlayerActivity.kt, ui/player/PipHelper.kt, manifest. *Accept*: screenshots show the
mini player over the home screen and over another app with the video's aspect (test a 16:9, a 4:3 and
a vertical clip); `tools/check_video.py` is not usable for a small window, so crop the screenshot to
the PiP bounds (`dumpsys activity activities | grep -i pip` / `dumpsys window`) and show it changes
between two captures; actions work via `adb shell` broadcast or `input tap` on them; the full player
returns with playback continuing.

_Reviewer notes on P5-04 (commit 55e6192), fix in P5-05:_
- _`pipReceiver` and `volumeReceiver` are registered `RECEIVER_EXPORTED`: any app can send the
  play/pause/skip broadcasts. Register `RECEIVER_NOT_EXPORTED` and give the PiP `PendingIntent`s
  `setPackage(packageName)`._
- _`onStop` outside PiP (screen off or another app on top while the full player is playing) leaves
  playback running. Until P5-03 adds the background-audio setting, pause in `onStop` when not in PiP;
  after P5-03, pause only if background audio is off._

_Reviewer decisions after the CLAUDE-H review of par/p5 (2026-10-14), to do before the P5 device pass ends:_
- _Paused from the notification with no activity open: keep the media notification (it leaves the
  foreground, stays swipe-dismissable, play resumes from it) and stop the service only after 10
  minutes paused or when the user dismisses it; this is what MX Player and YouTube do._
- _While the surface is detached for background audio, also set `vid=no` (restore the previous `vid`
  on re-attach) so video is not decoded with the screen off; measure CPU before/after._
- _AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: lower mpv `volume` to about 30% and restore it on gain
  instead of pausing (add a small PlayerEngine.setDuck(Boolean))._
- _Ask for POST_NOTIFICATIONS once (remember the denial); content-URI titles use the display name._

**P5-05 Lifecycle edge cases** — PiP ↔ fullscreen, PiP close, swipe from recents, opening a new file
while playing. *Files*: playback/*. *Accept*: none of these crash or leave a stale notification.

## P6 Quality mode and renderer benchmark

**P6-00 Vulkan-enabled native build** — Enable Vulkan in libplacebo and mpv (and what they need, e.g.
shaderc/glslang, Vulkan headers from the NDK) via a new patch; rebuild; probe on the device with
`gpu-api=vulkan`, `gpu-context=androidvk`, `vo=gpu-next`. *Files*: native/patches/*, native/build.sh,
ARCHITECTURE.md (Vulkan status, Confirmed mpv options). *Accept*: either a clip plays through Vulkan
(overlay/log shows the Vulkan context), or the exact failure (e.g. device Vulkan 1.1 below the required
version) is recorded in ARCHITECTURE.md and a non-blocking BLOCKERS entry, and P6-02/P6-04/P6-05 are
re-scoped to `gpu-next` vs `gpu` on GLES. GLES playback must still work after the rebuild.

**P6-00b Vulkan re-probe on the 1.3 driver** — The phone now reports Vulkan 1.3.295 (ARCHITECTURE.md,
"Vulkan status update"). Build `VULKAN=1 native/build.sh` (in a worktree with its own
`native/mpv-android`: the build wipes the shared prefix), probe `vo=gpu-next`, `gpu-api=vulkan`,
`gpu-context=androidvk` with `hwdec=mediacodec-copy` and with software AV1, and also `vo=gpu` with
Vulkan. *Accept*: a clip plays through Vulkan with a visible picture (`tools/check_video.py`) and the
log shows the Vulkan context and device; or the exact failure is recorded. If it works, the Vulkan
build becomes the default libraries in `player/src/main/jniLibs` (default variant = with Vulkan), the
P6 tasks compare Vulkan vs GLES as SPEC.md originally says, and the renderer setting offers both.

**P6-07 Best configuration per format, chosen before opening** — **User decision (2026-10-14).** The app
targets one phone, one OS, one GPU, so it does not guess at runtime: before `loadfile` it identifies
the video format and picks the configuration measured as best for that format on this phone, from a
table in the player module. Identify the format cheaply before the engine loads the file (Android
`MediaExtractor` track MIME + profile/bit depth, or a header read; must cost well under 50 ms and run
off the main thread, in parallel with engine init: see P7-02). Initial table from the measurements we
have (docs/BENCHMARK.md, BLOCKERS.md B-08), to be refined by the P6 benchmark:

| Format | Decode | Renderer | Why (measured) |
|---|---|---|---|
| H.264 8-bit | MediaCodec copy-back (`mediacodec-copy`) | Vulkan if available, else OpenGL ES | zero-copy stalled 12/40 and 5/60 opens; copy-back 0/80 on GLES, 5/80 on Vulkan |
| H.264 10-bit (Hi10P) | software | Vulkan, else OpenGL ES | no hardware decoder on the 778G |
| HEVC 8/10-bit | MediaCodec zero-copy (efficient) | OpenGL ES | 0/20 stalls zero-copy; lowest power path |
| VP9 | MediaCodec zero-copy (efficient) | OpenGL ES | 0/20 stalls |
| AV1 (any) | dav1d software, all cores | Vulkan `vo=gpu`, else OpenGL ES | fewest drops in the 4K test (359 vs 517/565) |
| other | software | OpenGL ES | safe default |

Quality mode overrides the renderer column with gpu-next on Vulkan (else GLES). "Vulkan if available"
= libmpv has `vulkan` in `gpu-api` and the device's Vulkan driver started once on this driver build
(cached per Build.FINGERPRINT + vulkan.adreno.so mtime; never the 1.1 feature flag).
The fallback chain stays only as a safety net for when the driver changes or a file misbehaves:
Vulkan -> OpenGL ES -> software, at the same position, one short message, VULKAN-FAIL log lines, and a
failure for a format on this driver build is remembered so the table entry is skipped next time.
Applies everywhere: every open path (player, mini player, auto-play next, previous/next,
notification/MediaSession, re-attach after background audio, reconfigure on mode switch, the benchmark
runner default) and the docs (ARCHITECTURE.md, README.md, docs/HARDWARE.md, docs/ROADMAP.md).
Settings: Renderer = Auto (the table; default) / Vulkan / OpenGL ES; old default users move to Auto.
The debug overlay shows the chosen row and the reason. *Accept*: on the phone each test clip opens
with its table configuration (overlay + log), first frame time is not worse than before (VP-Perf),
forced Vulkan failure lands on GLES, forced GLES failure on software; unit tests for the table lookup,
format detection parsing and the chain.

_User goal for choosing configurations (2026-10-14): smoothest playback first, then lowest battery,
then lowest CPU. Per format, whichever of OpenGL ES or Vulkan measures better on those criteria wins;
no renderer is preferred on principle. The user's Vulkan driver is maturing (they report Vulkan felt
smoother and used less battery today), so the decision must follow measurements per driver build:
the P6-04 matrix covers every table row on both renderers (incl. HEVC/VP9/H.264 hardware decode with
Vulkan copy-back vs OpenGL ES zero-copy) and records dropped and mistimed frames, frame-time
variance, battery current (unplugged), app CPU and temperature. P6-06 turns the results into the
P6-07 table; keep the table as data (an asset generated from the benchmark CSV, keyed by the Vulkan
driver hash) so a new driver build can be re-benchmarked and the table regenerated without code
changes. Ties go to the lower-power option._

**P6-08 Quick re-check on today's driver (fill-in task)** — **User request (2026-10-14): run in free
phone time between other tasks, not as a priority.** The formats where OpenGL ES measured better or was
assumed better: H.264 8-bit (ichiban.mp4), HEVC 10-bit 4K (hevc10_4k.mp4), VP9 (vp9_1080p.webm).
For each, 60 s with the benchmark runner (P6-03, per-second samples, auto-play off) on: OpenGL ES
zero-copy (`vo=gpu`, `hwdec=mediacodec`), OpenGL ES copy-back, Vulkan `vo=gpu` copy-back, Vulkan
`vo=gpu-next` copy-back. Record steady dropped/mistimed frames, frame-time variance, app CPU, battery
current (mark "charging" if plugged in: then current is not comparable) and temperature, with the
Vulkan driver hash. Plus 20 cold opens of ichiban.mp4 per configuration for the start stall. Write a
short table in docs/BENCHMARK.md "Quick re-check (driver <hash>)" and one line of conclusion per format;
append the Vulkan rows to vulkan.txt. About 25 minutes of phone time; can be split.
_Open after the P6-08 fix (2026-10-15, MediaCodec operating-rate hint, ARCHITECTURE.md): the battery cost
of the higher decoder clock must be measured on battery power (unplugged), before the hint counts as
settled: HEVC 10-bit 4K, H.264 1080p (ichiban.mp4, hint 60) and VP9 1080p, 60 s each, with the hint
(default) and without it (`--es mpv_opts 'vd-lavc-o=operating_rate=0'`), interleaved; compare steady
drops, battery current and app CPU. If a 30 fps clip shows 0 drops either way and a clear current increase
with the hint, set that row's policy to `off` (table_notes.json "operatingRate.formats") or raise the
threshold, and regenerate the table._

**P6-01 Quality mode** — Quality option table; mode setting and in-player toggle using
`reconfigure()`. *Files*: PlaybackMode.kt, PlayerEngine.kt, ui/settings/*. *Accept*: overlay shows
`gpu-next` and `mediacodec-copy`; switching mid-file keeps position and tracks.

**P6-02 Renderer switch** — Vulkan / OpenGL ES setting for quality mode. *Files*: same.
*Accept*: both renderers play all clips; overlay shows the active API.

**P6-03 Benchmark harness** — Debug-only runner: plays a clip for N seconds and writes a CSV row
(drops, decoder drops, app CPU %, GPU busy if readable, average battery current, temperature).
*Files*: app/src/debug/.../Benchmark.kt. *Accept*: one adb command produces a CSV on the device.

_Reviewer notes for P6-03/P6-04 (from a review of the P6-00b numbers, 2026-10-13). The earlier 20 s
numbers are not reliable enough to decide anything; the harness and the matrix must do this:_
- _**Sample stats while the clip is playing.** The P6-00b "last stats line" for the GLES AV1 run was
  taken after the file had ended (`drops=0 fps=0.0`, empty hwdec). The harness records the counters
  at a fixed time before the end of the run and also once per second, with timestamps, so a row can be
  re-derived from the log. Never read counters after EOF or after auto-play has switched file (turn
  auto-play next off for benchmark runs)._
- _**Time of each drop.** Log `frame-drop-count` every second. If all drops of a run fall in the
  first one or two seconds they are shader compilation at start: report them separately as "startup
  drops". Set `gpu-shader-cache-dir` (and `icc-cache-dir` is not needed) to a directory in the app
  cache so libplacebo/mpv reuse compiled shaders on later plays, and measure cold and warm start._
- _**AV1 4K is not yet attributed.** av1_4k.mp4 is 4K 10-bit 60 fps decoded on the CPU; mpv counts
  late frames as output drops even when slow decoding caused them, so output drops do not prove a
  GPU limit. Separate the two: (a) an AV1 1080p60 clip and an AV1 4K 30 fps clip (make them with
  the reviewer made them from av1_4k.mp4 with SVT-AV1 at bitrates proportional to the original's
  40 Mbit/s and pushed them to `/sdcard/Movies/vibeplayer-bench/`: `av1_1080p60_10bit.mp4` (10M),
  `av1_4k30_10bit.mp4` (20M), `av1_4k60_8bit.mp4` (40M); the original stays in the test folder): if drops mostly go,
  CPU decode is the limit; (b) `gpu-next` with `profile=fast` versus plain `gpu-next` versus `gpu`:
  if fast brings gpu-next down to gpu's level it is per-frame GPU work, not the driver; (c) record
  per-core CPU load and `decoder-frame-drop-count`, `frame-drop-count`, `mistimed-frame-count`,
  `vo-delayed-frame-count`, `estimated-vf-fps` and the display fps._
- _**Identify the driver build.** The Vulkan driver is the user's own port and changes between
  sessions while still reporting API 1.3.295 / driverVersion 2151018497: record
  `sha256sum /vendor/lib64/hw/vulkan.adreno.so` (first 16 hex digits) and its date with every row.
  Build at 2026-10-13 21:09: `c0b92d25178c8b21`._

**P6-04 Benchmark: short matrix** — {Vulkan, GLES} × {H.264 1080p, HEVC 4K, AV1 1080p} × 5 min,
unplugged, fixed brightness. Commit after each row so a resumed agent continues mid-matrix.
*Files*: docs/BENCHMARK.md. *Accept*: 6 result rows. Needs the user to set up wireless adb, otherwise
BLOCKERS.

**P6-05 Benchmark: stability** — 30 min run of `hevc10_4k.mkv` on each renderer; note crashes, drops,
temperature. *Files*: docs/BENCHMARK.md. *Accept*: 2 rows plus a written recommendation.

**P6-06 Default renderer** — Set the default per the benchmark; record the decision.
*Files*: PlaybackMode.kt, ARCHITECTURE.md. *Accept*: fresh install uses the chosen renderer.

## P7 Performance pass (release builds only)

**P7-01 Release build** — mpv log level `warn` in release (P0 sets `all=v`, which costs CPU); R8 full mode, resource shrinking, local signing config, keep rules for JNI.
*Files*: app/build.gradle.kts, proguard files, AGENT_RULES.md (commands). *Accept*: release APK
installs and passes a smoke test of P1 to P5 features.

**P7-02 Start time** — `VP-Perf` timestamps from tap/intent to `playback-restart`; optimise (preinit
engine, lazy UI, no blocking IO). *Files*: PlayerEngine.kt, ui/player/*. *Accept*: median of 10 cold
opens of `h264_1080p.mp4` under 1000 ms.

**P7-03 Recomposition** — _Start from `docs/REVIEW_UI_2026-10-12.md` items 2, 7, 12 and 19._ Verify only the seek bar and time label recompose on position ticks; fix
unstable parameters. *Files*: ui/player/*. *Accept*: recomposition counts (Layout Inspector or
logged) for other controls stay flat during 60 s playback.

**P7-04 Baseline Profile** — `:baselineprofile` module covering launch, browse, open video.
*Files*: baselineprofile/*, app/build.gradle.kts. *Accept*: profile generated on device and packaged;
cold start improves or stays equal (numbers in docs/BENCHMARK.md).

**P7-05 Browser smoothness** — _Start from `docs/REVIEW_UI_2026-10-12.md` items 9, 10, 11, 15, 17 and 19._ Fix list jank (image decoding off main, stable keys).
*Files*: ui/browser/*. *Accept*: `dumpsys gfxinfo` janky frames under 5% for a fast scroll of a
100+ item folder.

**P7-06 Idle cost** — _Start from `docs/REVIEW_UI_2026-10-12.md` items 8, 14 and 18._ No position/stat updates or recomposition while controls are hidden.
*Files*: PlayerEngine.kt, ui/player/*. *Accept*: efficient-mode power for a 10 min clip is not higher
than the P1-06 figure.

**P7-07 Memory budget** — Reviewer measurement 2026-10-13: playing a 1080p H.264 file the app uses
428 MB PSS, of which 253 MB is native heap (mpv) and 78 MB graphics. For local files mpv's default
read-ahead cache (150 MiB forward, 50 MiB back) buys nothing. Set for local files
`demuxer-max-bytes` about 32 to 48 MiB and `demuxer-max-back-bytes` about 8 to 16 MiB (keep seeking
back by 10 s instant: measure), check `cache`/`demuxer-readahead-secs`, the libass font cache, the
thumbnail memory LRU (1/8 of the heap today) and that the thumbnail mpv instance is really gone after
use. Release memory when the player is closed (the browser alone should be small) and trim on
`onTrimMemory`. *Files*: PlaybackMode.kt, PlayerEngine.kt, data/thumbs/*. *Accept*: `dumpsys meminfo
com.vibeplayer` TOTAL PSS recorded in docs/BENCHMARK.md before and after for: browser idle, 1080p
H.264 playing 2 min, 4K AV1 playing 2 min, mini player; 1080p playback at or under 220 MB without new
dropped frames or slower double-tap seeks; browser idle after closing the player at or under 150 MB.

## P8 Optional shaders (quality mode only)

**P8-01 Shader plumbing** — Ship shaders as assets, copy to files dir, apply via `glsl-shaders`;
ignored in efficient mode. *Files*: PlaybackMode.kt, assets. *Accept*: a test shader visibly applies
and is removable at runtime.

**P8-02 Anime4K light presets** — Fast presets (A, B, C) selectable in settings. *Files*: assets,
ui/settings/*. *Accept*: each preset loads without shader errors in the mpv log on both renderers.

**P8-03 Snapdragon GSR** — Add GSR upscaling shader with a toggle. *Files*: same.
*Accept*: visible sharpening on 720p content; no errors.

**P8-04 Shader benchmark** — Run the P6 harness 5 min per preset (3 Anime4K + GSR) on the default
renderer with a 1080p clip; commit after each row. *Files*: docs/BENCHMARK.md, ui/settings/*.
*Accept*: 4 rows recorded; presets that drop frames are labelled "heavy" in settings.

## P9 Optional native tuning

**P9-01 Tuned build** — `TUNED=1 native/build.sh` uses `-O2 -mcpu=cortex-a78 -flto=thin`.
*Files*: native/build.sh, native/patches/*. *Accept*: builds; all 7 clips play; no `-O3`/`-Ofast`
anywhere in the build flags.

**P9-02 Tuned vs baseline** — Benchmark AV1 4K software decode and a 30 min stability run.
*Files*: docs/BENCHMARK.md. *Accept*: results recorded; tuned build kept only if stable and not slower.

## Phase 10: platform features (from the Android 17 research, 2026-10-15)

**P10-01 Surface.setFrameRate** — in the video surface code (app/src/main/java/com/vibeplayer/ui/player/VideoSurface.kt or wherever the SurfaceView's Surface is created), when a file's container fps is known (engine state/stats; use the exact value e.g. 23.976), call surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS) once per file (not per frame), and setFrameRate(0f, ...) on pause, stop, surface destroy and when leaving the player. Guard against invalid fps (0, NaN, >240). Add a setting toggle "Match display refresh rate" (default on) in the existing settings store. Pure helper for the fps decision with unit tests. *Files*: ui/player/VideoSurface.kt, data/prefs/AppSettings.kt, ui/settings/SettingsScreen.kt, test files. *Accept*: log line shows the call, `adb shell dumpsys SurfaceFlinger | grep -i 'frame rate'` shows the vote (device check open).

**P10-02 Thermal adaptation** — register PowerManager.addThermalStatusListener while playing (unregister on stop). Expose thermal status in the stats overlay. At THERMAL_STATUS_MODERATE or higher, step quality down one notch via the existing quality-mode/renderer/shader machinery (e.g. drop shaders first, then gpu-next→gpu), show a short toast/OSD "Reduced quality (device hot)", never step back up within the same file. Decision logic as a pure function with unit tests (including no oscillation). Never touch the safety chain Vulkan→GLES→software ordering. *Files*: playback/PlaybackService.kt, ui/player/DebugOverlay.kt, PlayerEngine.kt, test files. *Accept*: device check open.

**P10-03 Display cutout** — set window.attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS in PlayerActivity so video in zoom/fill aspect modes can use the punch-hole area; make sure the controls still respect the cutout insets (WindowInsets displayCutout padding on the controls layer only, not on the video). *Files*: ui/player/PlayerActivity.kt, ui/player/PlayerControls.kt. *Accept*: device check open (user).

**P10-04 Back handling audit for targetSdk 37** — KEYCODE_BACK and onBackPressed are not delivered. Check PlayerActivity.onKeyDown and every back path (lock state, open sheets, PiP, settings, browser); make sure all use BackHandler/OnBackPressedDispatcher; add android:enableOnBackInvokedCallback="true" to the application in the manifest if missing. Fix whatever depends on KEYCODE_BACK. *Files*: AndroidManifest.xml, ui/player/PlayerActivity.kt, ui/player/PlayerControls.kt, ui/browser/BrowserActivity.kt, etc. *Accept*: predictive back and gestures work on Android 17 without KEYCODE_BACK dependencies.

**P10-05 Exit diagnostics** — on app start, read ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 5); for exits since the last check (store the last seen timestamp), log reason, description (Android 17 memory limiter shows "MemoryLimiter:AnonSwap"), pss/rss and timestamp to logcat with tag VibeExit, and show them in the existing diagnostics/about/stats screen if there is one. IO off the main thread. *Files*: diagnostics/ExitDiagnostics.kt, data/prefs/AppSettings.kt, ui/settings/SettingsScreen.kt, MainActivity.kt. *Accept*: logged to logcat with tag VibeExit, visible in about/diagnostics screen (device check open).

