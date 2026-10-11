# TASKS

## USER FEEDBACK
_Only the user writes here. Agents: read before every session; this outranks task text._

-

---

Format: **ID Title** — description. *Files*: likely touched. *Accept*: checks on the device.
Clips are in `/sdcard/Movies/vibeplayer-test/` (SPEC.md). Tasks are sequential unless marked
**[parallel safe]**. "Overlay" means the debug overlay from P1-08.

## P0 Native build and first playback

**P0-01 Toolchain check** — Record host tool versions; pick the JDK Gradle will use (only JDK 27 is
installed; prefer a Gradle toolchain download over sudo). *Files*: ARCHITECTURE.md (pinned versions).
*Accept*: versions recorded; if sudo is required, BLOCKERS entry instead.

**P0-02 Device check** — Confirm the device over adb and record facts. *Files*: ARCHITECTURE.md
(device facts). *Accept*: `adb devices` shows redwood; SDK level, Vulkan and GLES versions recorded;
all 7 test clips exist, otherwise BLOCKERS entry listing the missing ones.

**P0-03 Fetch mpv-android** — `native/fetch.sh` clones mpv-android at a pinned commit; `.gitignore`
covers it. *Files*: native/fetch.sh, .gitignore, ARCHITECTURE.md. *Accept*: fresh run reproduces the
same commit; mpv/FFmpeg/dav1d/libass versions recorded.

**P0-04 Download SDK, NDK, deps** — Run the buildscripts' download step; confirm what it fetches.
*Files*: native/build.sh (download stage), ARCHITECTURE.md. *Accept*: SDK, NDK and all dependency
sources present; NDK version recorded; the "candidate" note in Native build replaced by facts.

