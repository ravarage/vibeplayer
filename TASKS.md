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
- _P6-02: keep `Renderer.VULKAN` in the code but offer it only when the device reports Vulkan 1.2 or
  newer (`PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`) **and** the loaded libmpv lists `vulkan` in
  its `gpu-api` choices; otherwise hide the choice and use GLES. The user plans a system upgrade that
  may bring Vulkan 1.2/1.3, so the path must light up without code changes: rebuild with
  `VULKAN=1 native/build.sh` (applies `native/patches/optional/0004-vulkan-shaderc.patch`) and re-run
  the P6 benchmark. Add that switch to `native/build.sh` in P6-02._
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

**P6-01 Quality mode** — Quality option table; mode setting and in-player toggle using
`reconfigure()`. *Files*: PlaybackMode.kt, PlayerEngine.kt, ui/settings/*. *Accept*: overlay shows
`gpu-next` and `mediacodec-copy`; switching mid-file keeps position and tracks.

**P6-02 Renderer switch** — Vulkan / OpenGL ES setting for quality mode. *Files*: same.
*Accept*: both renderers play all clips; overlay shows the active API.

**P6-03 Benchmark harness** — Debug-only runner: plays a clip for N seconds and writes a CSV row
(drops, decoder drops, app CPU %, GPU busy if readable, average battery current, temperature).
*Files*: app/src/debug/.../Benchmark.kt. *Accept*: one adb command produces a CSV on the device.

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

**P7-03 Recomposition** — Verify only the seek bar and time label recompose on position ticks; fix
unstable parameters. *Files*: ui/player/*. *Accept*: recomposition counts (Layout Inspector or
logged) for other controls stay flat during 60 s playback.

**P7-04 Baseline Profile** — `:baselineprofile` module covering launch, browse, open video.
*Files*: baselineprofile/*, app/build.gradle.kts. *Accept*: profile generated on device and packaged;
cold start improves or stays equal (numbers in docs/BENCHMARK.md).

**P7-05 Browser smoothness** — Fix list jank (image decoding off main, stable keys).
*Files*: ui/browser/*. *Accept*: `dumpsys gfxinfo` janky frames under 5% for a fast scroll of a
100+ item folder.

**P7-06 Idle cost** — No position/stat updates or recomposition while controls are hidden.
*Files*: PlayerEngine.kt, ui/player/*. *Accept*: efficient-mode power for a 10 min clip is not higher
than the P1-06 figure.

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
