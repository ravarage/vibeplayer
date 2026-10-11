# PROGRESS

Last reviewed commit: 65dd73a

Statuses: TODO, IN PROGRESS, DONE, BLOCKED. Notes: one or two lines; for IN PROGRESS always state the next step.

## Versions
Latest stable, looked up from official sources during P0 (AGENT_RULES.md, Dependency policy). Fill before use.

| Component | Version used | Source checked | Notes (e.g. fell back from X) |
|---|---|---|---|
| Android Studio / JetBrains Runtime (build JDK) | 25.0.3 (build 25.0.3+-15898627-b508.16) | /home/rava/.local/share/JetBrains/Toolbox/apps/android-studio/jbr | Android Studio 2026.2.1 |
| compileSdk / targetSdk | 37 / 37 | platforms;android-37.1 / build-tools 37.0.0 | Matches redwood device Android 17 (SDK 37) |
| Android Gradle Plugin | 9.4.1 | https://dl.google.com/dl/android/maven2 | latest stable AGP release |
| Gradle | 9.8.1 | https://services.gradle.org/distributions/ | latest stable Gradle release |
| Kotlin | 2.4.21 | https://repo1.maven.org/maven2 | latest stable release (built-in AGP 9.4.1) |
| Compose BOM | 2026.09.00 | https://dl.google.com/dl/android/maven2 | latest stable BOM |
| Room | 2.8.5 | https://dl.google.com/dl/android/maven2 | latest stable release |
| Media / MediaSession | 1.11.1 | https://dl.google.com/dl/android/maven2 | latest stable Media3 session |
| Activity | 1.13.0 | https://dl.google.com/dl/android/maven2 | latest stable activity-compose |
| Lifecycle | 2.11.0 | https://dl.google.com/dl/android/maven2 | latest stable lifecycle-runtime-compose |
| Navigation | 2.10.2 | https://dl.google.com/dl/android/maven2 | latest stable navigation-compose |
| DataStore | 1.2.1 | https://dl.google.com/dl/android/maven2 | latest stable DataStore Preferences |
| Baseline Profile / Macrobenchmark | 1.5.0 | https://dl.google.com/dl/android/maven2 | latest stable benchmark-macro |
| NDK | r30 (30.0.16248370) | https://github.com/android/ndk/releases | latest stable release |
| CMake | 4.4.4 | https://github.com/Kitware/CMake/tags | latest stable release (matches host) |
| mpv-android (commit) | c2e02fe111541e4063ab8f868bf629649ed937a3 | https://github.com/mpv-android/mpv-android.git | pinned HEAD from 2026-10-06 |
| mpv | v0.41.0 | https://github.com/mpv-player/mpv.git | latest stable release |
| FFmpeg | n9.0.2 | https://github.com/FFmpeg/FFmpeg.git | latest stable release |
| dav1d | 1.5.4 | https://code.videolan.org/videolan/dav1d.git | latest stable release |
| libass | 0.17.5 | https://github.com/libass/libass.git | latest stable release |
| libplacebo | v7.360.1 | https://github.com/haasn/libplacebo.git | latest stable release |
| freetype | 2.14.3 | https://gitlab.freedesktop.org/freetype/freetype.git | tag VER-2-14-3, latest stable |
| harfbuzz | 14.6.0 | https://github.com/harfbuzz/harfbuzz.git | latest stable release |
| fribidi | 1.0.17 | https://github.com/fribidi/fribidi.git | tag v1.0.17, latest stable |

