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
- P8-01 a test shader visibly applied and removed; P8-03 GSR sharpening on a 720p clip.
- P11 and P12 (player extras, watching series): the by-hand checks in PROGRESS.md; P12 automatic checks in
  `tools/shower_check.sh`.
- P13 network playback: `tools/network_check.sh`, then SFTP, SMB, WebDAV, FTP, DLNA and a real IPTV list by hand.
- HEVC 10-bit 4K drops: operating-rate hint to the decoder, confirmed by a device A/B, fix in progress.

## Planned

- P6-04 / P6-05: 5-minute matrix and 30-minute stability runs per renderer (P6-05 in `tools/overnight.sh`).
- P8-04: shader benchmark (5 minutes per preset on 1080p, Vulkan; in `tools/overnight.sh`).
- P9: tuned native build (`-mcpu=cortex-a78`, ThinLTO) and comparison against the baseline build.

## Known issues

- B-08 (resolved as recovery, `BLOCKERS.md`): MediaCodec can stall at the start of an H.264 file (1 in 10 to 1
  in 20 cold opens on the default copy-back row, 4 in 20 on zero-copy). The watchdog abandons the stuck handle
  and reopens the file within 6 to 10 s; none stayed black in 160 test opens, and no ANR in the
  60-open run that recorded it. The stall itself is not fixed.
- Anime4K runs on Vulkan only: the Adreno OpenGL ES compiler rejects its passes (0.7.0 shows a notice instead).
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
  off) as P10-06; the device A/B (drops and current, setting off vs on) decides whether it stays.
- FFmpeg MediaCodec wrapper patch: set `priority=0` and `frame-rate`, then check whether the 2x
  operating-rate hint can be lowered for the same zero drops at less power. Needs a battery A/B.
- HDR output (`setDesiredHdrHeadroom`, `COLOR_MODE_HDR`): depends on mpv/libplacebo Android HDR support;
  speculative.
- `ProfilingManager` triggers (API 37) to capture traces of rare stalls.
