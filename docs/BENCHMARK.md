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

