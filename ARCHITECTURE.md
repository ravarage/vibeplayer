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

## Threading model
| Thread | Does | Must never |
|---|---|---|
| Main | Compose, surface callbacks, collects state | call blocking mpv functions (`getProperty`, sync commands) |
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

- **Efficient mode has two candidate paths; neither is chosen yet.** Path A is the most direct and
  probably the lowest power, but is believed unable to draw libass subtitles or OSD, and software
  fallback would need a re-init onto another vo. Path B keeps frames on the GPU without a copy and is
  believed to support subtitles and in-place software fallback, at some GPU cost. All of this is
  unverified, including whether path B's zero-copy interop works on this device.
- P1-06 measures power and drops for both and P1-10 checks ASS rendering on both. The agent then
  writes the numbers and the trade-off into BLOCKERS.md and **the user picks**; agents do not choose.
- **Software fallback**: mpv falls back to software when hwdec init or decoding fails
  (`hwdec-current` becomes `no`) when the vo is `gpu`/`gpu-next` (**candidate**, P1-07). On path A the
  engine must detect the failure and `reconfigure()` onto path B options at the same position.
  The engine exposes the fallback in `PlayerState`.
- **Mode or renderer switch**: `vo`, `gpu-api` and `gpu-context` cannot change on a live output.
  `PlayerEngine.reconfigure()` saves position and tracks, destroys and recreates the handle with the
  new option table, reloads the file and seeks.

## Confirmed mpv options
_Filled by P0-09 from the built libmpv (`vo`, `hwdec`, `gpu-context`, `gpu-api`, `ao` help lists)._

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
