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
| Main | Compose, surface callbacks, collects state | call blocking mpv functions (`getProperty`, sync commands). Only exception: `surfaceDestroyed` waits for the detach to finish |
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
- Native code is rebuilt only by tasks that touch `native/`. Outputs are gitignored and shared by all
  agents because they work on the same machine.
- Development flags `-O2`. P9 adds `-mcpu=cortex-a78 -flto=thin` behind `TUNED=1 native/build.sh`.

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