**P0-05 FFmpeg trim** — Patch the FFmpeg configure flags per SPEC (disable programs, doc, encoders,
muxers, devices, avdevice; libdav1d on; decoders/demuxers stay broad). Confirm mpv builds without
avdevice. *Files*: native/patches/*. *Accept*: patch applies cleanly on a fresh fetch; FFmpeg
configure output shows no encoders/muxers and libdav1d enabled.

**P0-06 Build native for arm64** — `native/build.sh` builds everything arm64-only at `-O2` and copies
the `.so` files to `player/src/main/jniLibs/arm64-v8a/`. *Files*: native/build.sh, AGENT_RULES.md
(commands). *Accept*: `libmpv.so` is aarch64 (`readelf -h`); no libavdevice; a second run is a fast
no-op; build time noted.

**P0-07 Gradle skeleton** — `:app` and `:player` modules, Compose, `abiFilters arm64-v8a`, minSdk =
device SDK, empty MainActivity. *Files*: settings/build gradle files, app/, player/.
*Accept*: `./gradlew :app:assembleDebug` succeeds; app installs and shows a blank Compose screen;
versions recorded.

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

**P1-01 Event thread and state** — Native event loop, `observeProperty`, `PlayerState`, separate
position flow. *Files*: mpv_jni.c, PlayerEngine.kt, PlayerState.kt. *Accept*: logcat shows pause,
duration and position changes arriving as flow updates.

**P1-02 PlayerEngine API** — `load`, `play`, `pause`, `seekTo`, `release` on a single command thread.
*Files*: PlayerEngine.kt. *Accept*: no mpv call on main (StrictMode or thread assert); load/seek/release
100 times in a loop without crash or leak.

**P1-03 Surface lifecycle** — Attach/detach on surface created/destroyed; survive rotate, home and
return, screen off and on. *Files*: PlayerEngine.kt, mpv_jni.c, ui/player/VideoSurface.kt.
*Accept*: 10 rotations and 10 home/return cycles, playback continues, no crash, no black screen.

**P1-04 Player screen** — Compose controls over the surface: play/pause, time labels, seek bar, title,
back; auto-hide after 3 s; immersive fullscreen; keep screen on. *Files*: ui/player/*.
*Accept*: controls work and hide; system bars hidden during playback.

**P1-05 Scrubbing** — Fast keyframe seeks while dragging, exact seek on release; position label
follows the thumb. *Files*: ui/player/SeekBar.kt, PlayerEngine.kt. *Accept*: dragging across a 4K
file stays responsive; released position is exact within 1 s.

**P1-06 Efficient mode candidates** — Implement both efficient paths from ARCHITECTURE.md behind a
debug switch; play `h264_1080p.mp4` 5 minutes on each and record power and drops. *Files*:
PlaybackMode.kt, docs/BENCHMARK.md. *Accept*: `hwdec-current` is a mediacodec variant for the H.264,
HEVC and VP9 clips on each working path; numbers recorded. The choice is made after P1-10.

**P1-07 Software fallback and dav1d threads** — Detect and expose fallback; confirm AV1 uses dav1d on
all cores. *Files*: PlayerEngine.kt, PlayerState.kt. *Accept*: `av1_1080p.mkv` plays with
`hwdec-current=no`; `top -H` shows 8 busy decoder threads on `av1_4k.mkv`; forcing a hwdec failure
still plays.

**P1-08 Debug overlay** — Toggle (long-press title) showing vo, hwdec-current, mode, resolution, fps,
both drop counters. Stats flow observed only while open. *Files*: ui/player/DebugOverlay.kt.
*Accept*: values update live; closed overlay causes no stats updates in logcat.

**P1-09 Open-with intent** — Handle `ACTION_VIEW` for `video/*` (file and content URIs); orientation
follows video aspect. *Files*: manifest, MainActivity.kt, ui/player/*. *Accept*: the "Play a test
clip" command in AGENT_RULES.md works; opening from a file manager works.

**P1-10 ASS subtitles and efficient path decision** — Check styled ASS (embedded fonts) and software
fallback on both efficient paths; write the P1-06 numbers and trade-offs into a BLOCKERS entry for the
user to pick the path. *Files*: BLOCKERS.md, ARCHITECTURE.md. *Accept*: per-path result for
`anime_ass.mkv` (fonts, positions, karaoke) recorded; task is BLOCKED until the user answers, then
the chosen path becomes the efficient default and the mode table is updated.

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

**P4-07 Persist per-file choices** — Save and restore audio track, subtitle track/off, speed, aspect.
*Files*: PlayerViewModel.kt, data/db/*. *Accept*: choices survive app restart for that file only.

**P4-08 History in browser** — Progress bar on thumbnails, "Continue watching" row.
*Files*: ui/browser/*. *Accept*: partially watched clips show progress; row order = most recent first.

## P5 PiP and background

**P5-01 PlaybackService** — Move engine ownership into a foreground service; ViewModel binds to it.
*Files*: playback/PlaybackService.kt, PlayerViewModel.kt, manifest. *Accept*: all P1 to P4 behaviour
unchanged; rotation does not restart playback.

**P5-02 MediaSession and notification** — Session with metadata, play/pause/seek/skip actions.
*Files*: playback/*. *Accept*: notification and lock screen controls work; headset button works.

**P5-03 Background audio** — Setting to continue audio when leaving the player; video output
detached while in background; audio focus and unplug (becoming noisy) handled. *Files*: playback/*.
*Accept*: audio continues with screen off; pauses on call or unplug; video returns on reopen.

**P5-04 Picture-in-picture** — Enter on home (auto-enter) and by button; aspect from video; PiP
play/pause and skip actions. *Files*: MainActivity.kt, playback/PipHelper.kt. *Accept*: PiP window
has the right aspect; actions work; controls hidden in PiP.

**P5-05 Lifecycle edge cases** — PiP ↔ fullscreen, PiP close, swipe from recents, opening a new file
while playing. *Files*: playback/*. *Accept*: none of these crash or leave a stale notification.

## P6 Quality mode and renderer benchmark

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

**P7-01 Release build** — R8 full mode, resource shrinking, local signing config, keep rules for JNI.
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
