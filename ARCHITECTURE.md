# ARCHITECTURE

Anything marked **candidate** comes from general mpv/Android knowledge and is not yet checked against
this project's build. The task named next to it verifies it and replaces the text with the result.

## Repository layout
```
native/
  fetch.sh            clone mpv-android at the pinned commit into native/mpv-android (gitignored)
  build.sh            wrap the buildscripts: arm64 only, flags, copy .so into player/src/main/jniLibs
  patches/            patches applied to buildscripts (FFmpeg configure trim, flags)
app/                  :app    Compose UI, navigation, data layer, service
player/               :player Kotlin engine API + JNI bridge (C) + prebuilt .so (gitignored)
baselineprofile/      :baselineprofile (added in P7)
docs/BENCHMARK.md     results from P6/P8/P9
```
Application id and base package: `com.vibeplayer`.

## Packages
```
:player  com.vibeplayer.player
  MpvNative        JNI declarations only (thin, 1:1 with libmpv client API)
  PlayerEngine     the only class the app talks to; owns the mpv handle
  PlayerState      immutable state + separate position flow
  PlaybackMode     Efficient | Quality, Renderer: Vulkan | Gles, option tables
:app     com.vibeplayer
  ui.browser       folder list, video list, thumbnails
  ui.player        player screen, controls, gestures, pickers, debug overlay
  ui.settings      settings screen
  data.media       MediaStore repository
  data.db          Room database, DAOs, entities
  data.thumbs      thumbnail generator + disk cache
  data.prefs       DataStore settings
  playback         PlaybackService (P5), MediaSession, PiP helpers
```

## JNI bridge
- Own small C file `player/src/main/cpp/mpv_jni.c` built with CMake, linking the prebuilt `libmpv.so`.
  Modelled on mpv-android's `MPVLib`, but handle-based rather than a global singleton so a second
  instance can generate thumbnails.
- Functions: `create`, `setOptionString` (pre-init), `initialize`, `command(String[])`,
  `setProperty*`, `getProperty*`, `observeProperty(name, format)`, `attachSurface(Surface)`,
  `detachSurface`, `destroy`.
- `JNI_OnLoad` passes the JavaVM to FFmpeg (`av_jni_set_java_vm`), required for MediaCodec.
- Surface: a JNI global ref to the `Surface` is passed to mpv through the `wid` option; on detach set
  `vo`/`wid` back so mpv releases it before the ref is deleted. Exact sequence verified in P1-03.
- One native **event thread** per handle loops on `mpv_wait_event` and calls two Kotlin callbacks:
  `onPropertyChange(name, value)` and `onEvent(id)`. No other native-to-Kotlin calls.
- Keep rules for these callbacks and `MpvNative` go in the consumer ProGuard file (needed by P7).

## Activities
- `MainActivity`: browser only (`setContent { BrowserRoot() }`).
- `ui.player.PlayerActivity`: the player. Own activity so PiP, orientation and immersive mode stay
  isolated from the browser. Receives `ACTION_VIEW` for `video/*` and an explicit intent from the
  browser. `configChanges` handles rotation and size changes without recreating the engine.

## Threading model
| Thread | Does | Must never |
|---|---|---|
| Main | Compose, surface callbacks, collects state | call blocking mpv functions (`getProperty`, sync commands). Only exception: `surfaceDestroyed` waits for the detach to finish, at most 1.5 s (`PlayerEngine.detachSurface`); on timeout the detach stays queued on the command thread |
| Player command thread (single-thread dispatcher in `PlayerEngine`) | all `command`/`setProperty` calls, init, destroy | touch UI |
| mpv event thread (native) | reads events, pushes values into `MutableStateFlow`s | call back into mpv synchronously |
| IO dispatcher | Room, MediaStore, thumbnail disk cache | |
| Thumbnail worker (limited to 2 parallel) | frame extraction | use the playback mpv handle |

## Player state to Compose
- `PlayerEngine.state: StateFlow<PlayerState>` for values that change rarely: paused, duration,
  tracks, selected track ids, video size, speed, `hwdecCurrent`, buffering, error, end of file.
- `PlayerEngine.position: StateFlow<Long>` (ms) is separate, so only the seek bar and time label
  recompose on ticks. Position observation is paused while controls are hidden.
