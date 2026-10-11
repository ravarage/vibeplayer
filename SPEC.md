# SPEC

## Goal
A personal Android video player with MX Player style UI/UX and the best possible performance on one
device: **POCO X5 Pro 5G (redwood)**, Snapdragon 778G, Adreno GPU, Android 17, arm64. Not for release.

## Fixed tech decisions (never change; see AGENT_RULES.md)
- Kotlin + Jetpack Compose UI. SurfaceView for video output.
- libmpv engine built from mpv-android buildscripts, **arm64-v8a only**.
- FFmpeg with libdav1d for AV1; dav1d uses all CPU cores.
- Two playback modes, both with automatic software fallback:
  - **Efficient**: direct MediaCodec hardware path for H.264/HEVC/VP9, lowest battery, no shaders.
  - **Quality**: GPU renderer with copy-back hardware decoding so shaders apply.
- Both Vulkan (gpu-next) and OpenGL ES output supported; the default is chosen by on-device benchmark
  (P6), with a setting to switch.
- `ao=audiotrack` so system Dolby effects apply.
- MediaStore for finding videos. Room for watch history and per-file settings.
- Keep libass, freetype, harfbuzz, fribidi: anime ASS subtitles must render correctly.
- FFmpeg trimming: disable programs, doc, encoders, muxers, devices, avdevice. Keep decoders and
  demuxers broad (no whitelist-only configure). Minimum that must be present:
  - video: h264, hevc, vp9, mpeg4, libdav1d
  - audio: aac, opus, flac, mp3, vorbis, ac3, eac3, dts, truehd
  - demuxers: matroska, mov, mpegts, avi, webm
  - subtitles: ass, subrip, hdmv_pgs_subtitle, dvd_subtitle
  - plus required parsers and bsfs
- Native flags: `-O2` during development. Task P9 only: `-O2 -mcpu=cortex-a78 -flto=thin`.
  Never `-O3` or `-Ofast`.
- mpv option names must be verified against the built mpv version before use (task P0-09).

## Features by phase
| Phase | Scope |
|---|---|
| P0 | libmpv + FFmpeg + dav1d build for arm64; minimal app that plays one file |
| P1 | Player core: SurfaceView, play/pause, seek bar, efficient mode with fallback |
| P2 | Folder based browser via MediaStore; background thumbnails with disk cache |
| P3 | MX gestures: left swipe brightness, right swipe volume, horizontal swipe seek, double tap skip 10 s, pinch zoom, lock button, haptics on seek and skip |
| P4 | Audio and subtitle track picker, speed, aspect ratio; resume position and chosen tracks per file (Room) |
| P5 | Picture-in-picture; background audio with MediaSession notification controls |
| P6 | Quality mode; Vulkan vs OpenGL ES benchmark and default selection |
| P7 | Performance pass: Baseline Profiles, R8, minimal recomposition, start under 1 s, release builds only |
| P8 | Optional shaders in quality mode: Anime4K light presets, Snapdragon GSR; toggle in settings |
| P9 | Optional native tuning with the final compile flags |

## Non-goals
- Other devices, ABIs, tablets, TV, Play Store release, localisation, accessibility polish.
- Network streaming, casting, DLNA, playlists, audio-only library, online subtitle download.
- Screenshots and any transcoding (FFmpeg encoders and muxers are disabled).
- DRM content.

## Quality bar
- Every task builds and is checked on the real device against its acceptance criteria.
- No crash or ANR in the flows a task touches (rotate, home and return, screen off and on).
- Efficient mode: H.264/HEVC/VP9 test clips decode in hardware with 0 dropped frames in 60 s of
  steady playback.
- AV1 1080p plays in software (dav1d) without sustained drops.
- `anime_ass.mkv` renders styled ASS (fonts, positioning, karaoke) correctly.
- From P7 on: tap to first frame under 1 s on a local 1080p file, measured on a release build.

## Test clips
Provided by the user on the device in `/sdcard/Movies/vibeplayer-test/` (task P0-02 checks them):

| File | Purpose |
|---|---|
| `ichiban.mp4` | H.264 8-bit, AAC |
| `hevc10_4k.mp4` | HEVC 10-bit 4K |
| `vp9_1080p.webm` | VP9, Opus (video only) |
| `[Ironclad] Clevatess - S02E01 [WEB.1080p.AV1].mkv` | AV1 software decode |
| _none_ (see B-02) | AV1 stress test (`av1_4k.mkv`) |
| `[denisplay] Bocchi the Rock! - S01E01.mkv` | heavily styled ASS, embedded fonts |
| _none_ (see B-02) | 2+ audio tracks (one AC3/EAC3/DTS), 2+ subtitle tracks (one PGS) (`multi_track.mkv`) |

## Measurement methods
- **Hardware decode**: mpv property `hwdec-current` (shown in the debug overlay, task P1-08).
- **Dropped frames**: mpv `frame-drop-count` (output) and `decoder-frame-drop-count`.
- **Start time**: logcat timestamp of the tap/intent to mpv `playback-restart` event, tag `VP-Perf`.
- **UI jank**: `adb shell dumpsys gfxinfo com.vibeplayer`.
- **Power**: average `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` over a fixed clip and duration,
  screen brightness fixed, device unplugged (wireless adb).
