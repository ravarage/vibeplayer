# BENCHMARK

Performance and power benchmarks on POCO X5 Pro 5G (redwood), Snapdragon 778G, Android 17.

## P1-06 Efficient Mode Candidates

Comparison of Path A (`mediacodec_embed`) vs Path B (`gpu` + `mediacodec`) on `ichiban.mp4` (H.264 1080p, 5 min playback).
Device status: Charging (AC powered: true), so battery current / power is recorded as "not measured (charging)" per instructions.

| Path | VO | hwdec-current | Duration | Output Drops (`frame-drop-count`) | Decoder Drops (`decoder-frame-drop-count`) | Power | Notes |
|---|---|---|---|---|---|---|---|
| Path A | `mediacodec_embed` | `h264_mediacodec` | 5 min | 0 | 0 | not measured (charging) | Direct Surface rendering via MediaCodec; no ASS subtitle or OSD overlay support |
| Path B (Default) | `gpu` (GLES) | `h264_mediacodec` | 5 min | 0 | 0 | not measured (charging) | Zero-copy MediaCodec to GPU interop; supports ASS subtitles and Compose overlays |

### Codec Coverage on Efficient Paths

| Clip | Codec / Resolution | Path A (`mediacodec_embed`) | Path B (`gpu`) | Result |
|---|---|---|---|---|
| `ichiban.mp4` | H.264 1080p | `h264_mediacodec` (hwdec) | `h264_mediacodec` (hwdec) | Both working, 0 drops |
| `hevc10_4k.mp4` | HEVC 10-bit 4K | `hevc_mediacodec` (hwdec) | `hevc_mediacodec` (hwdec) | Both working, 0 drops |
| `vp9_1080p.webm` | VP9 1080p | `vp9_mediacodec` (hwdec) | `vp9_mediacodec` (hwdec) | Both working, 0 drops |

Path B is the default per ARCHITECTURE.md.

## P1-10 ASS Subtitles and Fallback on Efficient Paths

> Reviewer correction (2026-10-12): the Bocchi clip is **AV1 10-bit**, decoded by dav1d (logcat shows `libdav1d`, `yuv420p10`), not Hi10P H.264. So the "software fallback" rows below describe ordinary AV1 software decoding, not a hardware-to-software fallback; karaoke rendering was not checked. Power for P1-06 is still unmeasured (phone was charging) and must be redone on battery.

Tested on `[denisplay] Bocchi the Rock! - S01E01.mkv` (Hi10P 10-bit H.264 1080p with styled ASS subtitles, embedded fonts, dialogue positioning and karaoke):

| Path | VO | Video Output | ASS Subtitle Rendering (Fonts / Positions / Karaoke) | Software Fallback (Hi10P) | Result |
|---|---|---|---|---|---|
| Path A | `mediacodec_embed` | Black screen (check_video.py: non_black=0.0%) | Not supported (no libass OSD compositor) | Fails (mediacodec_embed cannot display SW frames) | Fails on styled ASS & Hi10P |
| Path B (Default) | `gpu` (GLES) | Visible (check_video.py: non_black=78.9% changed=72.3%) | Supported & verified (embedded fonts, top/bottom positioning, outline styling) | Works seamlessly via FFmpeg software fallback (check_video.py: non_black=80.0% changed=78.3%) | **PASS** |

### Findings:
1. **ASS Subtitle Rendering**: Path B (`vo=gpu`) successfully composites styled ASS subtitles via libass + FriBidi + HarfBuzz, properly rendering embedded fonts, styling, outlines, and multi-line top/bottom dialog positions (verified by screenshot inspection). Path A (`mediacodec_embed`) outputs video frames directly from MediaCodec to the Surface, completely bypassing mpv's OSD/libass pipeline, so subtitles cannot be rendered.
2. **Software Fallback**: The Bocchi clip is 10-bit H.264 (`yuv420p10`), which hardware decoders on Qualcomm Snapdragon 778G do not support. Path B performs seamless in-place software fallback to FFmpeg with `vo=gpu` displaying full video and subtitles. Path A fails with a black screen because `mediacodec_embed` cannot accept software-decoded frames.
3. **Conclusion**: Path B stays the default playback mode.


## P7-03 / P7-05 / P7-06 UI frame statistics (2026-10-13, CLAUDE-A)

