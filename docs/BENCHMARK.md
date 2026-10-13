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

Other debug runs: folder list (9 folders) 102 frames, 4 janky (3.92%); "Music" (37 videos) first pass
159 frames, 14 janky (8.81%), second pass 162 frames, 6 janky (3.70%).

Notes: all debug janky frames were "Slow UI thread". A simpleperf sample of the debug build during a
scroll showed the main thread mostly in the JIT code cache and `libart.so` (interpreting / JIT-compiled
Compose code), which is what a debuggable build costs. The release runs were taken with the display in
landscape (the phone was lying on its side), so fewer rows were on screen than in the debug runs
(portrait); a portrait release run is still missing. The release build logs nothing below warn, so
"thumbnails being generated" in the second release row is assumed from the scroll position, not
confirmed from a log.

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
