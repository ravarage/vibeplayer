<p align="center"><img src="branding/icon.svg" width="128" alt="Vibe Player icon"></p>

<h1 align="center">Vibe Player</h1>

<p align="center">
  <img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="Platform: Android 17 arm64" src="https://img.shields.io/badge/platform-Android%2017%20%7C%20arm64--v8a-green">
  <img alt="Version 0.5.0" src="https://img.shields.io/badge/version-0.5.0-orange">
</p>

Vibe Player is a personal Android video player built on libmpv, with MX Player-style touch gestures,
styled ASS subtitles, hardware decoding where the phone supports it and software AV1 where it does not.
It is developed for one phone (POCO X5 Pro 5G) and published as is.

## Screenshots

Screenshots will be added later.

## Features

Features marked **beta** are merged but not yet verified on the device.

- Gestures: brightness, volume, swipe to seek, double-tap skip, pinch zoom and pan, lock, haptics;
  hold for 2x speed (**beta**)
- Hardware decoding of H.264, HEVC and VP9 through MediaCodec; AV1 with dav1d on all cores
- Styled ASS subtitles with embedded fonts (libass); audio and subtitle track pickers
- Subtitle and audio sync adjustment (-/+ 50 ms) (**beta**)
- Global subtitle styling: size, position, and ASS style override toggle (**beta**)
- Speed 0.25x to 4x with pitch correction; aspect modes (fit, crop, stretch, 16:9, 4:3)
- Per-file memory of position, tracks, speed, aspect, and sync delays; per-folder track memory and default languages
- Auto-play of the next file in the folder; previous / next
- Picture-in-picture mini player
- Background playback with a media notification (**beta**)
- Folder browser with thumbnails (software thumbnails for AV1 and 10-bit video) and a continue-watching row
- Quality mode with an OpenGL ES renderer or an experimental Vulkan renderer, with automatic fallback to OpenGL ES (**beta**)
- Best configuration per format chosen before opening: Auto renderer mode detects format (MediaExtractor, <50 ms) and selects the measured best decoder and renderer from a data table keyed by the Vulkan driver build (**beta**)
- Anime4K and Snapdragon GSR shader presets (**beta**)
- Efficient mode for battery

### Format and renderer matrix

Auto renderer mode selects the measured optimal configuration per format before opening from a data table keyed by the Vulkan driver build hash:

| Format | Renderer | Video Output (`vo`) | Decoder | Notes |
|---|---|---|---|---|
| **H.264 (8-bit)** | Vulkan | `gpu-next` | MediaCodec copy-back | Lowest power draw; falls back to OpenGL ES `gpu` copy-back |
| **HEVC (8/10-bit)** | OpenGL ES | `gpu` | MediaCodec zero-copy | Optimal efficiency and lowest battery consumption |
| **VP9** | OpenGL ES | `gpu` | MediaCodec zero-copy | Optimal efficiency and 0 dropped frames |
| **AV1** | Vulkan | `gpu` | Software (`dav1d`) | Smoothest multi-core software decode; falls back to OpenGL ES `gpu` |
| **H.264 (10-bit / Hi10P)** | Vulkan | `gpu` | Software | No hardware decoder on chipset; falls back to OpenGL ES `gpu` |

**Fallback chain**: If Vulkan is unavailable or fails at runtime, playback automatically falls back to OpenGL ES. If hardware decoding fails on OpenGL ES, playback falls back to software decoding, preserving current playback position with an on-screen notice.

## Requirements and compatibility

Android 17 on an arm64-v8a device (minSdk is 37). Tested on one phone only. See
[docs/HARDWARE.md](docs/HARDWARE.md) for the reference device, what might work elsewhere and what will not.

## Download

Releases and pre-built APKs will be made available on the [GitHub Releases](https://github.com/ravarage/vibeplayer/releases) page.

## Install

Download the APK from the [GitHub Releases](https://github.com/ravarage/vibeplayer/releases) page (**v0.5.0**) and install it. It works only on Android 17, arm64.
The APK is signed with the developer's debug key, so it updates in place only over builds signed with the
same key; builds from source signed with your own key must be uninstalled first (this deletes app data).

## Building from source

See [docs/BUILDING.md](docs/BUILDING.md): the native libraries are built from pinned sources with
`native/fetch.sh` and `native/build.sh`, the app with `./gradlew :app:assembleRelease`.

## How it was developed

A human owner directed AI coding agents. Claude acted as architect and reviewer (plus helper agents); Google
Antigravity CLI (agy, Gemini) and OpenAI Codex were the implementing agents. They worked from
[SPEC.md](SPEC.md), [ARCHITECTURE.md](ARCHITECTURE.md) and [TASKS.md](TASKS.md) with one commit per task, rules in
[AGENT_RULES.md](AGENT_RULES.md), and a progress log in [PROGRESS.md](PROGRESS.md) and
[BLOCKERS.md](BLOCKERS.md). Changes were verified on the phone with screenshot-based checks
(`tools/check_video.py`) and a regression suite (`tools/devcheck`). The planning files are part of the repository.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md): renderer benchmark and default choice, performance work, shader and
tuned-build benchmarks, and the known H.264 start stall (B-08).

## License

Copyright (C) 2026 Ravyar Tahir ([ravarage](https://ravarage.xyz/)).

GPL-3.0-or-later; see [LICENSE](LICENSE). The app is GPL because FFmpeg is built with GPL options
(`--enable-gpl --enable-version3`), which makes the bundled libmpv binaries GPL. Third-party components and
how to get the corresponding source are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Contact

Ravyar Tahir ([ravarage on GitHub](https://github.com/ravarage), [ravarage.xyz](https://ravarage.xyz/)): ravyar.tahir@ravarage.xyz

## Acknowledgements

[mpv](https://mpv.io), [mpv-android](https://github.com/mpv-android/mpv-android), [FFmpeg](https://ffmpeg.org),
[dav1d](https://code.videolan.org/videolan/dav1d), [libass](https://github.com/libass/libass),
[libplacebo](https://code.videolan.org/videolan/libplacebo), [Anime4K](https://github.com/bloc97/Anime4K),
Qualcomm Snapdragon Game Super Resolution (mpv port by agyild). MX Player was the inspiration for the touch
interface; Vibe Player is not affiliated with or endorsed by MX Player or any other project named here.