Method: `adb shell dumpsys gfxinfo com.vibeplayer reset`, the interaction through `adb shell input swipe`,
then `dumpsys gfxinfo com.vibeplayer` ("Janky frames"). Phone on battery, Wi-Fi adb, commit `aa10b49`.
gfxinfo counts only the app's own UI frames (Compose); mpv draws the video into a SurfaceView, which is
not counted. The debug build is debuggable (no ahead-of-time compilation, mpv log level verbose), so
its numbers are a worst case; the release build (R8, `:app:assembleRelease`, installed in place over
the debug build and replaced by the debug build again afterwards) is the one that matters for users.

### Browser: fast scroll of "WhatsApp Video" (925 videos)

Flings of 1500 px in 90 ms, one every 0.45 s.

| Build | Run | Frames | Janky | 50th / 90th / 99th percentile |
|---|---|---|---|---|
| debug | first visit of the folder, 14 flings, thumbnails being extracted | 186 | 103 (55.38%) | 34 / 150 / 300 ms |
| debug | back up and down, 14 flings, thumbnails from disk | 707 | 292 (41.30%) | 19 / 38 / 57 ms |
| debug | same rows again, 14 flings, thumbnails in memory | 1114 | 14 (1.26%) | 6 / 8 / 13 ms |
| debug | rows never visited, 12 flings (process warm) | 407 | 33 (8.11%) | 8 / 18 / 32 ms |
| release | top of the folder, 12 flings, memory cache cold, thumbnails from disk | 1010 | 0 (0.00%) | 5 / 5 / 6 ms |
| release | rows not visited before, 14 flings | 900 | 0 (0.00%) | 5 / 5 / 7 ms |
| release | back up and down over visited rows, 16 flings | 1435 | 0 (0.00%) | 5 / 5 / 6 ms |
| release, portrait | thumbnail cache directory emptied first; first visit of the folder, 14 flings | 1178 | 6 (0.51%) | 5 / 8 / 14 ms |
| release, portrait | next 14 flings (new rows) | 398 | 1 (0.25%) | 5 / 5 / 9 ms |
| release, portrait | back up over the rows just visited, 14 flings | 1216 | 0 (0.00%) | 5 / 5 / 8 ms |
| release, portrait | thumbnails on disk, rows up and down, 16 flings | 1249 | 1 (0.08%) | 5 / 5 / 6 ms |

Other debug runs: folder list (9 folders) 102 frames, 4 janky (3.92%); "Music" (37 videos) first pass
159 frames, 14 janky (8.81%), second pass 162 frames, 6 janky (3.70%).

Notes: all debug janky frames were "Slow UI thread". A simpleperf sample of the debug build during a
scroll showed the main thread mostly in the JIT code cache and `libart.so` (interpreting / JIT-compiled
Compose code), which is what a debuggable build costs. The first three release rows were taken with the display in landscape (2400x1080; auto-rotate is
off and the user-rotation value read 1 at that time, 0 a few minutes later; not changed by the test), and
the thumbnails for those rows turned out to be on disk already. The four "release, portrait" rows
were added afterwards: for the first three of them `cache/thumbnails` was removed first, and 708
thumbnail files existed in the cache when the debug build was put back, so thumbnails were being
generated during those runs; a screenshot taken mid-scroll had picture content in every thumbnail
slot.

### Player (release build, `Case Closed - 001 - The Big Shrink.mkv`, AV1 software decode, Efficient path B)

| Run | Frames | Janky | 50th / 90th / 99th percentile |
|---|---|---|---|
| 60 s playback, controls hidden | 0 | 0 | no UI frame was drawn in 60 s |
| about 35 s of continuous scrubbing (14 seek-bar drags of 1.3 s) | 2152 | 24 (1.12%) | 7 / 10 / 13 ms |

`tools/check_video.py` reported VIDEO VISIBLE before and after both runs. With the controls hidden
the Compose UI did not draw a single frame, i.e. nothing recomposes into a visible change on position
ticks while idle. Not measured: recomposition counts per control with the controls visible (P7-03
acceptance), battery power (P7-06 acceptance; the P1-06 baseline has no power figure because the
phone was charging then), the same runs on the debug build.

## Benchmark Runner (P6-03)

### How to run

