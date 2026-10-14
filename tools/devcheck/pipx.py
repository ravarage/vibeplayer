"""Parse Picture-in-Picture state and bounds from `dumpsys activity activities` / `dumpsys window` text."""
import re

_RECT1 = re.compile(r"Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)")
_RECT2 = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
_BOUNDS_KEY = re.compile(r"(?i)\b(?:m?bounds|m?frame|m?windowFrames?|mAppBounds|mParentFrame)\b")


def _split_blocks(text):
    blocks, cur = [], []
    for ln in text.splitlines():
        if re.match(r"^\s*\*?\s*(Task|ActivityRecord|Window)\b[{# ]", ln) and cur:
            blocks.append("\n".join(cur))
            cur = []
        cur.append(ln)
    if cur:
        blocks.append("\n".join(cur))
    return blocks


def _first_rect(s):
    for ln in s.splitlines():
        if _BOUNDS_KEY.search(ln):
            for rx in (_RECT1, _RECT2):
                m = rx.search(ln)
                if m:
                    l, t, r, b = map(int, m.groups())
                    if r > l and b > t:
                        return (l, t, r, b)
    return None


def parse_pip(text, package="com.vibeplayer"):
    """Return (is_pinned, bounds or None). Pinned = a task/activity block mentioning the package that
    carries windowingMode=pinned (or WINDOWING_MODE_PINNED / mode=pinned / inPictureInPicture)."""
    pinned = False
    bounds = None
    for blk in _split_blocks(text):
        low = blk.lower()
        if package.lower() not in low:
            continue
        if re.search(r"windowingmode=(?:windowing_mode_)?pinned|windowing_mode_pinned|\bmode=pinned|"
                     r"ispictureinpicturemode=true|inpictureinpicture(?:mode)?=true|mpipmode=true", low) or \
                re.search(r"windowingmode=\s*2\b", low):
            pinned = True
            r = _first_rect(blk)
            if r:
                bounds = r
                break
    return pinned, bounds


def aspect_matches(bounds, video_w, video_h, tol=0.10):
    l, t, r, b = bounds
    if video_w <= 0 or video_h <= 0:
        return None
    pip = (r - l) / float(b - t)
    vid = video_w / float(video_h)
    vid = min(max(vid, 1 / 2.39), 2.39)  # PipHelper clamps the ratio
    return abs(pip - vid) / vid <= tol
