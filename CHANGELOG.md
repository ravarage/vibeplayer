# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] - 2026-10-15

Features marked **(beta)** are merged but not yet fully verified on the physical device.

### Added

#### Video Rendering and Formats
- **Format profile table & Auto renderer mode**: Chooses the measured optimal decoder and renderer before opening based on video format and Vulkan driver build hash (H.264 -> Vulkan `gpu-next` copy-back, HEVC -> OpenGL ES zero-copy, VP9 -> OpenGL ES zero-copy, AV1 -> Vulkan `gpu` software dav1d) (beta).
- **Vulkan renderer**: Experimental Vulkan video output (`vo=gpu-next` / `vo=gpu`) with automatic fallback to OpenGL ES on failure (beta).
- **Safety fallback chain**: Automatic runtime fallback chain (Vulkan -> OpenGL ES -> software decoding) preserving playback position, with on-screen alerts and failure logging (beta).
- **Quality and Efficient modes**: In-player and settings toggle between Quality mode (Vulkan/`gpu-next`, copy-back) and Efficient mode (OpenGL ES, zero-copy) (beta).
- **Shader presets**: GLSL shader support featuring Anime4K v4.0.1 (Fast Modes A, B, C) and Qualcomm Snapdragon Game Super Resolution (Snapdragon GSR v1) spatial upscaler (beta).

#### Playback Engine & Background
- **PlaybackService**: Background foreground service architecture keeping playback state alive across activity lifecycles and orientation changes (beta).
- **MediaSession & Notification**: MediaStyle playback notification with play/pause, rewind (-10s), fast-forward (+10s), previous, and next controls (beta).
- **Background audio playback**: Seamless background audio on screen-off or app minimization with audio focus ducking (lowering to 30% on transient loss) and becoming-noisy unplug pause (beta).
- **Picture-in-picture (PiP)**: Android PiP mini player with aspect-ratio clamping, auto-enter on backgrounding, and seamless auto-advance to next episode while in PiP.
- **Playlist navigation**: Auto-play next file in folder upon completion, and previous/next media buttons.
- **Hold for fast playback**: Long-press on the right half of the screen for temporary fast playback (2x or configurable speed) (beta).

#### Subtitles & Audio
- **Subtitle & audio delay sync**: Sync adjustment sheet (-/+ 50 ms step controls) persisted per file (Room schema migration 3 -> 4) (beta).
- **Global subtitle appearance**: Settings for subtitle font size (`sub-scale`), vertical position (`sub-pos`), and ASS style override toggle (beta).
- **Folder track memory & default languages**: Remembers preferred audio/subtitle track choices per folder and supports user default language preferences (Room schema migration 2 -> 3).
- **Per-file state persistence**: Persists audio/sub track selections, playback speed, aspect ratio mode, and sync delays across sessions.

#### Browser & Media Library
- **Continue Watching row**: Horizontal shelf on the browser home screen displaying recent in-progress videos with progress bars, formatted time labels, and history key deduplication.
- **Software thumbnail extraction**: libmpv-backed software frame extraction for 10-bit HEVC, AV1, and clips where Android MediaMetadataRetriever fails, with dark-frame detection and multi-offset retry.

#### Performance & Optimization
- **Baseline Profile module**: Macrobenchmark-based baseline profiles for faster cold start and smoother UI interactions.
- **Release build configuration**: Optimized R8 minification, resource shrinking, and ProGuard keep rules for libmpv JNI callbacks and native bindings.
- **Demuxer cache & memory budgeting**: Bounded mpv read-ahead cache (32 MB forward / 16 MB backward) and fixed 16 MB thumbnail LRU cache with Android `onTrimMemory` handling.
- **Browser smoothness**: Optimized Jetpack Compose recomposition and list scrolling performance (0% janky frames on 900+ items list).

### Fixed
- **B-08 decoder start stall recovery**: Stall watchdog monitors first frame rendering; automatically aborts stuck decoder handles and recovers via software decoding on a fresh surface without ANR.
- **ANR and thread safety**: Bounded main-thread surface detach wait (1.5s max) to prevent ANR during engine tear-down or codec reinitialization.
- **Accurate resume position**: Bounded command-thread position polling ensuring saved resume positions do not become stale when on-screen controls auto-hide.
- **Thumbnail temp file cleanup**: Isolated unique temporary file naming and cleanup during frame extraction.
- **Sheet display in landscape**: Ensure track selection and settings bottom sheets open fully expanded in landscape orientation.

### Known Issues
- **B-08 start stall on H.264 zero-copy**: H.264 zero-copy decoding occasionally stalls on initial frame display; automatically detected and recovered by the stall watchdog without user intervention.
- **Vulkan experimental with fallback**: Vulkan requires driver support (Vulkan 1.2+); on devices or drivers where Vulkan fails, the app automatically falls back to OpenGL ES.
- **HEVC 10-bit 4K drops under investigation**: High frame drop rates (~1,200 drops/min) observed during 4K 10-bit HEVC playback benchmarks are under active investigation code-side.

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