Direct adb broadcast:
```bash
adb shell am broadcast -a com.vibeplayer.BENCHMARK -n com.vibeplayer/.BenchmarkReceiver \
  [--es path <video_path>] \
  [--ei seconds 15] \
  [--es mode quality|efficient] \
  [--es renderer vulkan|gles] \
  [--es vo gpu-next|gpu] \
  [--es shader none|anime4k_a|anime4k_b|anime4k_c|snapdragon_gsr] \
  [--ez cold true|false] \
  [--es profile fast|default] \
  [--es decoder auto|software] \
  [--es driver_hash <16-hex>] [--es driver_mtime <mtime>] \
  [--es mpv_opts "'name=value;name=value'"]
```

`mpv_opts` (debug builds only, also accepted by `PlayerActivity` directly and by `run_matrix.py --mpv-opts`)
adds mpv options after the normal option table, for A/B tests; `;` separates entries, the first `=` splits
name and value, and only the names in `DebugMpvOptions.ALLOWED` (checked against the mpv 0.41 and FFmpeg
sources in `native/`) are applied, others are logged as rejected. The CSV `profile` column then reads
`<profile>+<options>` (commas shown as `|`). Through `adb shell` keep the inner single quotes: the remote
shell would otherwise split the list at `;`.

Output CSV files are written to app external storage:
- Summary: `/sdcard/Android/data/com.vibeplayer/files/benchmark.csv`
- Samples (1 Hz): `/sdcard/Android/data/com.vibeplayer/files/benchmark_samples.csv`

Host matrix runner (`tools/bench/run_matrix.py`):
```bash
# Short matrix (P6-04): 5 min each across {Vulkan, GLES} x {gpu-next, gpu} x {ichiban, hevc10_4k, av1_1080p}
python3 tools/bench/run_matrix.py --matrix short

# AV1 attribution matrix: 60 s each across 4 clips x {gpu, gpu-next, gpu-next+profile=fast} x {Vulkan, GLES}
python3 tools/bench/run_matrix.py --matrix av1_attribution

# Stability matrix (P6-05): 30 min hevc10_4k.mp4 per renderer
python3 tools/bench/run_matrix.py --matrix stability

# Shaders matrix (P8-04): 5 min per preset on 1080p clip
python3 tools/bench/run_matrix.py --matrix shaders
```

Flags:
- `--allow-charging`: allow running on AC/USB power (normally refused for power-sensitive matrices)
- `--dry-run`: display planned execution rows without device interaction
- Note on device permissions: If `/vendor/lib64/hw/vulkan.adreno.so` is not readable directly by the app on Android 17 due to SELinux, the host script automatically hashes it over adb and passes `--es driver_hash <hash> --es driver_mtime <mtime>`. Per-core CPU load from `/proc/stat` is restricted by SELinux on Android 17, so empty strings are written for that column.


## Memory (P7-07)

Measure with `tools/meminfo.py "<label>"` (read-only `adb shell dumpsys meminfo com.vibeplayer`, appends a
row to docs/meminfo.csv). A/B: debug build, PlayerActivity intent extra `legacy_cache=true` restores mpv's
old 150 MiB / 50 MiB demuxer limits. New values: forward 32 MiB, back 16 MiB (MemoryBudget.kt).

Before (reviewer, 2026-10-13, 1080p H.264 playing): TOTAL PSS 428 MB, native heap 253 MB, graphics 78 MB,
Dalvik 6 MB.

| State | TOTAL PSS before | TOTAL PSS after | Native heap | Graphics | Dalvik | Target |
|---|---|---|---|---|---|---|
| Browser idle after cold start (20 s) | not measured | 68 MB | 10 MB | 43 MB | | |
| 1080p H.264 playing 2 min (h264_1080p.mp4) | 428 MB | 216 MB | 125 MB (253 before) | 47 MB (78 before) | | <= 220 MB: met |
| 4K AV1 playing (~25 s; the clip is 30 s) | not measured | 708 MB | 594 MB | 76 MB | | (record only) |
| Mini player (PiP, 1080p H.264, 20 s) | not measured | 178 MB | 125 MB | 9 MB | | |
| Browser after closing the player (20 s / 60 s) | not measured | 110 MB / 108 MB | 26 MB | 41 MB | | <= 150 MB: met |

"After" = release build of main cb2abcf+ (CLAUDE-V2, 2026-10-15 04:44-04:48, phone charging, Efficient mode,
Renderer Auto: H.264 copy-back on Vulkan gpu-next, AV1 software on Vulkan gpu). Raw rows in docs/meminfo.csv.

