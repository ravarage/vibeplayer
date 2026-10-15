# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- **Subtitle and audio delay** in 50 ms steps, saved per file (Room schema 3 -> 4) (beta).
- **Subtitle size, position and ASS style override** settings (override off by default) (beta).
- **Default languages and per-folder track memory** (Room schema 2 -> 3).

#### Browser
- **Continue Watching row** with progress bars.
- **Software thumbnails** (libmpv) for 10-bit HEVC, AV1 and clips Android's MediaMetadataRetriever cannot read,
  with dark-frame detection.

#### Performance
- **Start time**: about 0.9 s from the open intent to the first frame (release build, median 882 ms for 1080p
  H.264, 798 ms for `ichiban.mp4`).
- **Memory budget**: mpv demuxer cache 32 MiB forward / 16 MiB back for local files, smaller thumbnail cache,
  `onTrimMemory`; 1080p H.264 playback 216 MB PSS (was 428 MB), browser after closing the player 110 MB.
- **Browser smoothness**: 0 to 0.5 % janky frames on fast scrolls of a 925-video folder (release build).
- **Release build**: R8 and resource shrinking with keep rules for the JNI callbacks.
- **Baseline Profile module** (`:baselineprofile`): prepared; the profile has not been generated on the device yet.

### Changed
- Storage access limited to video files: `MANAGE_EXTERNAL_STORAGE` removed, only `READ_MEDIA_VIDEO`
  (`READ_EXTERNAL_STORAGE` up to Android 12).
- Player sheets (tracks, subtitles, speed, sync) open fully expanded in landscape.

### Fixed
- **B-08 start stall recovery**: a watchdog detects a decoder that never delivers the first frame, abandons the
  stuck handle and reopens the file (with software decoding for that open), with no ANR.
- Bounded main-thread surface detach (1.5 s) so a stuck mpv core cannot hang the UI.
- Saved resume positions no longer go stale while the controls are hidden.
- Closing the mini player with its X now ends the session (no stale notification).
- Benchmark runs no longer trigger the "startForegroundService() did not then call startForeground()" ANR.
- Double Tap Skip Interval setting is applied (it was always 10 s before).

### Known issues
- **B-08**: MediaCodec can still stall at the start of an H.264 file (1 in 20 and 1 in 10 cold opens in two runs on
  the default copy-back row, 4 in 20 on zero-copy); the watchdog recovers it in 6 to 10 s.
- **Anime4K on OpenGL ES**: presets A, B and C fail to compile one pass (Clamp_Highlights) on OpenGL ES; a fix
  is in progress. They work on Vulkan; Snapdragon GSR works on both.
- **HEVC 10-bit 4K drops** (~40 of 60 frames per second on `hevc10_4k.mp4`): cause found (no operating-rate
  hint to the MediaCodec decoder; `operating_rate=120` gave 0 drops in a device A/B); the fix is pending its
  device confirmation and a power check.
- **External subtitles**: `.srt` / `.ass` files next to a video in shared storage are not auto-loaded on
  Android 13+, because the app only has video access.
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
