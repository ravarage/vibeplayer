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
