# PROGRESS

Last reviewed commit: none

Statuses: TODO, IN PROGRESS, DONE, BLOCKED. Notes: one or two lines; for IN PROGRESS always state the next step.

## Versions
Latest stable, looked up from official sources during P0 (AGENT_RULES.md, Dependency policy). Fill before use.

| Component | Version used | Source checked | Notes (e.g. fell back from X) |
|---|---|---|---|
| Android Studio / JetBrains Runtime (build JDK) | | | not installed, see B-01 |
| compileSdk / targetSdk | | | |
| Android Gradle Plugin | | | |
| Gradle | | | |
| Kotlin | | | |
| Compose BOM | | | |
| Room | | | |
| Media / MediaSession | | | |
| Activity | | | |
| Lifecycle | | | |
| Navigation | | | |
| Baseline Profile / Macrobenchmark | | | |
| NDK | | | |
| CMake | | | |
| mpv-android (commit) | | | |
| mpv | | | |
| FFmpeg | | | |
| dav1d | | | |
| libass | | | |
| libplacebo | | | |
| freetype | | | |
| harfbuzz | | | |
| fribidi | | | |

## Tasks
| Task | Title | Status | Agent | Commit | Notes |
|---|---|---|---|---|---|
| P0-01 | Toolchain check | BLOCKED | AGY-1 | pending | Host tool versions recorded in short form (Linux 7.2.9, OpenJDK 27, Gradle 9.8.1, git 2.56.0, CMake 4.4.4, Meson 1.12.1, Ninja 1.13.2, NASM 3.02, Python 3.14.7, adb 1.0.41, 16 cores); blocked by B-01 |
| P0-02 | Device check | TODO | | | |
| P0-03 | Fetch mpv-android | TODO | | | |
| P0-04 | Download SDK, NDK, deps | TODO | | | |
| P0-05 | FFmpeg trim | TODO | | | |
| P0-06 | Build native for arm64 | TODO | | | |
| P0-07 | Gradle skeleton | TODO | | | |
| P0-08 | JNI bridge minimal | TODO | | | |
| P0-09 | Verify mpv options and codecs | TODO | | | |
| P0-10 | Play one file | TODO | | | |
| P1-01 | Event thread and state | TODO | | | |
| P1-02 | PlayerEngine API | TODO | | | |
| P1-03 | Surface lifecycle | TODO | | | |
| P1-04 | Player screen | TODO | | | |
| P1-05 | Scrubbing | TODO | | | |
| P1-06 | Efficient mode candidates | TODO | | | |
| P1-07 | Software fallback and dav1d threads | TODO | | | |
| P1-08 | Debug overlay | TODO | | | |
| P1-09 | Open-with intent | TODO | | | |
| P1-10 | ASS subtitles and efficient path decision | TODO | | | |
| P2-01 | MediaStore repository | TODO | | | |
| P2-02 | Folder list screen | TODO | | | |
| P2-03 | Video list screen | TODO | | | |
| P2-04 | Navigation and refresh | TODO | | | |
| P2-05 | Thumbnail generator | TODO | | | |
| P2-06 | Thumbnail cache | TODO | | | |
| P3-01 | Gesture layer | TODO | | | |
| P3-02 | Brightness | TODO | | | |
| P3-03 | Volume | TODO | | | |
| P3-04 | Swipe seek | TODO | | | |
| P3-05 | Double tap skip | TODO | | | |
| P3-06 | Pinch zoom | TODO | | | |
| P3-07 | Lock | TODO | | | |
| P3-08 | Haptics | TODO | | | |
| P4-01 | Room setup | TODO | | | |
| P4-02 | Resume position | TODO | | | |
| P4-03 | Audio track picker | TODO | | | |
| P4-04 | Subtitle picker | TODO | | | |
| P4-05 | Speed | TODO | | | |
| P4-06 | Aspect ratio | TODO | | | |
| P4-07 | Persist per-file choices | TODO | | | |
| P4-08 | History in browser | TODO | | | |
| P5-01 | PlaybackService | TODO | | | |
| P5-02 | MediaSession and notification | TODO | | | |
| P5-03 | Background audio | TODO | | | |
| P5-04 | Picture-in-picture | TODO | | | |
| P5-05 | Lifecycle edge cases | TODO | | | |
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