A/B on the debug build (same file, 2 min from 5 s): new 32/16 MiB cache 260 MB PSS (native 121 MB) vs
`legacy_cache=true` (150/50 MiB) 419 MB (native 289 MB): the demuxer cache accounts for ~160 MB. The debug build
is ~45 MB above release. Not measured: double-tap seek-back latency (no VP-Perf mark after a seek; the -10 s
seek was issued in both runs without errors) and dropped frames across the A/B.

## B-08 stall recovery (2026-10-14, build 1cc3d61, default path, tools/stall_test.sh, ichiban.mp4, cold start each open)
| run | opens | stalls | recovered by watchdog | permanent black | ANR |
|---|---|---|---|---|---|
| P60 default path (engine in PlaybackService) | 60 | 5 (8.3 %) | 5 | 0 | 0 |
| A current libs GLES zero-copy | 40 | 12 | 12 | 0 | - |
| B old GLES-only libs | 40 | 6 | 6 | 0 | - |
| C Vulkan copy-back gpu / gpu-next | 40 / 40 | 1 / 4 | all | 0 | - |
| D GLES copy-back gpu / gpu-next | 40 / 40 | 0 / 0 | - | 0 | - |
| A_hevc / A_vp9 | 20 / 20 | 0 / 0 | - | 0 | - |

## P6-08 start-stall re-check (2026-10-15, CLAUDE-V2, driver c0b92d25178c8b21, debug build cb2abcf+, phone charging)

`OUT=/tmp/vp_stall_night tools/stall_test.sh <label> 20 ichiban.mp4 [probe extras]`, force-stop before each open, stall = no
playback-restart within 8 s. Every stall was recovered by the B-08 watchdog (6.3-9.8 s), none stayed black.

| configuration | extras | opens | stalls (recovered) |
|---|---|---|---|
| table row (Efficient, Auto): mediacodec-copy, Vulkan gpu-next | none | 20 | 1 (1) |
| GLES zero-copy gpu | probe_vo gpu, probe_renderer opengl, hwdec mediacodec | 20 | 4 (4) |
| GLES copy-back gpu | probe_vo gpu, probe_renderer opengl, hwdec mediacodec-copy | 20 | 1 (1) |
| Vulkan gpu copy-back | probe_vo gpu, probe_renderer vulkan, hwdec mediacodec-copy | 20 | 0 |

Plus the P7-02 release runs: 1 stall in 10 opens of h264_1080p.mp4 and 0 in 10 of ichiban.mp4 on the table row.
Conclusion: zero-copy still stalls most (20 %, as in B-08); the copy-back rows are 0-5 % at 20 opens each, too
few to rank them against each other. The table's H.264 choice (copy-back) stands.

## Power comparison (2026-10-14, unplugged, driver c0b92d25178c8b21)

