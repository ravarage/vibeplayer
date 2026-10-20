# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.0] - unreleased (versionCode 5)

Built without the phone; every item still needs its device check (PROGRESS.md) before this is released.

### Added
- **Chapters (P4-15)**: ticks on the seek bar, chapter name while scrubbing, a chapter list, the current chapter
  in the top bar.
- **Skip opening / ending (P11-01)**: a skip button on chapters named Opening, OP, Ending, ED, Credits; optional
  auto-skip.
- **Volume boost (P11-02)**: up to 150 % or 200 % past the media maximum with the swipe and volume keys.
- **Night mode (P11-03)**: evens out loud music and quiet dialogue (dynaudnorm).
- **Subtitle folders (P11-04)**: subtitle files next to a video in a granted folder load automatically.
- **Subtitle encoding (P11-04)**: external subtitles in UTF-16 or a legacy code page (Arabic, Cyrillic, CJK and
  others) are converted to UTF-8.
- **More sheet (P11-05)**: sleep timer, A-B repeat, loop this video, rotate.
- **Frame step (P11-06)** while paused.
- **Search (P11-07)** across all videos and within a folder.
- **Compact panels (P11-08)**: audio, subtitles, speed, sync, chapters and More open as a side panel in
  landscape, so the video stays visible; smaller popups; the portrait top bar fits.
- **ViPER4Android and equalizer apps (P11-09)** can attach to the player's audio.
- **Picture adjustments (P11-10)**: brightness, contrast, saturation and gamma in the More panel.
- **Subtitle text style (P11-11)**: colour, outline, background box and bold for SRT and other plain subtitles.
- **Equalizer (P11-12)**: 10 bands with presets (Flat, Bass boost, Voice, Treble, Loudness).
- **Browser (P11-13)**: folder name in search results, a ✓ on watched videos, best matches first.
- **Screenshot tests**: the player panels and browser rows render on the JVM (Robolectric, Roborazzi).

### Fixed
- **Anime4K on OpenGL ES (P8-02)**: the de-ring pass is rewritten so it can compile on the Adreno driver (device
  check open).

## [0.6.0] - 2026-10-16 (versionCode 4)

Entry added afterwards from the GitHub release notes.

### Added
- **HDR output (experimental, off by default)** on Vulkan `gpu-next`: HDR10 and HLG clips play as HDR; SDR again in
  PiP, on exit and for SDR files.
- **Baseline profile** generated on the device: the browser and player start from precompiled code.

### Changed
- Quality mode toggle mid-file keeps position and tracks.
- Background audio checked on Android 17 with audio hardening on; screen-off no longer stops PiP playback when
  background audio is on.

### Fixed
- Native (JNI) null and exception checks, callbacks dropped after release, settings recover from a corrupt file,
  MediaStore permission and missing-column errors handled.
- Thumbnails of clips whose keyframes are all black now show a real frame.

## [0.5.0] - 2026-10-15

Features marked **(beta)** are merged but their device acceptance check is not finished
(status per task in `PROGRESS.md`, reviewed up to commit c32a50b).

### Added

#### Video rendering and formats
- **Best configuration per format, chosen before opening (Auto renderer, the default)**: the format is detected
  before the file opens and the decoder and renderer come from a data table measured on the reference phone and
  keyed by the Vulkan driver build (`player/src/main/assets/format_table.json`): H.264 8-bit -> MediaCodec
  copy-back on Vulkan `gpu-next`; HEVC and VP9 -> MediaCodec zero-copy on OpenGL ES `gpu`; AV1 -> dav1d on
  Vulkan `gpu`; Hi10P -> software on Vulkan `gpu`.
- **Renderer setting**: Auto (default) / Vulkan (experimental) / OpenGL ES; the Vulkan renderer is offered only
  when a runtime probe succeeds and libmpv lists `vulkan`.
- **Fallback chain**: Vulkan -> OpenGL ES -> software decoding at runtime, keeping the playback position, with an
  on-screen notice and `VULKAN-FAIL` / `GLES-FAIL` log lines; a Vulkan failure is remembered per format for the
  driver build.
- **Quality mode**: settings and in-player toggle between Efficient and Quality (`gpu-next`, MediaCodec
  copy-back) (beta: the mid-file toggle is not yet checked on the device).
- **Shader presets** in Quality mode: Anime4K v4.0.1 fast modes A, B, C and Snapdragon Game Super Resolution v1
  (beta: Anime4K compiles on Vulkan only for now, see Known issues).
- **Benchmark runner** (debug builds): one adb broadcast plays a clip and writes a summary CSV plus 1 Hz samples
  on the device; `tools/bench/run_matrix.py` runs matrices from the host.

#### Playback service and background
- **PlaybackService**: the engine lives in a foreground service; reopening the player re-attaches without
  reloading (beta).
- **MediaSession and notification**: play/pause, seek, previous/next from the notification and media buttons
  (beta: lock screen and a real headset button not yet checked).
- **Background audio**: audio keeps playing with the screen off or the player left, video decoding stops while
  in the background; audio-focus ducking and becoming-noisy pause (beta: call and unplug not yet checked).
- **Picture-in-picture mini player** with aspect-ratio clamping, auto-enter on home, auto-advance inside the
  mini player.
- **Auto-play next file** in the folder; previous / next buttons.
- **Hold for fast playback**: press and hold on the right half for the hold speed (1.5x, 2x, 3x, 4x) (beta).

#### Subtitles and audio
- **External subtitle loading via SAF (P4-14)**: "Load subtitle file…" in the subtitle sheet loads external subtitles (`.srt`, `.ass`, etc.) via the system document picker with persistent track memory and automatic re-attachment on reopen.
- **Subtitle and audio delay** in 50 ms steps, saved per file (Room schema 3 -> 4).
- **Subtitle size, position and ASS style override** settings (override off by default).
- **Default languages and per-folder track memory** (Room schema 2 -> 3).

