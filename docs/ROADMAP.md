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

## Next version (0.9.0) candidates, drafted 2026-10-21

Not started. Each was checked against the code (none of these exist yet) and against the non-goals in SPEC.md.
The owner picks what goes in; the chosen items become Phase 14 in TASKS.md. Size: S about a day, M two to
three days, L a week. "Phone" means it can only be finished with device checks.

### Watching (player)
1. **Seek bar thumbnails** (M, phone). A small picture above the seek bar while scrubbing, from a second
   low-resolution mpv handle or the existing thumbnail decoder at the scrub position; local files only, cached
   per file. The most asked-for feature of players like MX and mpvKt that this one lacks.
2. **Two subtitles at once** (S). mpv's `secondary-sid`: a second track (for example English under Japanese)
   picked in the Subtitles panel, remembered per file like the first. Language learners and anime.
3. **Drag subtitles up and down** (S). A vertical drag on the subtitle area moves `sub-pos`, saved per file;
   today the position is only in the style settings.
4. **Smooth motion** (M, phone). Optional `video-sync=display-resample` with `interpolation` on Vulkan for
   24 fps content on the 120 Hz panel (less judder on pans). Off by default; benchmark power before it ships.

### Library
5. **Queue / play list** (M). "Play next" and "Add to queue" from a long-press, a queue sheet in the player,
   mixing local and network files. Today next/previous only follows the folder or the network listing.
6. **Network items in Continue Watching** (S). Network files already remember their position; show them in the
   Continue Watching row with the source name, and reopen through the saved source.
7. **Quick Settings tile and app shortcuts** (S). "Resume last video" as a tile and as a long-press shortcut on
   the launcher icon.

### Network (after P13)
8. **Jellyfin / Emby client** (L). Their own API on top of DLNA: library by series and season, watched state
   synced both ways, subtitles from the server, direct play only (no server transcoding requests). Big, but
   it is the natural next step for someone with a home server.
9. **Save for offline** (M). Copy a file from SFTP / SMB / WebDAV / HTTP to the phone in the background with a
    progress notification (Android 16+ progress-style), resumable. Useful before a trip.
10. **Thumbnails for network videos** (M). Generated once from the first megabytes over SFTP / SMB / HTTP and
    cached, so remote folders look like local ones. Only on Wi-Fi, off on metered networks.
11. **Explain blocked network in the app** (S). When every connection times out, say that the ROM may be
    blocking network for the app (the restricted networking mode found on 2026-10-21) and link to its app
    settings page, instead of a plain timeout.

### Quality and upkeep
12. **Run the overnight suite** (phone, about 2.5 h, no code). Skipped for 0.8.0: 30-minute stability on
    Vulkan and OpenGL ES, the shader benchmark (including FSRCNNX and RAVU) and the full regression. Do it
    before 0.9.0 work so new features start from known numbers.
13. **By-hand P13 checks** (phone). SFTP, SMB, WebDAV, FTP, DLNA, a real IPTV list, Open with from a browser.
14. **HEVC 10-bit 4K drops** (phone). Finish the operating-rate fix that is in progress.
15. **P9 tuned native build** (M, phone). `-mcpu=cortex-a78` and ThinLTO, compared against today's build.

### Left out on purpose
Casting, online subtitle download, EPG / recording, screenshots, transcoding and DRM stay non-goals (SPEC.md).

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