## Tasks
| Task | Title | Status | Agent | Commit | Notes |
|---|---|---|---|---|---|
| P0-01 | Toolchain check | DONE | AGY-1 | 075cf3d | Host tools recorded (Linux 7.2.9, OpenJDK 27, Gradle 9.8.1, git 2.56.0, CMake 4.4.4, Meson 1.12.1, Ninja 1.13.2, NASM 3.02, Python 3.14.7, adb 1.0.41, 16 cores); JBR 25.0.3 configured in ~/.gradle/gradle.properties |
| P0-02 | Device check | DONE | AGY-1 | d53c9b7 | redwood (SDK 37, Vulkan 1.1, GLES 3.2); clips probed and mapped, missing clips recorded in non-blocking B-02 |
| P0-03 | Fetch mpv-android | DONE | AGY-1 | 9307be7 | mpv-android pinned at c2e02fe; mpv v0.41.0, FFmpeg n9.0.2, dav1d 1.5.4, libass 0.17.5, libplacebo v7.360.1, harfbuzz 14.6.0 pinned in buildscripts via patch |
| P0-04 | Download SDK, NDK, deps | DONE | AGY-1 | e1e935b | SDK platform 36/build-tools 36.0.0, NDK r30 (30.0.16248370), CMake 4.4.4 recorded; all deps downloaded at pinned versions |
| P0-05 | FFmpeg trim | DONE | AGY-1 | 341c081 | Patch 0002 trims FFmpeg (0 encoders/muxers, no avdevice, libdav1d enabled); mpv built without avdevice; patches apply cleanly on fresh fetch |
| P0-06 | Build native for arm64 | DONE | AGY-1 | a7aca47 | arm64-only native build at -O2 verified (readelf AArch64); 7 .so files copied to player/src/main/jniLibs/arm64-v8a; no libavdevice; build ~43s, incremental no-op ~16s |
| P0-07 | Gradle skeleton | DONE | AGY-1 | b7a7086 | Gradle 9.8.1, AGP 9.4.1, compile/targetSdk 37, minSdk 37; :app and :player with version catalog; installed and ran blank Compose screen on redwood |
| P0-08 | JNI bridge minimal | DONE | AGY-1 | 866a54c | JNI bridge (mpv_jni.c, CMakeLists.txt, MpvNative.kt); JavaVM passed to FFmpeg in JNI_OnLoad; logcat shows mpv v0.41.0; no crash on exit |
| P0-09 | Verify mpv options and codecs | DONE | AGY-1 | f4853de | Confirmed options: vo (gpu, gpu-next), hwdec (mediacodec, mediacodec-copy), ao (aaudio, audiotrack, opensles); 507 decoders (libdav1d, mediacodec, broad FFmpeg); 367 demuxers; 0 encoders/muxers |
| P0-10 | Play one file | DONE | AGY-1 | b8b3cba | MainActivity with SurfaceView attached to libmpv wid plays ichiban.mp4; hwdec=mediacodec, ao=audiotrack confirmed in log; video and audio play to end (eof reached); pause toggle works; clean exit |
| P1-01 | Event thread and state | DONE | AGY-1 | 1f6cf15 | Event thread calling onPropertyChange/onEvent JNI callbacks; PlayerEngine exposes state (PlayerState) and position StateFlows; verified on redwood with pause/duration/position flow updates |
| P1-02 | PlayerEngine API | DONE | AGY-1 | 749ce1a | load, play, pause, seekTo, release executed on VP-Command thread; thread asserts and StrictMode verified; 100x load/seek/release loop verified on device without crash or leak |
| P1-03 | PlayerActivity and surface lifecycle | DONE | AGY-2 | 97054a8 | Moved playback to PlayerActivity; implemented proper vo null/detach/attach sequence with surface size; verified 10 rotations and 10 home/return cycles without crash or black screen |
| P1-04 | Player screen | IN PROGRESS | AGY-2 | pending | Implementing immersive fullscreen, auto-hiding controls (3s), seek bar, time labels, play/pause, title/back, time-pos 100ms throttle in C, and unobserve when hidden |
| P1-05 | Scrubbing | TODO | | | |
| P1-06 | Efficient mode candidates | TODO | | | |
| P1-07 | Software fallback and dav1d threads | TODO | | | |
| P1-08 | Debug overlay | TODO | | | |
| P1-09 | Open-with intent | TODO | | | |
| P1-10 | ASS subtitles on both efficient paths | TODO | | | |
| P2-01 | MediaStore repository | DONE | AGY-1B | pending | READ_MEDIA_VIDEO flow and MediaRepository query implemented; 9 folders and counts logged matching device gallery |
| P2-02 | Folder list screen | DONE | AGY-1B | pending | FolderListScreen implemented with LazyColumn, folder icon, name, count; verified on redwood showing all 9 folders including vibeplayer-test |
| P2-03 | Video list screen | DONE | AGY-1B | pending | VideoListScreen implemented with title, duration, size, resolution; tap calls onPlayVideo with VP log/Toast (player acceptance skipped per instructions); back returns to folder list |
| P2-04 | Navigation and refresh | DONE | AGY-1B | pending | BrowserNavGraph in ui/Nav.kt with folders->videos->player routes; ContentObserver auto-refresh verified; DataStore sort order (name/date/size) verified surviving restart |
| P2-05 | Thumbnail generator | DONE | AGY-1B | pending | FrameExtractor interface & MediaMetadataRetriever extractor implemented (max 2 parallel, cancellable); on device 6 clips extracted, 5 got no thumbnail (av1_4k, hevc10_4k, bocchi, bad girl, clevatess) awaiting mpv fallback |
| P2-06 | Thumbnail cache | DONE | AGY-1B | pending | ThumbnailCache with disk cache (path+size+mtime WebP) and memory LRU; tested on redwood: instant disk cache HIT on second launch with no extraction, cache miss/regeneration verified on replacing file |
| P2-07 | Launch player from browser | DONE | AGY-1B | pending | onPlayVideo starts explicit ACTION_VIEW intent for PlayerActivity with file Uri & video/*; missing activity caught and Toast displayed; verified on redwood |
| P2-08 | Thumbnail robustness | DONE | AGY-2B | a5b09df | MMR failures investigated (null on 10-bit HEVC & 10-bit/4K AV1; 8-bit AV1 & 8-bit H264/VP8/VP9 succeed); versioned (.failed) negative cache; non-blocking sends in ContentObserver; display-size decode; 0 extractions on 2nd launch |
| P2-09 | Settings screen scaffold | DONE | AGY-2B | a2f1767 | SettingsScreen scaffold via gear icon in browser top bar; DataStore AppSettings (mode, renderer, gestures, skip seconds, background audio, shaders); values persist across app restart |
| P3-01 | Gesture layer | TODO | | | |
| P3-02 | Brightness | TODO | | | |
| P3-03 | Volume | TODO | | | |
| P3-04 | Swipe seek | TODO | | | |
| P3-05 | Double tap skip | TODO | | | |
| P3-06 | Pinch zoom | TODO | | | |
| P3-07 | Lock | TODO | | | |
| P3-08 | Haptics | TODO | | | |
| P4-01 | Room setup | DONE | AGY-1B | pending | AppDatabase, FileSettings entity and DAO configured with Room 2.8.5; insert/read round-trip verified on redwood; schema exported |
| P4-02 | Resume position | TODO | | | |
| P4-03 | Audio track picker | TODO | | | |
| P4-04 | Subtitle picker | TODO | | | |
| P4-05 | Speed | TODO | | | |
| P4-06 | Aspect ratio | TODO | | | |
| P4-07 | Persist per-file choices | TODO | | | |
| P4-08 | History in browser | IN PROGRESS | AGY-2B | df3500c | Code complete (Continue watching row, progress bars from Room). Next step: after P4-06 writes real history, verify on device and mark DONE. Reviewer removed the fake history seeding from BrowserRoot. |
| P5-01 | PlaybackService | TODO | | | |
| P5-02 | MediaSession and notification | TODO | | | |
| P5-03 | Background audio | TODO | | | |
| P5-04 | Picture-in-picture | TODO | | | |
| P5-05 | Lifecycle edge cases | TODO | | | |
| P6-00 | Vulkan-enabled native build | TODO | | | |
| P6-01 | Quality mode | TODO | | | |
| P6-02 | Renderer switch | TODO | | | |
| P6-03 | Benchmark harness | TODO | | | |
| P6-04 | Benchmark: short matrix | TODO | | | |
| P6-05 | Benchmark: stability | TODO | | | |
| P6-06 | Default renderer | TODO | | | |
| P7-01 | Release build | TODO | | | |
| P7-02 | Start time | TODO | | | |
| P7-03 | Recomposition | TODO | | | |
| P7-04 | Baseline Profile | TODO | | | |
| P7-05 | Browser smoothness | TODO | | | |
| P7-06 | Idle cost | TODO | | | |
| P8-01 | Shader plumbing | TODO | | | |
| P8-02 | Anime4K light presets | TODO | | | |
| P8-03 | Snapdragon GSR | TODO | | | |
| P8-04 | Shader benchmark | TODO | | | |
| P9-01 | Tuned build | TODO | | | |
| P9-02 | Tuned vs baseline | TODO | | | |