Measured on POCO X5 Pro 5G (redwood), Snapdragon 778G, Adreno 642L, Android 17.
Unplugged on battery (`dumpsys battery` status 3, 51% -> 43%).
Vulkan driver hash: `c0b92d25178c8b21` (`/vendor/lib64/hw/vulkan.adreno.so`).
Screen on, brightness and media volume unchanged, phone untouched throughout runs.
Order interleaved (GLES, Vulkan, GLES, Vulkan...) with 10 s rest between rows to balance thermal drift.
Current sampled at 1 Hz via `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (steady average excludes first 5 s).
Steady dropped frames: delta in `frame-drop-count` after first 2 s. Decoder drops: delta in `decoder-frame-drop-count` after first 2 s.

| Clip | Format | Configuration | VO | Renderer | HWDEC | Steady Current (mA) | Steady Drops | Decoder Drops | CPU % | Temp Start/End (°C) |
|---|---|---|---|---|---|---|---|---|---|---|
| `ichiban.mp4` | H.264 1080p | GLES zero-copy | `gpu` | GLES | `mediacodec` | 637.0 | 0 | 0 | 28.1% | 32.0 → 32.0 |
| `ichiban.mp4` | H.264 1080p | Vulkan copy-back | `gpu` | Vulkan | `mediacodec-copy` | 768.2 | 0 | 0 | 38.6% | 32.0 → 32.0 |
| `ichiban.mp4` | H.264 1080p | GLES copy-back | `gpu` | GLES | `mediacodec-copy` | 720.4 | 0 | 0 | 34.3% | 32.0 → 32.0 |
| `ichiban.mp4` | H.264 1080p | Vulkan copy-back | `gpu-next` | Vulkan | `mediacodec-copy` | 549.0 | 0 | 0 | 36.3% | 35.0 → 35.0 |
| `hevc10_4k.mp4` | HEVC 10-bit 4K | GLES zero-copy | `gpu` | GLES | `mediacodec` | 604.5 | 1241 | 0 | 29.0% | 32.0 → 32.0 |
| `hevc10_4k.mp4` | HEVC 10-bit 4K | Vulkan copy-back | `gpu` | Vulkan | `mediacodec-copy` | 1254.3 | 1207 | 0 | 117.3% | 32.0 → 33.0 |
| `hevc10_4k.mp4` | HEVC 10-bit 4K | GLES copy-back | `gpu` | GLES | `mediacodec-copy` | 1023.8 | 1350 | 0 | 63.1% | 33.0 → 33.0 |
| `hevc10_4k.mp4` | HEVC 10-bit 4K | Vulkan copy-back | `gpu-next` | Vulkan | `mediacodec-copy` | 1202.0 | 1362 | 0 | 89.8% | 33.0 → 34.0 |
| `av1_4k.mp4` | AV1 4K | GLES software | `gpu` | GLES | `software (dav1d)` | 1668.0 | 918 | 0 | 592.0% | 34.0 → 35.0 |
| `av1_4k.mp4` | AV1 4K | Vulkan software | `gpu` | Vulkan | `software (dav1d)` | 1563.0 | 362 | 0 | 579.1% | 35.0 → 36.0 |
| `av1_4k.mp4` | AV1 4K | Vulkan software | `gpu-next` | Vulkan | `software (dav1d)` | 1340.0 | 385 | 0 | 576.7% | 36.0 → 37.0 |
| `vp9_1080p.webm` | VP9 1080p | GLES zero-copy | `gpu` | GLES | `mediacodec` | 338.6 | 0 | 0 | 40.4% | 37.0 → 37.0 |
| `vp9_1080p.webm` | VP9 1080p | Vulkan copy-back | `gpu` | Vulkan | `mediacodec-copy` | 912.6 | 0 | 0 | 92.7% | 37.0 → 37.0 |
| `vp9_1080p.webm` | VP9 1080p | GLES copy-back | `gpu` | GLES | `mediacodec-copy` | 794.2 | 0 | 0 | 57.1% | 37.0 → 37.0 |
| `vp9_1080p.webm` | VP9 1080p | Vulkan copy-back | `gpu-next` | Vulkan | `mediacodec-copy` | 1026.0 | 0 | 0 | 58.9% | 37.0 → 37.0 |
| `ichiban.mp4` (ctrl) | H.264 1080p | GLES zero-copy (repeat) | `gpu` | GLES | `mediacodec` | 643.2 | 0 | 0 | 30.3% | 37.0 → 37.0 |

### Conclusions (Rule: smoothest first, then lowest battery, then lowest CPU, ties to lower-power)
- **H.264 1080p (`ichiban.mp4`)**: All configurations deliver 0 steady drops. Vulkan `vo=gpu-next` achieves the lowest power draw (549.0 mA) beating GLES zero-copy (637.0 mA), while GLES zero-copy draws lowest CPU (28.1%). Winner: **Vulkan (`vo=gpu-next`)** on lowest battery.
- **HEVC 10-bit 4K (`hevc10_4k.mp4`)**: Vulkan `vo=gpu` has marginally fewer steady output drops (1207 vs 1241 on GLES zero-copy), but GLES zero-copy consumes less than half the battery power (604.5 mA vs 1254.3 mA) and a fraction of CPU load (29.0% vs 117.3%). Winner: **OpenGL ES zero-copy (`vo=gpu`)**.
- **AV1 4K (`av1_4k.mp4`, dav1d)**: Vulkan `vo=gpu` is smoothest with only 362 drops (vs 918 on GLES and 385 on gpu-next), and Vulkan `vo=gpu-next` has lowest battery power (1340.0 mA vs 1668.0 mA on GLES). Winner: **Vulkan (`vo=gpu`)** on smoothness (lowest drops).
- **VP9 1080p (`vp9_1080p.webm`)**: All configurations have 0 steady drops. OpenGL ES zero-copy draws drastically lower battery current (338.6 mA vs 794.2–1026.0 mA) and lowest CPU (40.4%). Winner: **OpenGL ES zero-copy (`vo=gpu`)**.
- **Control**: H.264 GLES zero-copy repeated at the end of the 20-minute suite measured 643.2 mA vs 637.0 mA at the start (a negligible +0.97% drift despite battery warming from 32°C to 37°C), confirming battery current stability across the session.

## HEVC 10-bit 4K drops: hypotheses

_Code-side investigation without the phone (2026-10-14, CLAUDE-N1). Nothing here changes a default; the
device pass has to confirm or reject each line._

**What is known.** `hevc10_4k.mp4` is HEVC Main10, 3840x2160, 29 s long (PROGRESS P4-11). Its frame rate is
not written anywhere in the repo (the runner records `container_fps` in `benchmark.csv` on the phone). P6-08
measured 1207-1362 steady drops in about 58 s on all four configurations, 0 decoder drops, while app CPU went
from 29% (GLES zero-copy) to 117% (Vulkan copy-back) and current from 605 to 1254 mA. The P6-08 runs were
60 s, so the runner looped the 29 s clip (`loop-file=inf`, two restarts). P1-06 had recorded "0 drops" for
this clip, but on the old last-stats-line method (possibly read after EOF, see the reviewer notes in
TASKS.md P6-03) and while charging. `av1_4k.mp4` (4K 10-bit 60 fps, TASKS.md P6-03 notes) also drops on every renderer;
`ichiban.mp4` (1080p 29.97 fps, ffprobe of the PC copy) and `vp9_1080p.webm` (fps not recorded) drop nothing.

Relevant facts from the sources (mpv 0.41.0, FFmpeg in `native/mpv-android/buildscripts/deps`):
- `framedrop=vo` is mpv's default: a frame that is late for its display time is dropped in the VO and
  counted in `frame-drop-count`, also when the decoder delivered it late. `decoder-frame-drop-count` counts
  only `framedrop=decoder`, so "0 decoder drops" does not clear the decoder.
- FFmpeg's MediaCodec decoder sets neither `frame-rate` nor `operating-rate` in the codec format
  (`operating_rate` AVOption, default 0, `mediacodecdec.c`), so the vendor codec picks its clocks itself.
- mpv's Android GL/Vulkan contexts do not report the display refresh rate, so `display-fps` is unknown to
  mpv and only `video-sync=audio` timing applies; the app never calls `Surface.setFrameRate`, so the 120 Hz
  panel stays at whatever refresh rate the system picks.
- The MediaCodec zero-copy interop (`hwdec_aimagereader`) works only with OpenGL; `hwdec-extra-frames`
  defaults to 6 and the decoder queue (`vd-queue-enable`) is off.
- Efficient mode sets `profile=fast`; interpolation and display-resample are not used anywhere.

Drops nearly equal on four configurations whose GPU and CPU cost differ by a factor of four point away from
rendering and towards something every configuration shares: the hardware decoder, the frame timing, or the
clip itself.

| # | Hypothesis (most likely first) | Why | Device test that confirms or rejects it |
|---|---|---|---|
| 1 | The clip is 60 fps and the HEVC Main10 4K MediaCodec path does not sustain 60 fps as mpv drives it (one frame in and out per playloop iteration, no frame-rate or operating-rate hint, so the codec runs at its default clocks); the late frames appear as VO drops. | If the clip is 60 fps, ~21 drops/s means ~38 fps reach the screen; identical across renderers; the 30 fps clip does not drop. | (a) Pull the P6-08 `benchmark_samples.csv`: `container_fps` 60 and `estimated_vf_fps` around 38 while `display_fps` is steady confirms the rate shortfall. (b) Decode-only speed: 25 s run with `mpv_opts 'video-sync=desync;untimed=yes;framedrop=no'` (video not paced by audio or display); `time-pos` advancing slower than wall time means the decoder is the limit. (c) A/B `mpv_opts 'vd-lavc-o=operating_rate=120'` (codec clock hint) and `'vd-queue-enable=yes'` (decode ahead on its own thread): drops falling to near 0 confirms and gives the fix. |
| 2 | Display refresh vs 60 fps content: the panel runs at a rate (or switches rate) where 60 fps frames miss vsyncs, and mpv cannot compensate because it does not know the refresh rate. | The 4K clips that drop are (or may be) 60 fps; the 29.97 fps clip has slack. | During a run: `adb shell dumpsys SurfaceFlinger \| grep -i -E "refresh\|fps\|activeMode"` and the runner's `display_fps` column. A/B `mpv_opts 'display-fps-override=120'` and `'=60'`. If the HEVC 4K drops stay but a 1080p60 HEVC clip (make one from any source: `ffmpeg -i in -vf scale=1920:1080,fps=60 -c:v libx265 -pix_fmt yuv420p10le`) also drops, it is timing, not decode. |
| 3 | Measurement artefact of looping: each `loop-file` restart seeks and flushes MediaCodec, and the frames around it are counted as drops. | The 60 s runs loop a 29 s clip twice. | Per-second samples of the P6-08 runs: drops in bursts at the loop points (time-pos wrap) instead of evenly. Rerun 25 s (no loop): `--ei seconds 25`. Unlikely to explain 1200 drops, but cheap to rule out. |
| 4 | Too few decoder output surfaces for 4K 10-bit zero-copy: mpv holds frames for display, the codec waits for free buffers. | Zero-copy keeps codec buffers until the frame is shown. | A/B `mpv_opts 'hwdec-extra-frames=12'` and `'=2'` on GLES zero-copy. Copy-back drops just as much, so this is unlikely to be the main cause. |
| 5 | Clip timestamps (variable or broken frame timing) or a bitrate peak beyond the decoder level. | Same drops everywhere is also what a bad clip looks like. | On the PC: `ffprobe -show_streams -show_frames -select_streams v hevc10_4k.mp4` (fps, level, bitrate, pts gaps); play the same file in another player on the phone (e.g. the stock gallery) and watch for stutter. |
| 6 | Clocks on battery, thermal limits or the P7-07 demuxer budget. | P1-06 measured on charger and before P7-07. | Same 25 s run on charger (compare drops only, not current) and with `--ez legacy_cache true` on the PlayerActivity intent. Temperatures were 32-34 C, so thermal limits are unlikely. |

**Safe fix candidates behind the debug extra** (no default changes until a device A/B shows a gain): the
generic `mpv_opts` debug extra (above) carries `vd-queue-enable=yes`, `vd-lavc-o=operating_rate=120`,
`hwdec-extra-frames=<n>`, `display-fps-override=<hz>`, `video-sync=desync`, `untimed=yes` and `framedrop=no`. If (1) is
confirmed, the candidate default is `vd-lavc-o=operating_rate=<clip fps x 2>` or `vd-queue-enable=yes` for
hardware-decoded clips above 30 fps, decided by measurement; if (2), calling `Surface.setFrameRate(<clip
fps>)` on the video surface is the Android-side fix.

Suggested device pass (about 10 minutes, unplugged, GLES zero-copy = the table row for HEVC):
```bash
B='am broadcast -a com.vibeplayer.BENCHMARK -n com.vibeplayer/.BenchmarkReceiver --es path /sdcard/Movies/vibeplayer-test/hevc10_4k.mp4 --ei seconds 25 --es mode efficient --es renderer gles'
adb shell "$B"                                                      # baseline, no loop
adb shell "$B --es mpv_opts 'video-sync=desync;untimed=yes;framedrop=no'"  # decoder speed (H1)
adb shell "$B --es mpv_opts 'vd-lavc-o=operating_rate=120'"         # H1 fix candidate
adb shell "$B --es mpv_opts 'vd-queue-enable=yes'"                  # H1 fix candidate
adb shell "$B --es mpv_opts 'display-fps-override=120'"             # H2
adb shell "$B --es mpv_opts 'hwdec-extra-frames=12'"                # H4
```
Before the next command wait for the log line `Benchmark: run over` (the CSV row is written before the
activity and service are torn down). For every row check in logcat that the options reached mpv
(`Debug mpv_opts applied at init: {...}`; a `rejected` line means a typo) and that hardware decoding stayed
on (`onPropertyChange: hwdec-current = mediacodec`): an option that breaks MediaCodec configuration makes
mpv fall back to software decoding, and the CSV has no hwdec column, so discard such rows. Then compare
`steady_drops`, `estimated_vf_fps`, `container_fps` and `display_fps`.

### Device result (2026-10-15 05:20-05:31, CLAUDE-V2, debug cb2abcf+, driver c0b92d25178c8b21, charging, 25 s, no loop, GLES zero-copy)

The broadcast only plays when Vibe Player is in front: from the home screen Android blocks the runner's
activity start ("Background activity launch blocked", goo.gle/android-bal) and the run samples time-pos 0. Start
`com.vibeplayer/.MainActivity` first (and add `-f 32` after a force-stop). Every row below logged the options as
applied and `hwdec-current = mediacodec`.

| mpv_opts | steady drops / 25 s | time-pos at 24 s | app CPU | note |
|---|---|---|---|---|
| none (baseline) | 1027, repeat 1025 | 23.4 | 18 % | clip is 60 fps (container_fps 60): ~41 of 60 frames/s dropped |
| video-sync=desync;untimed=yes;framedrop=no | 0 (drops disabled) | 23.3 | 25 % | unpaced decode still only keeps real time |
| **vd-lavc-o=operating_rate=120** | **0, repeat 0** | 24.3 | 27 % | H1 confirmed: codec clock hint removes the drops |
| vd-queue-enable=yes | 1006 | | 19 % | no effect |
| display-fps-override=120 | 1032 | | 18 % | no effect (H2 rejected) |
| hwdec-extra-frames=12 | 1027 | | 18 % | no effect (H4 rejected) |

Conclusion: hypothesis 1. Without a frame-rate / operating-rate hint the HEVC Main10 4K decoder runs at clocks that
deliver far fewer than 60 frames/s as mpv drives it; `operating_rate=120` brings it to real time with 0 drops at
+9 points app CPU. Candidate default (not applied, needs a reviewer decision and a power check on battery): pass
`vd-lavc-o=operating_rate=<2 x container fps>` for MediaCodec-decoded clips above 30 fps (or for all hardware
decodes), then re-run P6-08 for HEVC 4K and AV1-free rows. Picture visible was not re-checked per row (benchmark
runs), and current was not comparable (charging).


## Shader presets on OpenGL ES: known issues (P8-02, 2026-10-15)

- **Anime4K_Clamp_Highlights does not compile on the phone's GLES driver (gpu-next).** Device check
  (PROGRESS P8-02): presets A, B and C log `'MAINPRESUB_texOff' : no matching overloaded function found` /
  `'get_luma' : no matching overloaded function` for the stage's first pass (`//!HOOK MAIN`, `//!BIND HOOKED`,
  `MAIN_texOff(vec2(i - KERNELHALFSIZE, 0))`). libplacebo binds a `MAIN` hook under the name `MAINPRESUB` and
  emits `#define MAIN_texOff MAINPRESUB_texOff` plus the function-like `MAINPRESUB_texOff(off)`
  (`src/shaders/custom_mpv.c`, `bind_pass_tex`), so the error says the Adreno GLSL front end left
  `MAINPRESUB_texOff` unexpanded. The same pass, rebuilt with exactly those defines, compiles with
  `glslangValidator` for `#version 300 es`, `310 es` and `320 es`, and it compiles on Vulkan (shaderc); the
  CNN stages use the same `MAIN_texOff` alias (inside their own `go_0` macro) and compile on GLES. The
  cause is therefore the driver's preprocessor, not invalid GLSL, and a shader edit could not be verified
  without the phone. Because the failed first pass never saves `STATSMAX`, the stage's later passes had no
  input either; libplacebo then disables that one hook (`rr->disabled_hooks` in `src/renderer.c`, per user-shader
file) and the other stages keep running, so on GLES the stage never ran. Fix (P8-02 fix): `ShaderPreset.getShaderFiles(mode,
  renderer)` leaves `Anime4K_Clamp_Highlights.glsl` out of A, B and C when the handle's renderer is OpenGL
  ES; Vulkan keeps the full chain and GSR is unchanged. The picture on GLES is the same as before; only the
  compile error in the log goes away.
- **GLES gpu-next logs `Can't generate LUT: no compatible methods!` / `Failed generating/uploading block
  offsets LUT!` with no shader loaded at all.** This is libplacebo on the GLES backend (a LUT format or
  method the driver does not offer), not caused by the presets and not fixable in the app; known, left as is.
