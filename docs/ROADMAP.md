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
  Quality mode with the toggle in the middle of a file (P6-01), short benchmark matrix (P6-04), 30-minute
  stability runs on both renderers (P6-05).
- P4-13: subtitle and audio delay, subtitle style.
- P7: release build (P7-01), start time about 0.9 s (P7-02), recomposition counts (P7-03), browser smoothness
  (P7-05).
- P8: Anime4K presets (P8-02), shader benchmark (P8-04: 60 s per preset; Anime4K A and B drop 13.5 frames/s on
  1080p, GPU-bound; the others drop none).
- P9-01: tuned native libraries (`-mcpu=cortex-a78`, ThinLTO) ship as the default.
- P10: frame-rate vote, thermal step-down, display cutout, back handling, exit diagnostics (debug builds only),
  ProfilingManager triggers (P10-07).
- P11: subtitle folders and encodings, Unicode first (P11-04: Kurdish, Arabic, Japanese, BOM-less UTF-16),
  sleep timer, A-B repeat, loop and rotate (P11-05), frame step (P11-06), compact panels and portrait top bar
  (P11-08).
- P12: resume chip, up-next countdown, bookmarks, per-folder speed and auto-skip, rotated clips upright,
  previous/next subtitle line, shader switch and compare, backup and restore (P12-01..04, 07, 08, 10).
- P13: network screen, remote browser, network playlists with their subtitles (P13-03, P13-04).
- 4K playback: HEVC 10-bit 4K was dropping frames. Two fixes: the MediaCodec operating-rate hint, and clips
  above 2560x1440 always decode zero-copy on OpenGL ES, because copy-back cannot carry 4K (47.8 drops/s on
  Vulkan vs 0 zero-copy; ARCHITECTURE.md, "Copy-back size limit"). In 0.9.0.

## In progress (code merged, device check left; the exact check is in PROGRESS.md)

- P3-09 hold for fast playback: the volume change on a right-side vertical swipe.
- P5-01 to P5-03, P5-05 playback service, media session, background audio, lifecycle: rotation, lock-screen
  controls and a headset button, pause on a call and on unplug, swipe from recents (these need the user).
- P7-04 Baseline Profile; P7-06 a 10-minute power comparison (the P1-06 baseline has no power figure); P7-07
  memory "before" values and seek-back and drop checks with the smaller cache (the targets themselves are met:
  216 MB at 1080p, 110 MB in the browser after playback).
- P8-01 a test shader visibly applied and removed; P8-03 GSR sharpening on a 720p clip.
- P9-02 tuned vs default libraries comparison.
- P11-01..03, 09..13 (skip opening, volume boost, night mode, effect apps, picture adjustments, subtitle text
  style, equalizer, browser extras): by-hand checks in PROGRESS.md.
- P11-07 browser search: works, but typing in a 937-video folder costs about one janky frame per key press
  (99th percentile 61 ms on the release build).
- P12-05 rename and delete, P12-06 hidden folders, P12-09 stats overlay: by-hand checks.
- P13-01, P13-02 network links and saved sources: SFTP, SMB, WebDAV, FTP, DLNA and a real IPTV list by hand.

## Planned

Nothing new is scheduled; the next work is closing the checks above.

## Known issues

- B-08 (resolved as recovery, `BLOCKERS.md`): MediaCodec can stall at the start of an H.264 file (1 in 10 to 1
  in 20 cold opens on the default copy-back row, 4 in 20 on zero-copy). The watchdog abandons the stuck handle
  and reopens the file within 6 to 10 s; none stayed black in 160 test opens, and no ANR in the
  60-open run that recorded it. The stall itself is not fixed.
- Anime4K runs on Vulkan only: the Adreno OpenGL ES compiler rejects its passes (a notice is shown instead).
- Anime4K A and B drop about 13 frames/s on 1080p even on Vulkan (GPU-bound, P8-04); the lighter presets are
  smooth.
- With renderer set to Vulkan, 4K clips keep copy-back and drop frames (needed for experimental HDR output);
  Auto and OpenGL ES play them smoothly.
- Subtitle files next to a video load automatically only from folders granted in Settings > Subtitle folders
  (the app may only read videos on its own).
- ADPF performance hints (P10-06) do nothing on this phone: it has no ADPF support.
- Vulkan is experimental and depends on the owner's unfinished driver port.
- The release is signed with a debug key (see README).

## Ideas

minSdk stays 37 (Android 17): the app is built for one phone and one OS version and uses Android 17 APIs.

Network playback (links, SFTP, SMB, FTP, WebDAV, DLNA as a client, M3U / IPTV) is in scope since 2026-10-21 (P13).
Casting (Chromecast, DLNA send-to-TV) stays a non-goal (SPEC.md), decided with the owner 2026-10-20: on a TV the TV decodes
and renders, so styled ASS, the shaders and the tuned decoding do not apply; the Google Cast library is closed
source, which clashes with the GPL build; and the app cannot transcode (encoders are disabled). For the TV, use
Android's screen cast (mirrors the player as it looks, ASS and shaders included). DLNA "send to TV" would be the
clean option if ever wanted, for files the TV plays itself.


- An LGPL build option without GPL-only FFmpeg components.
- Testing on more devices, and hardware AV1 decode where available.

## Platform features deferred from the Android 17 research (2026-10-15)

Implemented as P10-01..05 (frame-rate vote, thermal step-down, display cutout, back handling, exit
diagnostics). Not yet scheduled:

- Android 17 background-audio hardening: test with `adb shell cmd audio set-enable-hardening enable`
  (then `throw`) that screen-off playback and resume from the notification or a media key still produce
  audio; consider moving to Media3 `MediaSessionService` (the documented route). Needs the phone.
- ADPF hint session (`PerformanceHintManager`, `setPreferPowerEfficiency`) for the mpv render, demux and
  dav1d threads: may reduce AV1 4K drops or power; vendor-dependent. Implemented behind a setting (default
  off) as P10-06; closed as won't do on this phone (no ADPF support, 2026-10-20).
- FFmpeg MediaCodec wrapper patch: set `priority=0` and `frame-rate`, then check whether the 2x
  operating-rate hint can be lowered for the same zero drops at less power. Needs a battery A/B.
- HDR output (`setDesiredHdrHeadroom`, `COLOR_MODE_HDR`): depends on mpv/libplacebo Android HDR support;
  speculative.
- `ProfilingManager` triggers (API 37) to capture traces of rare stalls: done as P10-07.
