"""Image metrics (Pillow only). Images are downscaled before sampling so checks stay fast."""
from PIL import Image, ImageChops


def _small(img, max_w=360):
    img = img.convert("RGB")
    if img.width > max_w:
        k = max(1, img.width // max_w)
        img = img.reduce(k)
    return img


def crop_frac(img, l, t, r, b):
    w, h = img.size
    return img.crop((int(w * l), int(h * t), int(w * r), int(h * b)))


def crop_box(img, box, shrink=0.0):
    l, t, r, b = box
    dw, dh = (r - l) * shrink, (b - t) * shrink
    l, t, r, b = int(l + dw), int(t + dh), int(r - dw), int(b - dh)
    l, t = max(0, l), max(0, t)
    r, b = min(img.width, r), min(img.height, b)
    if r <= l or b <= t:
        raise ValueError("empty crop")
    return img.crop((l, t, r, b))


def _rgb(img):
    b = _small(img).tobytes()
    return [(b[i], b[i + 1], b[i + 2]) for i in range(0, len(b) - 2, 3)]


def nonblack_ratio(img, thr=48):
    """Share of pixels whose R+G+B exceeds thr (same definition as tools/check_video.py)."""
    px = _rgb(img)
    return sum(1 for p in px if p[0] + p[1] + p[2] > thr) / max(1, len(px))


def changed_ratio(a, b, thr=24):
    """Share of pixels whose summed channel difference exceeds thr. Images are resized to a's size."""
    a, b = _small(a), _small(b)
    if a.size != b.size:
        b = b.resize(a.size)
    px = _rgb(ImageChops.difference(a, b))
    return sum(1 for p in px if p[0] + p[1] + p[2] > thr) / max(1, len(px))


def luma_stats(img):
    """(mean, stddev) of luminance, 0..255."""
    px = list(_small(img, 200).convert("L").tobytes())
    n = max(1, len(px))
    mean = sum(px) / n
    var = sum((p - mean) ** 2 for p in px) / n
    return mean, var ** 0.5


def is_picture(img, min_nonblack=0.03):
    return nonblack_ratio(img) > min_nonblack


def is_moving(a, b, min_changed=0.002):
    return changed_ratio(a, b) > min_changed


def is_landscape(img):
    return img.width > img.height
