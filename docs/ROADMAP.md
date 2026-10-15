# Roadmap

Source of truth for task status is `PROGRESS.md` and `TASKS.md`; this is a summary.

## Done

- P0: toolchain, device check, native libmpv build for arm64 (mpv v0.41.0, FFmpeg n9.0.2, dav1d 1.5.4,
  libass 0.17.5, libplacebo v7.360.1), JNI bridge, first playback.
- P1: player engine, surface lifecycle, controls, scrubbing, efficient-mode comparison, software fallback,
  debug overlay, open-with intent, styled ASS subtitles.
- P2: folder browser from MediaStore, sort order, thumbnails with disk cache and a software (mpv) fallback for
  AV1 and 10-bit clips, settings screen, storage access limited to videos (P2-11).
- P3: gestures (brightness, volume, swipe seek, double-tap skip, pinch zoom, lock, haptics).
- P4: per-file memory (position, tracks, speed, aspect), audio and subtitle pickers, speed, aspect modes,
  history row in the browser, default languages and per-folder track memory, auto-play next file, name and icon.
- P5: picture-in-picture mini player (P5-04).
- P6: Vulkan-enabled native build, renderer switch with Vulkan -> OpenGL ES fallback (P6-02), benchmark runner
  (P6-03), power comparison on today's driver (P6-08), per-format table as data keyed by the driver build
  (P6-06) and the best configuration chosen before opening, Auto by default (P6-07).
- P7: start time about 0.9 s (P7-02), browser smoothness (P7-05).

## In progress (code merged, device check left; the exact check is in PROGRESS.md)

- P3-09 hold for fast playback: the volume change on a right-side vertical swipe.
- P4-13 subtitle and audio delay, subtitle style: a visible subtitle shift, SRT size and position, Bocchi's
  styled ASS unchanged.
- P5-01 to P5-03, P5-05 playback service, media session, background audio, lifecycle: rotation, lock-screen
  controls and a headset button, pause on a call and on unplug, swipe from recents (these need the user).
- P6-01 quality mode: the Efficient <-> Quality toggle in the middle of a file.
- P7-01 release build and P7-04 Baseline Profile (being finished on the phone).
- P7-03 recomposition counts with the controls visible; P7-06 a 10-minute power comparison (the P1-06 baseline
  has no power figure); P7-07 memory "before" values and seek-back and drop checks with the smaller cache (the
  targets themselves are met: 216 MB at 1080p, 110 MB in the browser after playback).
- P8-01 a test shader visibly applied and removed; P8-02 Anime4K on OpenGL ES (compile fix in progress);
  P8-03 GSR sharpening on a 720p clip.
- HEVC 10-bit 4K drops: operating-rate hint to the decoder, confirmed by a device A/B, fix in progress.

## Planned

- P6-04 / P6-05: 5-minute matrix and 30-minute stability runs per renderer.
- P8-04: shader benchmark (5 minutes per preset on 1080p).
- P9: tuned native build (`-mcpu=cortex-a78`, ThinLTO) and comparison against the baseline build.

## Known issues

- B-08 (resolved as recovery, `BLOCKERS.md`): MediaCodec can stall at the start of an H.264 file (1 in 10 to 1
  in 20 cold opens on the default copy-back row, 4 in 20 on zero-copy). The watchdog abandons the stuck handle
  and reopens the file within 6 to 10 s; no black screen or ANR in 140+ test opens. The stall itself is not fixed.
- Anime4K presets fail to compile one pass on OpenGL ES (fix in progress).
- External subtitle files next to a video are not auto-loaded on Android 13+ (video-only storage access).
- Vulkan is experimental and depends on the owner's unfinished driver port.
- The release is signed with a debug key (see README).

## Ideas

- Lower minSdk for wider device support (needs code changes and testing on other phones).
- An LGPL build option without GPL-only FFmpeg components.
- Testing on more devices, and hardware AV1 decode where available.
