# PROGRESS

Last reviewed commit: a7ac63a

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
| shaderc | 2022.3 | Android NDK r30 (sources/third_party/shaderc) | Built via ndk-build libshaderc_combined for arm64-v8a at -O2 |

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
| P1-03 | PlayerActivity and surface lifecycle | DONE | AGY-2 | 1acb186 | Black screen fixed (vo=null pre-init, vid=auto on attach); blocking detach & real wait (bounded 500ms); tools/check_video.py verified (start: non_black=24.6% changed=3.9%, after 20 rotations & 20 home/return & power: non_black=25.3% changed=25.2%); 0 EGL/BufferQueue errors |
| P1-04 | Player screen | DONE | AGY-2 | 3ef1f1b | PlayerControls with play/pause, seek bar, time labels, title, back, 3s auto-hide, tap toggle, immersive fullscreen; time-pos throttled 100ms in C & unobserved when hidden; tools/check_video.py verified (non_black=23.3% changed=20.3%) |
| P1-05 | Scrubbing | DONE | AGY-2 | 14792b8 | VideoSeekBar with thumb-following position label, 50ms throttled keyframe scrubbing, exact seek on release; verified on hevc10_4k.mp4 on redwood with tools/check_video.py (non_black=21.8% changed=26.5%), exact release within 3ms |
| P1-06 | Efficient mode candidates | DONE | AGY-2 | 45998ba | Option tables in PlaybackMode (Path A mediacodec_embed vs Path B gpu); hwdec is mediacodec variant for H.264, HEVC, VP9 on both (0 drops); numbers in docs/BENCHMARK.md; Path B default; power not measured (charging) |
| P1-07 | Software fallback and dav1d threads | DONE | AGY-2 | a3812dd | Exposed fallback in PlayerState when codec in hwdec-codecs and hwdec=no; verified AV1 1080p plays with hwdec=no and fallback=false (check_video.py: non_black=25.3% changed=23.6%); av1_4k top -H shows 9 busy dav1d-worker threads across cores (check_video.py: non_black=19.1% changed=13.3%); forced hwdec=no plays (check_video.py: non_black=25.3% changed=25.2%) with fallback=true badge |
| P1-08 | Debug overlay | DONE | AGY-2 | f667fab | DebugOverlay with vo, hwdec, mode, resolution, fps, frame drops, decoder drops; toggled via long press on title; stats polled and observed only while overlay is open; verified values update live on redwood (check_video.py: non_black=19.5% changed=24.9%), closed overlay stops stats with 0 logcat entries |
| P1-09 | Open-with intent | DONE | AGY-2 | f833c63 | Handled ACTION_VIEW for video/* with file:// and content:// (via fdclose://<fd>) URIs; displayName extracted for title; auto-orientation follows video aspect (landscape 2400x1080); AGENT_RULES.md play command verified (check_video.py: non_black=80.0% changed=79.6%); content URI verified (check_video.py: non_black=68.2% changed=71.6%) |
| P1-10 | ASS subtitles on both efficient paths | DONE | AGY-2 | 7bb2e71 | Evaluated styled ASS subtitles on Bocchi clip (Hi10P 10-bit H.264) on Path A (mediacodec_embed) vs Path B (gpu); Path B renders embedded fonts, multi-line positions, and in-place software fallback (check_video.py: non_black=78.9% changed=72.3%); Path A cannot render ASS/OSD and black screens on Hi10P (check_video.py: non_black=0.0%); Path B confirmed default; documented in docs/BENCHMARK.md and ARCHITECTURE.md |
| P2-01 | MediaStore repository | DONE | AGY-1B | pending | READ_MEDIA_VIDEO flow and MediaRepository query implemented; 9 folders and counts logged matching device gallery |
| P2-02 | Folder list screen | DONE | AGY-1B | pending | FolderListScreen implemented with LazyColumn, folder icon, name, count; verified on redwood showing all 9 folders including vibeplayer-test |
| P2-03 | Video list screen | DONE | AGY-1B | pending | VideoListScreen implemented with title, duration, size, resolution; tap calls onPlayVideo with VP log/Toast (player acceptance skipped per instructions); back returns to folder list |
| P2-04 | Navigation and refresh | DONE | AGY-1B | pending | BrowserNavGraph in ui/Nav.kt with folders->videos->player routes; ContentObserver auto-refresh verified; DataStore sort order (name/date/size) verified surviving restart |
| P2-05 | Thumbnail generator | DONE | AGY-1B | pending | FrameExtractor interface & MediaMetadataRetriever extractor implemented (max 2 parallel, cancellable); on device 6 clips extracted, 5 got no thumbnail (av1_4k, hevc10_4k, bocchi, bad girl, clevatess) awaiting mpv fallback |
| P2-06 | Thumbnail cache | DONE | AGY-1B | pending | ThumbnailCache with disk cache (path+size+mtime WebP) and memory LRU; tested on redwood: instant disk cache HIT on second launch with no extraction, cache miss/regeneration verified on replacing file |
| P2-07 | Launch player from browser | DONE | AGY-1B | pending | onPlayVideo starts explicit ACTION_VIEW intent for PlayerActivity with file Uri & video/*; missing activity caught and Toast displayed; verified on redwood |
| P2-08 | Thumbnail robustness | DONE | AGY-2B | a5b09df | MMR failures investigated (null on 10-bit HEVC & 10-bit/4K AV1; 8-bit AV1 & 8-bit H264/VP8/VP9 succeed); versioned (.failed) negative cache; non-blocking sends in ContentObserver; display-size decode; 0 extractions on 2nd launch |
| P2-09 | Settings screen scaffold | DONE | AGY-2B | a2f1767 | SettingsScreen scaffold via gear icon in browser top bar; DataStore AppSettings (mode, renderer, gestures, skip seconds, background audio, shaders); values persist across app restart |
| P2-10 | Thumbnails for every video (software fallback) | TODO | | | Low priority, required: after P5, before P7 |
| P3-01 | Gesture layer | DONE | AGY-2 | 00f96d9 | GestureLayer with zones (left/right/horizontal), slop direction lock, single-tap toggle; controls clickable; check_video.py verified (non_black=80.0% changed=41.6%) |
| P3-02 | Brightness | DONE | AGY-2 | b9edb4d | Left vertical swipe adjusts window brightness; BrightnessIndicator overlay with 1s fade-out verified; full swipe covers min-max; reset on exit verified; check_video.py verified (non_black=80.0% changed=76.1%) |
| P3-03 | Volume | DONE | AGY-2 | bfe95aa | Right vertical swipe sets STREAM_MUSIC; VolumeIndicator overlay; tracks hardware volume keys both ways; media volume restored to 0; check_video.py verified (non_black=78.5% changed=76.5%) |
| P3-04 | Swipe seek | DONE | AGY-2 | bb6dcd4 | Horizontal swipe seeks with +/-delta / target position pill overlay; keyframe preview during drag and exact seek on release; verified playing and paused on device (check_video.py: non_black=79.8% playing, 77.5% still) |
| P3-05 | Double tap skip | DONE | AGY-2 | b012e76 | Double tap left/right skips +/-10s with ripple and pill indicator; repeated taps accumulate (+20s, +30s); single tap not delayed noticeably (200ms); verified on device (check_video.py: non_black=79.6% changed=70.1%) |
| P3-06 | Pinch zoom | DONE | AGY-2 | 83cc601 | Pinch sets mpv video-zoom, two-finger drag pans (video-pan-x/y); ResetZoomButton and pinch-out resets to fit; verified on 4K (hevc10_4k.mp4, check_video.py: non_black=75.4% zoomed, restored fit verified by screencap & check_video.py: changed=64.8%) |
| P3-07 | Lock | DONE | AGY-2 | a49171e | Lock button in controls disables all gestures, controls, and back; full-screen touch interceptor blocks touches; floating unlock pill restores unlocked state; verified on device (check_video.py: non_black=75.6% changed=66.5%, screenshots inspected) |
| P3-08 | Haptics | DONE | AGY-2 | ff152bf | Haptic tick on skip, swipe-seek steps, and seek bar ends (0% & 100%); none while locked; calls verified in logcat; needs user to feel it; video playback confirmed (check_video.py: non_black=69.9% changed=77.0%) |
| P4-01 | Room setup | DONE | AGY-1B | pending | AppDatabase, FileSettings entity and DAO configured with Room 2.8.5; insert/read round-trip verified on redwood; schema exported |
| P4-02 | Resume position | DONE | AGY-2 | pending | Position saved on pause, 10s periodic, onStop/onCleared (500ms bounded); currentPositionMs reads command thread; relative seek on swipe/double-tap; position flow isolated to seekbar & time-label; 40s hidden controls resume verified at 66s within 0s; swipe seek lands at old+delta; check_video.py non_black=80.0% |
| P4-03 | Audio track picker | DONE | AGY-2 | 1182cf1 | AudioTrackSheet bottom sheet with language, codec, channels; verified switching on multi_track.mkv (AC3 5.1, E-AC3 5.1, DTS 5.1 all playing); check_video.py verified (non_black=61.6% changed=54.0%); screencaps inspected |
| P4-04 | Subtitle picker | DONE | AGY-2 | d1ff25b | SubtitleTrackSheet bottom sheet with Off option and external indicator; sub-auto=fuzzy loads external subtitles; verified ASS (Bocchi), PGS and SRT (multi_track.mkv), and external SRT; Off hides subtitles; check_video.py verified (non_black=60.7% changed=79.9%); screencaps inspected |
| P4-05 | Speed | DONE | AGY-2 | a7ac63a | Speed control 0.25x to 4x with pitch correction via SpeedSheet; audio-pitch-correction=yes enables scaletempo2 filter; topbar pill reflects speed; verified at 2x and 0.5x, video stays in sync; check_video.py verified (exit 0, non_black=72.5% changed=78.3%); volume verified 0 |
| P4-06 | Aspect ratio | TODO | | | Deferred after P4-10 per user priority order. Next step: implement aspect cycling (fit, crop, stretch, 16:9, 4:3) with label, verify on a 4:3 and a 21:9 source. |
| P4-07 | Persist per-file choices | DONE | AGY-2 | e91d042 | Per-file aid/sid/speed/aspect saved on change & exit; loadfile passes aid/sid/speed/start options with track fallback by lang+title; verified on multi_track.mkv (aid=2, sid=2) and Case Closed (aid=2 Japanese, sid=2 English Full Subtitles); Room migration 1->2 preserves history; check_video.py exit 0 (non_black=43.2% changed=59.1% on Case Closed, non_black=79.4% on ichiban) |
| P4-08 | History in browser | IN PROGRESS | AGY-2B | df3500c | Code complete (Continue watching row, progress bars from Room). Next step: after P4-06 writes real history, verify on device and mark DONE. Reviewer removed the fake history seeding from BrowserRoot. |
| P4-09 | Name, icon and version | DONE | AGY-2 | pending | App label 'Vibe Player' (launcher, recents, settings title, browser top bar); adaptive icon with background, safe-zone foreground, monochrome vector, and legacy PNG mipmaps from branding/icon.svg; rootProject.name = 'VibePlayer', versionName = 0.4.0, versionCode = 2; About section in Settings displaying 'Vibe Player 0.4.0' from BuildConfig; verified by launcher search and settings screenshots |
| P4-10 | Default languages and per-folder track memory | DONE | AGY-2 | pending | TrackSelector pure Kotlin logic with 5 unit tests passing; FolderSettings Room table with MIGRATION_2_3; DataStore language preferences (audio, sub, mode); SettingsScreen Languages UI; verified on Case Closed defaults (English audio + signs/songs subtitle); switching to Japanese audio + full subtitles on ep1 in test folder automatically restored Japanese audio + full subtitles on ep2 in same folder; picker sheet screencaps inspected; check_video.py exit 0 (non_black=40.3% changed=41.4%) |
| P5-01 | PlaybackService | TODO | | | |
| P5-02 | MediaSession and notification | TODO | | | |
| P5-03 | Background audio | TODO | | | |
| P5-04 | Picture-in-picture | DONE | AGY-2 | pending | Android PiP on PlayerActivity (supportsPictureInPicture, resizeableActivity); PipHelper with aspect ratio clamping [1:2.39..2.39:1], sourceRectHint, seamlessResize, autoEnter on playing; controls/gestures hidden in PiP; remote actions rewind 10s, play/pause, forward 10s; verified 16:9, 4:3, and 9:16 vertical clips on device over home and Settings; cropped pixel diff confirmed playing (92.3% on 16:9, 14.8% on 9:16); broadcast actions verified; return to fullscreen verified (check_video exit 0) |
| P5-05 | Lifecycle edge cases | TODO | | | |
| P6-00 | Vulkan-enabled native build | DONE | AGY-2C | 0624988 | Built libplacebo v7.360.1 + shaderc 2022.3 + mpv v0.41.0 with Vulkan; patch optional/0004 (not applied by default) captures buildscripts; options confirmed; probe showed device Vulkan 1.1 < required 1.2; GLES playback confirmed |
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