- `PlayerEngine.stats: StateFlow<Stats>` (drop counts, fps) is only observed while the debug overlay
  is open.
- UI sends intents through `PlayerEngine` methods (`play`, `pause`, `seekTo`, `setTrack`, ...). The
  UI never reads mpv properties directly; state is driven by `observeProperty` only.
- Ownership: P1 to P4 a `PlayerViewModel` owns the engine. From P5 the `PlaybackService` owns it and
  the ViewModel binds to the service; the UI contract (the three flows) does not change.

## PiP, Background Audio, and Lifecycle Decisions (P5)
- **Engine & Service Ownership**: From P5, `PlaybackService` is a foreground service (`mediaPlayback`) that owns the `PlayerEngine`, media session, notification, playlist tracking, and position persistence. `PlayerViewModel` binds to the service and proxies flows/actions. When only the browser is open or playback stops and no clients are bound, the service stops itself and releases the engine (no engine alive when idle).
- **PiP vs Background Audio Decision**:
  - **Home gesture / App switch while playing**: Enters Picture-in-Picture automatically via `autoEnterEnabled(!paused && hasDuration)`. The video shrinks into a floating window and continues playing video and audio.
  - **Back gesture / Screen off (leaving player)**:
    - **Background Audio ON**: Playback continues uninterrupted in `PlaybackService`. When the activity stops and the surface is destroyed, `VideoSurface.surfaceDestroyed` calls `PlayerEngine.detachSurface()`, setting mpv property `vo=null` and `wid=0`. Video frame decoding and GPU rendering stop completely (0 GPU overhead), while audio keeps playing via `ao=audiotrack`. The foreground media notification and MediaSession remain active. When the user returns to the player, `surfaceCreated` calls `PlayerEngine.attachSurface()`, re-enabling `vo=gpu` and `vid=auto`, which instantly restores picture without re-seeking or buffering.
    - **Background Audio OFF**: The player pauses on `onStop()`. Unbinding the activity leaves `boundClients == 0`, prompting `PlaybackService` to stop itself, cancel the notification, and release the engine.
  - **PiP Dismissal / Close**: When the user closes the PiP window (via the 'X' button or flinging it away), Android stops the activity while in PiP (`onStop` with `isInPictureInPictureMode == true`). The activity explicitly pauses playback on PiP close. Because playback is paused and unbinds, `PlaybackService` stops itself even if background audio is enabled.
- **Audio Focus & Becoming Noisy**:
  - `PlaybackService` manages `AudioManager` audio focus requests (`AUDIOFOCUS_GAIN`) with media usage and movie content type.
  - Focus loss permanent (`AUDIOFOCUS_LOSS`): pauses playback immediately and drops focus.
  - Focus loss transient (`AUDIOFOCUS_LOSS_TRANSIENT` / `CAN_DUCK`): pauses playback and flags `resumeOnFocusGain = true`. When focus is regained (`AUDIOFOCUS_GAIN`), playback resumes automatically. User-initiated pause/play clears the transient resume flag.
  - Becoming noisy (`ACTION_AUDIO_BECOMING_NOISY`): receiver registered while actively playing pauses playback when headphones or Bluetooth audio disconnects.
- **Task Removal (`onTaskRemoved`)**:
  - Swiping the app away from recent tasks calls `Service.onTaskRemoved`.
  - If background audio is enabled and playback is actively playing, the foreground service and notification persist so the user can continue listening.
  - If background audio is disabled, or if playback is paused / reached EOF, the service saves position snapshot, dismisses the notification, and stops itself to avoid zombie processes.

## Playback modes (candidate until P0-09)
Common options: `ao=audiotrack`, `vd-lavc-threads=0` (auto; P1-07 confirms dav1d uses all 8 cores),
`hwdec-codecs=h264,hevc,vp9`. AV1 always decodes in software with dav1d.

| | Efficient, path A | Efficient, path B | Quality |
|---|---|---|---|
| vo | `mediacodec_embed` | `gpu` | `gpu-next` |
| context | none (decoder renders to the surface) | `gpu-context=android` (GLES) | Vulkan: `gpu-api=vulkan`, `gpu-context=androidvk`; GLES: `gpu-api=opengl`, `gpu-context=android` |
| hwdec | `mediacodec` | `mediacodec` (zero-copy interop) | `mediacodec-copy` |
| scaling | by the system compositor | cheapest: `profile=fast`, no deband, no dither | default or shader presets (P8) |
| shaders | never | never | optional (P8) |