#### Platform integration and diagnostics
- **Display refresh rate adaptation (P10-01)**: `Surface.setFrameRate` integration providing seamless display refresh rate voting matching video container framerate (e.g. 23.976/24 fps).
- **Thermal throttling adaptation (P10-02)**: `PowerManager` thermal status monitoring with stats overlay row and automatic quality step-down under thermal pressure.
- **Display cutout / edge-to-edge layout (P10-03)**: Edge-to-edge playback with `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` extending video under the camera punch-hole in crop/zoom mode with cutout-safe padding for controls.
- **Predictive back navigation (P10-04)**: Full back handling audit with `enableOnBackInvokedCallback="true"`, ensuring smooth predictive back transitions across player, settings, and sheets.
- **Exit diagnostics (P10-05)**: Historical process exit reason analysis via `ActivityManager.getHistoricalProcessExitReasons`, logging `VibeExit` to logcat on startup and displaying recent termination reasons in Settings About.

#### Browser
- **Continue Watching row** with progress bars.
- **Software thumbnails** (libmpv) for 10-bit HEVC, AV1 and clips Android's MediaMetadataRetriever cannot read,
  with dark-frame detection.

#### Performance
- **Tuned native libraries**: ships tuned native libraries compiled with `-O2 -mcpu=cortex-a78 -flto=thin` optimized specifically for Snapdragon 778G Cortex-A78 cores, reducing frame drops in heavy software decoding (AV1 4K).
- **Start time**: about 0.9 s from the open intent to the first frame (release build, median 882 ms for 1080p
  H.264, 798 ms for `ichiban.mp4`).
- **Memory budget**: mpv demuxer cache 32 MiB forward / 16 MiB back for local files, smaller thumbnail cache,
  `onTrimMemory`; 1080p H.264 playback 216 MB PSS (was 428 MB), browser after closing the player 110 MB.
- **Browser smoothness**: 0 to 0.5 % janky frames on fast scrolls of a 925-video folder (release build).
- **Release build**: R8 and resource shrinking with keep rules for the JNI callbacks; shared local signing allows in-place updates preserving user watch history. Automated devcheck verified: 23 PASS, 0 FAIL, 2 SKIP.
- **Baseline Profile module** (`:baselineprofile`): prepared; the profile has not been generated on the device yet.

### Changed
- Storage access limited to video files: `MANAGE_EXTERNAL_STORAGE` removed, only `READ_MEDIA_VIDEO`
  (`READ_EXTERNAL_STORAGE` up to Android 12).
- Player sheets (tracks, subtitles, speed, sync) open fully expanded in landscape.

### Fixed
- **B-08 start stall recovery**: a watchdog detects a decoder that never delivers the first frame, abandons the
  stuck handle and reopens the file (with software decoding for that open), with no ANR.
- **HEVC / H.264 frame drops (P6-08)**: MediaCodec operating-rate hint (`vd-lavc-o=operating_rate`) eliminates frame drops on 4K 10-bit clips while keeping hardware decoding (`mediacodec-copy`).
- Bounded main-thread surface detach (1.5 s) so a stuck mpv core cannot hang the UI.
- Saved resume positions no longer go stale while the controls are hidden.
- Closing the mini player with its X now ends the session (no stale notification).
- Benchmark mode always answers `startForegroundService` with `startForeground` (fix for an ANR; unit-tested,
  device re-check still open).
- Double Tap Skip Interval setting is applied (it was always 10 s before).

### Known issues
- **B-08**: MediaCodec can still stall at the start of an H.264 file (1 in 20 and 1 in 10 cold opens in two runs on
  the default copy-back row, 4 in 20 on zero-copy); the watchdog recovers it in 6 to 10 s.
- **Anime4K on OpenGL ES**: presets A, B and C fail to compile one pass (Clamp_Highlights) on OpenGL ES; a fix
  is in progress. They work on Vulkan; Snapdragon GSR works on both.
- **External subtitles in shared storage**: `.srt` / `.ass` files next to a video in shared storage cannot be auto-discovered on Android 13+ due to storage permissions; users can load them via the "Load subtitle file…" SAF picker (P4-14).
- **Vulkan is experimental**: it depends on the owner's unfinished driver port; the stock driver (Vulkan 1.1)
  cannot run it and the app uses OpenGL ES.

---

## [0.4.0] - 2026-10-12

Initial release state establishing core media player capabilities.

### Added
- **Core Engine**: Embedded libmpv runtime with custom JNI bindings, arm64-v8a native compilation, and hardware decoding via Android MediaCodec (H.264, HEVC, VP9) and software decoding via dav1d (AV1).
- **Touch Gestures**: MX Player-inspired gesture controls including vertical swipe for brightness (left) and volume (right), horizontal swipe to seek, double-tap to skip (+/-10s), pinch to zoom and pan, screen lock toggle, and haptic feedback.
- **Subtitles & Audio**: High-fidelity styled ASS subtitle rendering with embedded fonts (libass), external SRT/PGS subtitle loading, and interactive audio and subtitle track selection sheets.
- **Playback Controls**: Playback speed control (0.25x to 4x) with pitch correction (scaletempo2), and aspect ratio modes (fit, crop, stretch, 16:9, 4:3).
- **State Persistence**: Room database integration saving playback position, track choices, speed, and aspect ratio per file.
- **File Browser**: MediaStore-backed folder and video browsing with thumbnail generation and disk caching.
- **UI & System**: Fullscreen immersive controls with 3-second auto-hide, debug overlay for live playback statistics, open-with intent support (`file://` and `content://` URIs), and application branding and launcher icons.