- **Efficient mode candidate paths evaluated (P1-06, P1-10).**
  - Path A (`mediacodec_embed`): lowest overhead for plain hardware streams, but cannot render libass subtitles or OSD, and cannot display software fallback frames (e.g. Hi10P).
  - Path B (`gpu` on GLES): zero-copy interop (`aimagereader` / `mediacodec`) verified on Snapdragon 778G with 0 dropped frames; correctly renders styled ASS subtitles (embedded fonts, positioning, karaoke) and supports seamless in-place software fallback.
- **Default is Path B** (confirmed by P1-06 and P1-10 benchmark results in docs/BENCHMARK.md; ASS subtitles and software fallback are hard requirements).
- **Software fallback**: mpv falls back to software when hwdec init or decoding fails
  (`hwdec-current` becomes `no`) when the vo is `gpu`/`gpu-next` (**candidate**, P1-07). On path A the
  engine must detect the failure and `reconfigure()` onto path B options at the same position.
  The engine exposes the fallback in `PlayerState`.
- **Mode or renderer switch**: `vo`, `gpu-api` and `gpu-context` cannot change on a live output.
  `PlayerEngine.reconfigure()` saves position and tracks, destroys and recreates the handle with the
  new option table, reloads the file and seeks.

## Vulkan status update (2026-10-13)
**User decision 2026-10-14 (supersedes the GLES-default notes below; implemented in P6-07): one phone, so the configuration is chosen BEFORE opening from a per-format table of what was measured best on it (Vulkan where it measured best and is available); fallback chain Vulkan -> OpenGL ES -> software only as a safety net. See TASKS.md P6-07.**

### Per-format best configuration table (P6-07, data since P6-06)
The application targets one phone, OS, and GPU (Snapdragon 778G / Adreno 642L), so rather than guessing at runtime, it identifies the video format cheaply before opening and selects the configuration measured as best.

The table is data, not code: `player/src/main/assets/format_table.json`, generated by `tools/bench/make_table.py` from the benchmark results (the "Power comparison" section of docs/BENCHMARK.md or the runner's `benchmark.csv`) plus `tools/bench/table_notes.json` (rule tolerances, clip -> format, exclusions, rows for formats the benchmark does not cover). It holds one table per Vulkan driver hash (first 16 hex digits of sha256 of `vulkan.adreno.so`) and a `default` table (a copy of the newest measurement) for a driver that matches none. `FormatTableLoader` (app) installs it on a background thread at activity/service start; the driver is hashed once per driver build (`Build.FINGERPRINT` + mtime) and the hash kept in preferences. Constants in `FormatProfileTable` are only the fallback before the install. A new driver build: re-run the benchmark, `make_table.py`, rebuild; no code change.

Rule (user, 2026-10-14): smoothest first (steady drops within 5% of the fewest count as equal, so HEVC's 1241 vs 1207 is a tie and AV1's 385 vs 362 is not), then lowest battery current (within 2% equal), then lowest CPU; ties go to lower power. A row whose renderer is Vulkan also carries a `gles` block, the same rule over the OpenGL ES rows, used when Vulkan is unavailable or the user picks OpenGL ES; `gpu-next` is never carried over to GLES unmeasured.

Shipped table (driver `c0b92d25178c8b21`, P6-08, unplugged, 60 s per row):

| Format | Decode (`hwdec`) | Renderer | `vo` | Without Vulkan | Why (measured) |
|---|---|---|---|---|---|
| H.264 8-bit | copy-back (`mediacodec-copy`) | Vulkan | `gpu-next` | GLES `gpu`, copy-back | 0 drops at 549 mA (GLES zero-copy 637 mA, GLES copy-back 720 mA); zero-copy excluded for start stalls (B-08) |
| H.264 10-bit (Hi10P) | software (`no`) | Vulkan | `gpu` | GLES `gpu`, software | No hardware decoder on the 778G (not re-measured) |
| HEVC 8/10-bit | zero-copy (`mediacodec`) | OpenGL ES | `gpu` | (same) | 1241 drops vs 1207 on Vulkan (equal within 5%) at 604 mA vs 1254 mA, 29% vs 117% CPU |
| VP9 | zero-copy (`mediacodec`) | OpenGL ES | `gpu` | (same) | 0 drops at 339 mA vs 794-1026 mA |
| AV1 (any) | dav1d software, all cores (`no`) | Vulkan | `gpu` | GLES `gpu`, software | Fewest drops: 362 vs 385 (gpu-next) and 918 (GLES) |
| other | software (`no`) | OpenGL ES | `gpu` | (same) | Safe default (not measured) |

Zero-copy needs OpenGL ES (mpv's `hwdec_aimagereader` refuses a non-GL renderer), so a zero-copy row forced onto Vulkan (renderer setting Vulkan) uses copy-back. The P6-08 section does not record the runner mode; copy-back rows most likely ran with the runner's default `mode=quality` (no `profile=fast`). Efficient mode keeps `profile=fast`, so the H.264 Vulkan row in efficient mode is that configuration plus `profile=fast` (cheaper; to be confirmed by the device pass).

- **Quality mode override**: Overrides the renderer column with `gpu-next` on Vulkan (else GLES) and copy-back for hardware formats; the table only drives efficient mode.
- **Fast format detection**: `VideoFormatDetector` uses Android `MediaExtractor` off the main thread in parallel with engine initialization (`Dispatchers.IO`), extracting MIME, profile, and bit depth under ~5-15 ms (well within the 50 ms budget). Falls back to `other` on error or timeout.
- **Vulkan driver availability**: Probed and cached per driver build (`Build.FINGERPRINT` + `vulkan.adreno.so` mtime; never the 1.1 feature flag). Reset on driver update.
- **Safety net chain**: Vulkan -> OpenGL ES -> software (`hwdec=no`) at the exact same position with one short on-screen message (`_onScreenMessage`), `VULKAN-FAIL` / `GLES-FAIL` log lines, and remembered format failures on this driver build so subsequent opens of that format immediately avoid Vulkan.
- **Renderer setting**: `Auto (default)` / `Vulkan` / `OpenGL ES`. One-time DataStore migration moves old default `OPENGL` users to `AUTO`.
- **Debug overlay**: Displays the chosen row description (`ROW: ...`, ending in `[table <driver hash or default>]`) and selection reason (`REASON: ...`).
- **Debug intent extras**: `force_vulkan_failure=true` and `force_gles_failure=true` test the safety chain.

**User note 2026-10-13: the Vulkan 1.3 driver on the phone is the user's own port and is not finished.** Keep Vulkan support in the app and the Vulkan-enabled libraries as the default build, but treat the Vulkan renderer as experimental: OpenGL ES stays the default renderer for every mode unless the P6 benchmark and a stability run show Vulkan is at least as good, the automatic fallback to GLES is mandatory, and a Vulkan failure (init error, device lost, corruption, crash in `vulkan.adreno.so`) is to be reported to the user with the log lines, since it is useful for their driver work, not treated as an app bug to work around silently. The driver may change between sessions: re-read its version (`cmd gpu vkjson`) at the start of any P6 task and record it next to every benchmark number.

**Decision (reviewer, 2026-10-13): Vulkan works on the new driver (P6-00b), so the default libraries in `player/src/main/jniLibs` are now the Vulkan-enabled build** (`native/build.sh` defaults to `VULKAN=1`; `VULKAN=0` gives a GLES-only variant in `native/out/gles`, `TUNED=1` gives `native/out/tuned`). Playback options always name `gpu-api` and `gpu-context` explicitly, so GLES modes are unaffected. Which renderer is the default is decided by the P6 benchmark.

The phone's Adreno 642L Vulkan 1.3 driver was re-probed with P6-00b on 2026-10-13. `VULKAN=1
native/build.sh` built mpv v0.41.0/libplacebo v7.360.1 with Vulkan and shaderc at `-O2`; all seven
arm64 libraries total 103,236,584 bytes (98.46 MiB), including a 15,504,848-byte `libmpv.so`.

Both Vulkan outputs initialized and rendered H.264 hardware decode and AV1 software decode:

```
[vo/gpu-next/vulkan] Initializing GPU context 'androidvk'
[vo/gpu-next/libplacebo] GPU 0: Adreno (TM) 642L v1.3.295 (integrated)
[vo/gpu-next/libplacebo] Device Name: Adreno (TM) 642L
[vo/gpu-next/libplacebo] API version: 1.3.295
[vo/gpu-next/libplacebo] Driver name: Qualcomm Technologies Inc. Adreno Vulkan Driver
[vo/gpu-next/libplacebo] Initialized SPIR-V compiler 'shaderc'
[cplayer] VO: [gpu-next] 3840x2160 yuv420p10
[vd] Selected decoder: libdav1d - dav1d AV1 decoder by VideoLAN
[VP] onPropertyChange: hwdec-current = no
```

`vo=gpu` emitted the equivalent `[vo/gpu/vulkan] Initializing GPU context 'androidvk'`, the same
Adreno/API/driver values, and `[cplayer] VO: [gpu] 1920x1080 nv12`. Its H.264 run reported
`hwdec-current = mediacodec-copy`.

The OpenGL control initialized `[vo/gpu-next/opengl]` with `gpu-context=android`; its log reports
`GL_VERSION='OpenGL ES 3.2 V@0530.57 ...'`. All six 20-second media/configuration runs passed
`tools/check_video.py` with a moving picture. Frame drops at the 20-second stats read were:

| `vo` / API / context | H.264 `ichiban.mp4` (`hwdec-current`; drops / decoder drops) | AV1 `av1_4k.mp4` (`hwdec-current`; drops / decoder drops) |
|---|---|---|
| `gpu-next` / Vulkan / `androidvk` | `mediacodec-copy`; 7 / 0 | `no` (`libdav1d`); 517 / 0 |
| `gpu` / Vulkan / `androidvk` | `mediacodec-copy`; 0 / 0 | `no` (`libdav1d`); 359 / 0 |
| `gpu-next` / OpenGL ES / `android` (control) | `mediacodec-copy`; 0 / 0 | `no` (`libdav1d`); 565 / 0 |

`tools/check_video.py` reported `VIDEO VISIBLE` for all six: `gpu-next`/Vulkan H.264 80.0% non-black,
79.7% changed; AV1 64.6% / 51.8%; `gpu`/Vulkan H.264 82.3% / 67.4%, AV1 61.3% / 56.4%; GLES
control H.264 55.4% / 4.8%, AV1 81.8% / 80.6%.

Saved AV1 screenshots (`/tmp/P6-00b_gpu-next-vulkan_av1.png`,
`/tmp/P6-00b_gpu-vulkan_av1.png`, `/tmp/P6-00b_gpu-next-opengl_av1.png`) showed a moving picture with
no clear tearing or black frames. Vulkan AV1 screenshots contain large purple title artwork; it
appears to be part of the clip, but the runs resumed at different positions, so they are not
frame-matched comparisons. Other apps were concurrently using the device and their PiP windows or
controls appear in some captures. The GLES AV1 clip reached EOF after its 20-second sample and
autoplayed another AV1 file; only after that, libplacebo logged `Failed generating/uploading block
offsets LUT!` while opening the next file. No GPU-context initialization failure was seen during the
timed samples.

Keep the system Vulkan feature flag caveat: `android.hardware.vulkan.version` still reports 1.1.0,
so **never gate on `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`**. Select Vulkan by initializing
the context (cache per driver build) and fall back to GLES if it fails. The driver can change with a
system update, so GLES remains a required fallback. The older section below records the previous
1.1 probe.

## Vulkan status (probe 2026-10-12, task P6-00)
**Decision (reviewer, 2026-10-12): Vulkan is not usable on this device; every mode uses OpenGL ES.** The default native build does not include Vulkan: the patch lives in `native/patches/optional/0004-vulkan-shaderc.patch` and `fetch.sh` does not apply it (it doubles libmpv.so to 15 MB). In the default build `gpu-context` offers only `android` and `gpu-api` only `opengl`; ignore `androidvk`/`vulkan` in the options table below. P6 compares `gpu-next` vs `gpu` on GLES.

Vulkan support was enabled in the native build (libplacebo v7.360.1 + shaderc 2022.3 from NDK r30 + mpv v0.41.0 via patch `0004-vulkan-shaderc.patch`). The built `libmpv` successfully exposes `gpu-context=auto,android,androidvk` and `gpu-api=auto,opengl,vulkan`.

During device probe on redwood (Adreno 642L), `vo=gpu-next` with `gpu-api=vulkan`, `gpu-context=androidvk` failed to initialize because libplacebo requires Vulkan 1.2 minimum, whereas the device driver provides Vulkan 1.1.128:
```
[vo/gpu-next/vulkan] Initializing GPU context 'androidvk'
[vo/gpu-next/libplacebo] Initialized libplacebo v7.360.1 (v7.360.1) (API v360)
[vo/gpu-next/libplacebo] Creating vulkan instance with extensions:
...
[vo/gpu-next/libplacebo] Probing for vulkan devices:
[vo/gpu-next/libplacebo]     GPU 0: Adreno (TM) 642L v1.1.128 (integrated)
[vo/gpu-next/libplacebo]            uuid: 43:51:00:00:05:00:00:00:7B:02:50:00:0A:00:7B:02
[vo/gpu-next/libplacebo] Found no suitable device, giving up.
[vo/gpu-next/libplacebo] Failed initializing vulkan device
[vo/gpu-next] Failed initializing any suitable GPU context!
[cplayer] Error opening/initializing the selected video_out (--vo) device.
```

**Root cause**: In `libplacebo` (`src/include/libplacebo/vulkan.h:27` and `src/vulkan/context.c:1071`), `#define PL_VK_MIN_VERSION VK_API_VERSION_1_2`. During physical device enumeration, devices where `apiVersion < PL_VK_MIN_VERSION` are rejected with `PL_DEBUG(vk, "      -> excluding due to too low API version")`, resulting in `pl_vulkan_choose_device` returning NULL.

**Re-scoping**: Per TASKS.md P6-00, since Vulkan cannot initialize on this device due to the Vulkan 1.1 hardware/driver limitation, tasks P6-02, P6-04, and P6-05 are re-scoped to compare `vo=gpu-next` vs `vo=gpu` on OpenGL ES.
OpenGL ES playback was verified after the rebuild:
- `vo=gpu` (`gpu-api=opengl`, `gpu-context=android`, `hwdec=mediacodec`): functional and smooth.
- `vo=gpu-next` (`gpu-api=opengl`, `gpu-context=android`, `hwdec=mediacodec-copy`): functional and smooth.

**Future Vulkan (user note 2026-10-12):** the phone may later get a driver with Vulkan 1.2 or 1.3. The renderer choice stays in the design: at runtime Vulkan is offered only if the device reports 1.2+ and libmpv was built with it (`VULKAN=1 native/build.sh`). Until then nothing may depend on Vulkan.

## Surface handling (required sequence, from mpv-android)
- Attach: set `wid` to the surface ref, then set property `vo` to the mode's vo (and `force-window=yes`).
- Detach: set property `vo=null` and `force-window=no` **first**, wait for it to take effect, then set
  `wid=0` and delete the global ref. The P0-10 code deletes the ref while the vo may still be drawing;
  P1-03 must fix this.

## Confirmed mpv options
_Filled by P0-09 and updated by P6-00 from the built libmpv._

| Option | Confirmed values / choices | Notes |
|---|---|---|
| `vo` | `gpu-next`, `gpu`, `mediacodec_embed`, `libmpv`, `null`, `image`, `tct`, `kitty`, `lavc` | Primary output is `gpu` or `gpu-next` |
| `hwdec` | `mediacodec`, `mediacodec-copy`, `auto`, `auto-safe`, `auto-copy`, `no` | Confirmed `mediacodec` and `mediacodec-copy` accepted |
| `gpu-context` | `auto`, `android`, `androidvk` | `android` for GLES EGL window context; `androidvk` built & confirmed (requires Vulkan >= 1.2) |
| `gpu-api` | `auto`, `opengl`, `vulkan` | GLES 3.2 on Adreno 642L; Vulkan built & confirmed (requires Vulkan >= 1.2) |
| `ao` | `audiotrack`, `aaudio`, `opensles`, `null`, `pcm`, `lavc` | Native Android audio outputs |

## Decoders and demuxers
_Filled by P0-09 from the built libmpv (`decoder-list`, `demuxer-lavf-list`, `encoder-list`)._

- **Decoders (507 total)**:
  - AV1: `libdav1d` (software via dav1d 1.5.4), `av1_mediacodec` (hardware)
  - Hardware: MediaCodec video decoders (`h264_mediacodec`, `hevc_mediacodec`, `av1_mediacodec`, `vp9_mediacodec`, `vp8_mediacodec`, `mpeg2_mediacodec`, `mpeg4_mediacodec`), audio decoders (`aac_mediacodec`, `mp3_mediacodec`, `amrnb_mediacodec`, `amrwb_mediacodec`)
  - Software video: `h264`, `hevc`, `vp9`, `av1`, `mpeg4`, `mpeg2video`, `vc1`, etc.
  - Software audio: `ac3`, `eac3`, `dca` (DTS), `truehd`, `aac`, `flac`, `opus`, `vorbis`, `mp3`, etc.
  - Subtitles: handled via libass / internal mpv subtitle demuxers.
- **Demuxers (367 total)**:
  - Broad demuxer coverage: `matroska`/`webm`, `mov`/`mp4`/`m4a`/`3gp`, `mpegts`, `avi`, `flv`, `hls`, `dash`, `aac`, `ac3`, `flac`, `mp3`, `ogg`, `srt`, `ass`, `vobsub`, etc.
- **Encoders & Muxers**:
  - `encoder-list`: `[]` (0 encoders)
  - FFmpeg muxers: disabled (0 muxers)

## Data layer
- **MediaStore** (`data.media`): query `MediaStore.Video.Media` (id, data path, bucket id/name,
  duration, size, date modified, resolution). Folders are buckets. A `ContentObserver` refreshes.
- **Room** (`data.db`), file key = `path + size`:
  - `FileSettings(key, positionMs, durationMs, audioTrackId, subTrackId, subEnabled, speed, aspect, lastPlayedAt)`
  - History is `FileSettings` ordered by `lastPlayedAt`; no separate table.
- **DataStore** (`data.prefs`): playback mode, renderer, sort order, gesture toggles, shader preset.
- **Thumbnails** (`data.thumbs`): `MediaMetadataRetriever` first; if it fails, a second mpv handle
  (`vo=null`, `ao=null`) with `screenshot-raw`. Encoded with `Bitmap.compress` (FFmpeg has no
  encoders). Disk cache key = `path + size + mtime`, plus an in-memory LRU.

## Native build
- mpv-android buildscripts download their own SDK (Android SDK cmdline-tools 11076708, platform 36, build-tools 36.0.0), NDK (r30 / 30.0.16248370) and all dependency sources into `native/mpv-android/buildscripts/{sdk,deps}` (confirmed by P0-04). The Gradle build uses that same SDK through `local.properties`.
- Select the native build variant with environment variables:
  - `native/build.sh`: default `-O2`, no Vulkan, outputs to `player/src/main/jniLibs/arm64-v8a`.
  - `VULKAN=1 native/build.sh`: `-O2` plus the optional Vulkan/shaderc patch, outputs to `native/out/vulkan/arm64-v8a`.
  - `TUNED=1 native/build.sh`: `-O2 -mcpu=cortex-a78 -flto=thin`, outputs to `native/out/tuned/arm64-v8a`.
  - `TUNED=1 VULKAN=1 native/build.sh`: tuned flags plus Vulkan, outputs to `native/out/tuned-vulkan/arm64-v8a`.
- Every invocation cleans the shared dependency build directories and prefix before building, so objects and archives from another flag or Vulkan selection are not reused. Variant outputs are separate; `native/out/` and `player/src/main/jniLibs/` are gitignored.
- Native builds are arm64-v8a only. `fetch.sh` applies the default build-variant patch to a fresh buildscripts checkout; Vulkan remains opt-in.

## Versions
See the Versions table in PROGRESS.md and the dependency policy in AGENT_RULES.md.

## Device facts
_Filled by P0-02._

| Fact | Value |
|---|---|
| `ro.product.device` | redwood |
| `ro.build.version.sdk` | 37 (Android 17) |
| Vulkan version / features | 1.1.128 (apiVersion 4198528 / 0x401080, Adreno 642L), level 1, compute, samplerYcbcrConversion |
| GLES version | OpenGL ES 3.2 V@0530.57 (ro.opengles.version 196610, Adreno 642L) |
