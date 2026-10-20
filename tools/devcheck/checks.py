"""The regression checks. Each takes a Ctx and returns a one-line reason (PASS), or raises Fail/Skip.

Registry order is the run order. Every check starts from a clean cold launch so it is idempotent,
and restores what it changed (speed, aspect, track choices, media volume) before returning.
"""
import re
import time

from . import imgx, pipx, uix
from .ctx import (Ctx, Fail, Skip, need, TEST_DIR, PLAYER, PAUSE_TXT, PLAY_TXT, file_uri)
from .safety import PKG

CHECKS = []  # (name, description, fn)


def check(name, desc):
    def deco(fn):
        CHECKS.append((name, desc, fn))
        return fn
    return deco


H264 = "ichiban.mp4"
HEVC = "hevc10_4k.mp4"
VP9 = "vp9_1080p.webm"
AV14K = "av1_4k.mp4"
ASS = "[denisplay] Bocchi the Rock! - S01E01.mkv"
AV1SW = "[Ironclad] Clevatess - S02E01 [WEB.1080p.AV1].mkv"
MULTI = "multi_track.mkv"
CASE = "Case Closed - 001 - The Big Shrink.mkv"
SW_VALUES = {"", "no", "none", "None", "null"}


def _need_clip(ctx, name):
    if name not in ctx.phone.listing(TEST_DIR):
        raise Skip(f"{name} not in {TEST_DIR}")


def _hwdec(ctx, since):
    v = ctx.last_value(r"hwdec=(\S+)", since, tag="VP-Check")
    if v:
        return v
    return ctx.last_value(r"onPropertyChange: hwdec-current = (.*)$", since)


# ---- cold open --------------------------------------------------------------------------------
def _open(ctx, clip, label, extras=""):
    _need_clip(ctx, clip)
    t0 = time.monotonic()
    mark = ctx.launch_clip(clip, extras=extras)
    hit = ctx.wait_restart(mark, 25)
    need(hit, f"{label}: no playback-restart within 25 s")
    first = time.monotonic() - t0
    ctx.sleep(2.0)
    nb, ch = ctx.need_moving(label)
    wd = ctx.log.find(r"B-08 watchdog", since=mark)
    hw = _hwdec(ctx, mark)
    fb = ctx.log.find(r"B-08 recovery", since=mark)
    need(not wd, f"{label}: B-08 watchdog fired")
    return f"{label}: moving picture (non_black={nb:.0%}, changed={ch:.1%}), restart after ~{first:.1f}s, hwdec={hw}" + \
           (", software-recovery used" if fb else "")


@check("open_h264", "Cold open H.264 1080p shows a moving picture")
def open_h264(ctx):
    return _open(ctx, H264, "H.264")


@check("open_hevc10", "Cold open HEVC 10-bit 4K")
def open_hevc10(ctx):
    return _open(ctx, HEVC, "HEVC 10-bit 4K")


@check("open_vp9", "Cold open VP9")
def open_vp9(ctx):
    return _open(ctx, VP9, "VP9", extras="--el start_ms 0")


@check("open_av1_4k", "Cold open AV1 10-bit 4K")
def open_av1_4k(ctx):
    return _open(ctx, AV14K, "AV1 4K")


@check("open_av1_ass", "Cold open styled-ASS clip (Bocchi) and software AV1 1080p")
def open_av1_ass(ctx):
    a = _open(ctx, ASS, "ASS clip")
    ctx.shot("ass")
    b = _open(ctx, AV1SW, "AV1 1080p sw")
    return a + " | " + b + " (ASS styling needs a human look at the 'ass' screenshot)"


# Note on hwdec reporting: devcheck launches clips without mode extras (--es mode),
# thus testing against the user's active AppSettings preference. In EFFICIENT mode (the app's
# default), format_table.json selects GLES zero-copy (hwdec=mediacodec) for HEVC and VP9.
# In QUALITY mode (e.g. when left enabled from quality/benchmark passes), FormatProfileTable.resolve
# maps all hardware decoders to copy-back (hwdec=mediacodec-copy) with vo=gpu-next to support custom
# shaders/scaling. Both satisfy the hwdec check (neither is in SW_VALUES).
@check("hwdec", "Hardware decode in use for H.264, HEVC and VP9")
def hwdec(ctx):
    res = []
    for clip, lab in ((H264, "H.264"), (HEVC, "HEVC"), (VP9, "VP9")):
        _need_clip(ctx, clip)
        mark = ctx.play_clip(clip, settle=1.0)
        ctx.log.wait(r"hwdec=(\S+)", 6, since=mark, tag="VP-Check") or ctx.log.wait(r"onPropertyChange: hwdec-current = (.*)$", 6, since=mark)
        ctx.sleep(1.5)  # the first report is "no"/"null" before the codec is opened; take the last one
        val = _hwdec(ctx, mark)
        val = val.strip() if val else None
        if val is None:
            v = ctx.log.wait(r"onPropertyChange: hwdec-current = (.*)$", 6, since=mark)
            val = v[1].group(1).strip() if v else None
        if val is None:
            ns = ctx.controls_nodes()
            xy = (ctx.size()[0] // 3, ctx.dpx(48))
            title = uix.find(ns, contains=clip[:12]) if ns else None
            ctx.phone.long_press(*(uix.center(title) if title else xy))
            s = ctx.log.wait(r"Stats: vo=\S* hwdec=(\S*) ", 6, since=mark, tag="VP-Perf")
            val = s[1].group(1) if s else None
            ctx.phone.long_press(*(uix.center(title) if title else xy))
        if val is None:
            raise Skip(f"{lab}: no hwdec evidence (no debug property log, debug overlay gave no Stats line)")
        need(val not in SW_VALUES, f"{lab}: software decode (hwdec-current='{val}')")
        res.append(f"{lab}={val}")
    return ", ".join(res)


# ---- controls and gestures ----------------------------------------------------------------------
@check("controls_tap", "Tap toggles the control overlay")
def controls_tap(ctx):
    ctx.play_clip(H264, settle=1.0)
    mark = ctx.log.mark()
    w, h = ctx.size()
    ns0 = ctx.nodes()
    vis0 = bool(uix.find(ns0, desc="play_pause") or uix.find(ns0, text=PAUSE_TXT) or uix.find(ns0, text=PLAY_TXT))
    before = ctx.shot("before")
    ctx.phone.tap(w // 2, int(h * 0.25))
    ctx.sleep(0.9)
    ns1 = ctx.nodes()
    after = ctx.shot("after")
    vis1 = bool(uix.find(ns1, desc="play_pause") or uix.find(ns1, text=PAUSE_TXT) or uix.find(ns1, text=PLAY_TXT))
    need(ctx.log.wait(r"Gesture detected: TAP", 3, since=mark), "tap not seen by the gesture layer")
    if ns0 and ns1:
        need(vis0 != vis1, f"controls visibility did not change after a tap (before={vis0}, after={vis1})")
        return f"overlay toggled ({'shown' if vis1 else 'hidden'} after tap), title/buttons found via uiautomator"
    ch = imgx.changed_ratio(before, after)
    need(ch > 0.02, f"uiautomator unavailable and screen changed only {ch:.1%} after a tap")
    return f"overlay changed the screen by {ch:.0%} (uiautomator dump unavailable, image fallback)"


@check("play_pause", "Pause button freezes the picture, play resumes it")
def play_pause(ctx):
    ctx.play_clip(H264)
    ns = None
    m = ctx.log.mark()
    how = ctx.tap_center_button("pause", ns)
    need(ctx.log.wait(r"togglePause", 4, since=m, tag="VP"), "no togglePause after tapping the centre button")
    ctx.ctrl_pinned = True  # paused: the overlay stays
    ctx.sleep(1.0)
    ctx.need_still("paused picture")
    ns = None
    m = ctx.log.mark()
    ctx.tap_center_button("pause", ns)
    need(ctx.log.wait(r"togglePause", 4, since=m, tag="VP"), "no togglePause when resuming")
    ctx.ctrl_pinned = False
    ctx.sleep(2.0)
    ctx.need_moving("resumed picture")
    return f"paused (static) then resumed (moving); button found via {how}"


@check("double_tap", "Double tap right/left seeks +-10 s")
def double_tap(ctx):
    _need_clip(ctx, MULTI)
    # fixed start: from a resume point near the end of the 46 s clip, +10 s reaches the end and the
    # picture stops (end of file), which looked like a frozen player
    ctx.play_clip(MULTI, extras="--el start_ms 5000")
    ctx.sleep(3)
    w, h = ctx.size()
    out = []
    for side, x, name, sec in (("right", int(w * 0.75), "DOUBLE_TAP_RIGHT", 10), ("left", int(w * 0.25), "DOUBLE_TAP_LEFT", -10)):
        ok = False
        for attempt in range(8):
            m = ctx.log.mark()
            ctx.phone.double_tap(x, int(h * 0.5))
            if ctx.log.wait(r"Gesture detected: " + name, 3, since=m, tag="VP"):
                ok = True
                break
            ctx.sleep(1.0)
        need(ok, f"{side} double tap never recognised in 8 attempts (timing of concurrent taps?)")
        ctx.shot(side)
        need(ctx.log.wait(r"seekRelative %s\d+(\.\d+)? s" % ("" if sec > 0 else "-"), 3, since=m, tag="VP"), f"no seekRelative {sec} s after {side} double tap")
        out.append(f"{side} {sec:+d}s (attempt {attempt + 1})")
        ctx.sleep(2.0)
    ctx.need_moving("picture after double taps")
    return "; ".join(out)


@check("swipe_seek", "Horizontal swipe seeks proportionally (40 ms per pixel)")
def swipe_seek(ctx):
    _need_clip(ctx, MULTI)
    # fixed start (debug hook): from a random resume point +20 s can hit the end of the 46 s clip,
    # auto-play the next file from 0 and clamp the backward swipe
    ctx.play_clip(MULTI, extras="--el start_ms 5000")
    w, h = ctx.size()
    x0 = max(int(w * 0.07), ctx.dpx(48))
    m = ctx.log.mark()
    ctx.phone.swipe(x0, int(h * 0.5), x0 + 500, int(h * 0.5), 500)
    hit = ctx.log.wait(r"Swipe seek commit: deltaSec=(-?[\d.]+) \(start=(\d+), target=(\d+)\)", 6, since=m)
    need(hit, "no 'Swipe seek commit' after a 500 px forward swipe")
    d = round(float(hit[1].group(1)))
    need(8 <= d <= 24, f"forward 500 px swipe gave deltaSec={d} (expected about +20)")
    ctx.sleep(1.5)
    m = ctx.log.mark()
    ctx.phone.swipe(x0 + 400, int(h * 0.5), x0 + 100, int(h * 0.5), 500)
    hit2 = ctx.log.wait(r"Swipe seek commit: deltaSec=(-?[\d.]+) ", 6, since=m)
    need(hit2, "no commit after a backward swipe")
    d2 = round(float(hit2[1].group(1)))
    need(-16 <= d2 <= -3, f"backward 300 px swipe gave deltaSec={d2} (expected about -12)")
    ctx.sleep(1.5)
    ctx.need_moving("picture after swipe seeks")
    return f"forward {d:+d}s, backward {d2:+d}s, picture still moving"


def _vertical(ctx, x, gesture, up, vol_read):
    w, h = ctx.size()
    y1, y2 = (int(h * 0.75), int(h * 0.25)) if up else (int(h * 0.25), int(h * 0.75))
    m = ctx.log.mark()
    proc = ctx.phone.swipe_async(x, y1, x, y2, 1600)
    ctx.sleep(0.9)
    ns = ctx.nodes(1)
    ctx.shot(gesture.lower())
    ind = uix.find(ns, regex=r"^\d{1,3}%$") if ns else None
    try:
        proc.wait(timeout=15)
    except Exception:
        pass
    seen = bool(ctx.log.wait(r"Gesture detected: " + gesture, 3, since=m, tag="VP"))
    return seen, ind


@check("brightness_volume", "Left vertical swipe shows brightness, right shows volume (volume restored)")
def brightness_volume(ctx):
    ctx.play_clip(H264)
    w, h = ctx.size()
    vol0 = ctx.phone.music_volume()
    try:
        seen_b, ind_b = _vertical(ctx, int(w * 0.2), "LEFT_VERTICAL", True, None)
        need(seen_b, "left vertical swipe was not recognised as LEFT_VERTICAL")
        _vertical(ctx, int(w * 0.2), "LEFT_VERTICAL", False, None)  # put brightness back
        ctx.sleep(1.0)
        up = vol0 is None or vol0 < 8
        seen_v, ind_v = _vertical(ctx, int(w * 0.8), "RIGHT_VERTICAL", up, None)
        need(seen_v, "right vertical swipe was not recognised as RIGHT_VERTICAL")
        vol1 = ctx.phone.music_volume()
        if vol0 is not None and vol1 is not None:
            need(vol1 != vol0, f"media volume unchanged by the volume swipe ({vol0} -> {vol1})")
        ctx.sleep(1.0)
    finally:
        if vol0 is not None:
            ctx.phone.set_music_volume(vol0)
    ctx.need_moving("picture after brightness/volume swipes")
    return (f"brightness gesture seen (indicator text: {ind_b.text if ind_b else 'not captured'}); "
            f"volume {vol0} -> {vol1} -> restored {vol0}; indicator: {ind_v.text if ind_v else 'not captured'}")


@check("lock_unlock", "Lock blocks gestures, Unlock restores them")
def lock_unlock(ctx):
    ctx.play_clip(H264)
    ns = None
    how = ctx.tap_top("lock", ns)
    ctx.sleep(1.0)
    ns = ctx.nodes()
    unlock = uix.find(ns, desc="unlock") or uix.find(ns, text="Unlock") or uix.find(ns, contains="Unlock")
    if ns:
        need(unlock, "no 'Unlock' button after tapping the lock icon")
    w, h = ctx.size()
    m = ctx.log.mark()
    ctx.phone.swipe(int(w * 0.3), int(h * 0.5), int(w * 0.3) + 400, int(h * 0.5), 400)
    ctx.sleep(1.0)
    ctx.log.refresh()
    need(not ctx.log.find(r"Gesture detected: HORIZONTAL", since=m), "swipe still recognised while locked")
    ctx.shot("locked")
    if unlock:
        ctx.phone.tap(*uix.center(unlock))
    else:
        ctx.phone.tap(ctx.dpx(28 + 60), ctx.dpx(28 + 20))
    ctx.sleep(1.0)
    m = ctx.log.mark()
    ctx.phone.swipe(int(w * 0.3), int(h * 0.5), int(w * 0.3) + 400, int(h * 0.5), 400)
    need(ctx.log.wait(r"Gesture detected: HORIZONTAL", 4, since=m), "gestures did not come back after Unlock")
    ctx.sleep(1.5)
    ctx.need_moving("picture after unlock")
    return f"lock button via {how}; swipe ignored while locked; swipe works after unlock"


@check("orientation_home", "Wide video plays landscape; Home and return keep the picture")
def orientation_home(ctx):
    ctx.play_clip(H264)
    img = ctx.shot("landscape")
    need(imgx.is_landscape(img), f"screen is {img.width}x{img.height}, expected landscape for a wide video")
    ctx.phone.key("KEYCODE_HOME")
    pinned = ctx.log.wait(r"onPictureInPictureModeChanged: isInPictureInPictureMode=true", 6)
    ctx.sleep(1.0)
    ctx.shot("home")
    _expand_pip(ctx)
    ctx.sleep(2.0)
    img2 = ctx.shot("returned")
    need(imgx.is_landscape(img2), "not landscape after returning")
    ctx.need_moving("picture after return")
    return f"landscape {img.width}x{img.height}; Home {'entered PiP' if pinned else 'left the app'}; picture moving after return"


# ---- PiP helpers ----------------------------------------------------------------------------------
def _pip_state(ctx):
    txt = ctx.phone.sh("dumpsys activity activities", 40)
    pinned, b = pipx.parse_pip(txt)
    if pinned and not b:
        pinned2, b = pipx.parse_pip(ctx.phone.sh("dumpsys window windows", 40))
    return pinned, b


def _expand_pip(ctx):
    pinned, b = _pip_state(ctx)
    if pinned and b:
        cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        ctx.phone.double_tap(cx, cy)
        if ctx.log.wait(r"isInPictureInPictureMode=false", 5):
            return "double-tap on the mini player"
    ctx.phone.start_activity(PLAYER)
    ctx.log.wait(r"isInPictureInPictureMode=false", 6)
    return "am start of PlayerActivity (double-tap did not expand)"


@check("pip", "Mini player: enter on Home, aspect, picture moves inside the window, expand")
def pip(ctx):
    mark = ctx.play_clip(H264)
    w_ = ctx.last_value(r"onPropertyChange: dwidth = (\d+)", mark)
    h_ = ctx.last_value(r"onPropertyChange: dheight = (\d+)", mark)
    ctx.phone.key("KEYCODE_HOME")
    need(ctx.log.wait(r"onPictureInPictureModeChanged: isInPictureInPictureMode=true", 8), "Home did not enter PiP")
    ctx.sleep(1.5)
    pinned, b = _pip_state(ctx)
    need(pinned, "dumpsys shows no pinned (PiP) task for com.vibeplayer")
    if not b:
        pb = ctx.log.find(r"PiP (?:params set: )?bounds=(?:Rect\()?(-?\d+)[,\s]+(-?\d+)[,\s-]+(-?\d+)[,\s]+(-?\d+)\)?", since=mark)
        if pb:
            b = (int(pb[1].group(1)), int(pb[1].group(2)), int(pb[1].group(3)), int(pb[1].group(4)))
    if not b:
        raise Skip("in PiP but dumpsys bounds could not be parsed (see README hooks)")
    asp = pipx.aspect_matches(b, int(w_ or 0), int(h_ or 0))
    a = ctx.shot("pip_a")
    ctx.sleep(1.0)
    c = ctx.shot("pip_c")
    ca, cc = imgx.crop_box(a, b, 0.06), imgx.crop_box(c, b, 0.06)
    nb, ch = imgx.nonblack_ratio(ca), imgx.changed_ratio(ca, cc)
    outside_w = (b[2] - b[0]) / a.width
    need(nb > 0.05, f"PiP window {b} is black (non_black={nb:.0%})")
    need(ch > 0.002, f"PiP picture frozen (changed={ch:.2%})")
    need(asp is not False, f"PiP bounds {b} do not match the video aspect {w_}x{h_}")
    how = _expand_pip(ctx)
    ctx.sleep(2.0)
    need(not _pip_state(ctx)[0], "still pinned after expand attempt")
    ctx.need_moving("fullscreen picture after expand")
    return f"PiP bounds {b} aspect {'ok' if asp else 'unchecked'}, picture moving inside (non_black={nb:.0%}, changed={ch:.1%}); expanded via {how}"


@check("pip_autoadvance", "Auto-advance to the next file keeps playing inside the mini player")
def pip_autoadvance(ctx):
    order = ctx.order()
    _need_clip(ctx, MULTI)
    i = order.index(MULTI)
    need(i + 1 < len(order), f"{MULTI} is the last file in the folder, no next file")
    nxt = order[i + 1]
    mark = ctx.play_clip(MULTI)
    ctx.seek_near_end(remaining_s=14.0)
    ctx.phone.key("KEYCODE_HOME")
    # 15 s: right after a swipe seek the first Home can be slow to hand over to PiP over Wi-Fi adb
    if not ctx.log.wait(r"isInPictureInPictureMode=true", 15, since=mark):
        resumed = [l.strip() for l in ctx.phone.sh("dumpsys activity activities").splitlines()
                   if "topResumedActivity" in l or "mResumedActivity" in l]
        ctx.trace("no PiP after Home; resumed: " + " | ".join(resumed[:2]))
        need(False, "Home did not enter PiP")
    hit = ctx.log.wait(r"Auto-play: .* ended -> (.*)$", 40, since=mark)
    need(hit, "no auto-play while in PiP within 40 s")
    need(hit[1].group(1).endswith(nxt), f"auto-play went to {hit[1].group(1)}, expected {nxt}")
    since = hit[0].seq
    need(ctx.log.wait(r"playback-restart", 25, since=since, tag="VP-Perf"), "next file did not start (no playback-restart)")
    ctx.sleep(2.5)
    pinned, b = _pip_state(ctx)
    need(pinned, "no longer in PiP after auto-advance")
    if b:
        a = ctx.shot("a")
        ctx.sleep(1.0)
        c = ctx.shot("c")
        ca, cc = imgx.crop_box(a, b, 0.06), imgx.crop_box(c, b, 0.06)
        need(imgx.nonblack_ratio(ca) > 0.05, "PiP window black after auto-advance")
        need(imgx.changed_ratio(ca, cc) > 0.002, "PiP picture frozen after auto-advance")
    _expand_pip(ctx)
    return f"advanced to {nxt} inside PiP, pinned and playing"


# ---- resume, tracks, speed, aspect ---------------------------------------------------------------
@check("resume", "Position is resumed after force-stop")
def resume(ctx):
    _need_clip(ctx, MULTI)
    mark = ctx.play_clip(MULTI)
    w, h = ctx.size()
    # a fresh playback starts at the saved position if any; wait for a periodic save of >= 8 s
    hit = ctx.log.wait(r"Saved position (\d+) ms", 30, since=mark)
    need(hit, "no periodic 'Saved position' within 30 s")
    saved = round(float(hit[1].group(1)))
    for _ in range(3):
        if saved >= 8000 or saved >= 40000:
            break
        hit = ctx.log.wait(r"Saved position (\d+) ms", 14, since=hit[0].seq + 1)
        need(hit, "no further periodic save")
        saved = round(float(hit[1].group(1)))
    ctx.stop_app()
    mark2 = ctx.launch_clip(MULTI)
    r = ctx.log.wait(r"Resume: (resuming from (\d+) ms|pos \d+ ms is in last 5%)", 12, since=mark2)
    need(r, "no 'Resume:' log after relaunch")
    if "last 5%" in r[1].group(1):
        raise Skip("saved position was in the last 5%, restart from 0 is correct (re-run)")
    got = int(r[1].group(2))
    need(abs(got - saved) < 12000 and got >= 5000, f"resumed from {got} ms, last save was {saved} ms")
    need(ctx.wait_restart(mark2, 20), "no playback after resume")
    ctx.sleep(2)
    ctx.need_moving("resumed picture")
    return f"saved {saved} ms, resumed from {got} ms, picture moving"


def _sheet_rows(ctx, opener, header):
    ns = None
    hdr = None
    for attempt in range(3):  # the dump can miss a sheet that is still animating, or the tap can miss
        ctx.tap_top(opener, ns)
        ctx.sleep(1.2)
        ns = ctx.nodes()
        hdr = uix.find(ns, text=header)
        if hdr:
            break
        ctx.sleep(1.0)
        ns = ctx.nodes()
        hdr = uix.find(ns, text=header)
        if hdr:
            break
    need(hdr, f"sheet '{header}' not found in the UI dump")
    prefix = "audio_track_" if opener == "audio" else "sub_track_"
    # the sheet slides up; rows below the screen edge are missing from an early dump: wait until two
    # consecutive dumps show the same number of track rows
    prev = len([n for n in ns if n.desc and n.desc.startswith(prefix)])
    for _ in range(4):
        ctx.sleep(0.8)
        ns2 = ctx.nodes()
        if not uix.find(ns2, text=header):
            break
        ns, hdr = ns2, uix.find(ns2, text=header)
        cnt = len([n for n in ns if n.desc and n.desc.startswith(prefix)])
        if cnt == prev and cnt > 0:
            break
        prev = cnt
    cands = [n for n in ns if n.desc and n.desc.startswith(prefix)]
    if cands:
        rows = []
        for n in cands:
            txts = uix.texts_in(ns, n.bounds) or [n.desc]
            rows.append((n, txts))
        return rows
    rows = []
    for n in ns:
        if n.clickable and n.bounds[1] >= hdr.bounds[3] - 4 and n.bounds[2] - n.bounds[0] > 300:
            txts = uix.texts_in(ns, n.bounds)
            if txts:
                rows.append((n, txts))
    if not rows:
        raise Skip(f"'{header}' rows are not clickable nodes in the uiautomator dump (see README hooks)")
    return rows


def _is_off(txts, desc=""):
    return "audio_track_off" in desc or "sub_track_off" in desc or any(t in ("Mute / Off", "Off") for t in txts)


def _track_roundtrip(ctx, clip, kind):
    opener, header, setlog, persist, upd = (("audio", "Audio Tracks", r"setAudioTrack: aid=(\S+)", r"Persisted file audioTrackId=(\S+),", 1)
                                           if kind == "audio" else
                                           ("sub", "Subtitles", r"setSubtitleTrack: sid=(\S+)", r"Persisted file subTrackId=(\S+),", 2))
    ctx.play_clip(clip)
    rows = _sheet_rows(ctx, opener, header)
    sel = [r for r in rows if "✓" in r[1]]
    others = [r for r in rows if "✓" not in r[1] and not _is_off(r[1], r[0].desc)]
    if not others:
        raise Skip(f"{clip}: no alternative {kind} track to choose")
    orig = sel[0] if sel else None
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(others[0][0]))
    hit = ctx.log.wait(setlog, 5, since=m)
    need(hit, f"{clip}: choosing a {kind} track produced no set{kind.title()}Track log")
    chosen = hit[1].group(1)
    need(ctx.log.wait(persist, 6, since=m), f"{clip}: choice not persisted (no 'Persisted file' log)")
    ctx.stop_app()
    mark = ctx.launch_clip(clip)
    need(ctx.wait_restart(mark, 25), f"{clip}: reopen did not start")
    ctx.sleep(3)
    cur = ctx.last_value(r"aid=(\S+)" if kind == "audio" else r"sid=(\S+)", mark, tag="VP-Check")
    if not cur:
        cur = ctx.last_value(r"Updated tracks \(\d+\): aid=(\S+), sid=(\S+)", mark, group=upd)
    need(cur == chosen, f"{clip}: {kind} track after reopen is {cur}, chosen was {chosen}")
    # restore the original choice
    if orig is not None:
        rows2 = _sheet_rows(ctx, opener, header)
        want = [r for r in rows2 if r[1] == orig[1] or (r[0].desc and r[0].desc == orig[0].desc)]
        if want:
            m = ctx.log.mark()
            ctx.phone.tap(*uix.center(want[0][0]))
            ctx.log.wait(persist, 6, since=m)
    return f"{clip}: {kind} id {chosen} remembered after force-stop (original restored)"


@check("track_memory", "Audio and subtitle choice remembered per file (multi_track.mkv, Case Closed)")
def track_memory(ctx):
    res = []
    for clip in (MULTI, CASE):
        _need_clip(ctx, clip)
        for kind in ("audio", "sub"):
            res.append(_track_roundtrip(ctx, clip, kind))
    return " | ".join(res)


@check("folder_track_memory", "Audio language chosen in one file is applied to other files of the folder")
def folder_track_memory(ctx):
    _need_clip(ctx, CASE)
    ctx.play_clip(CASE)
    rows = _sheet_rows(ctx, "audio", "Audio Tracks")
    sel = [r for r in rows if "✓" in r[1]]
    others = [r for r in rows if "✓" not in r[1] and not _is_off(r[1], r[0].desc)]
    need(others, "no alternative audio track in Case Closed")
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(others[0][0]))
    hit = ctx.log.wait(r"Persisted folder audio: lang=(\S+), title", 6, since=m)
    need(hit, "no 'Persisted folder audio' log (folder memory not written)")
    lang = hit[1].group(1)
    try:
        if lang in ("null", ""):
            raise Skip("chosen track has no language tag; folder memory is by language")
        tested = []
        for cand in (ASS, AV1SW, H264, MULTI):
            if cand not in ctx.phone.listing(TEST_DIR):
                continue
            mk = ctx.play_clip(cand, settle=3)
            pend = ctx.log.find(r"Pending track selection set: file=(\S+)", mk)  # file=FileTrackChoice(... has spaces
            ts = ctx.log.find_all(r"id=(\d+) type=audio lang=(\S+) ", mk, tag="VP-Track")
            audio = {int(x[1].group(1)): x[1].group(2) for x in ts}
            if lang not in audio.values():
                continue
            if pend and not pend[1].group(1).startswith("null"):
                continue  # per-file choice exists, inconclusive
            aid = ctx.last_value(r"aid=(\S+)", mk, tag="VP-Check")
            if not aid:
                aid = ctx.last_value(r"Updated tracks \(\d+\): aid=(\S+), sid=", mk)
            need(aid and aid.isdigit() and audio.get(int(aid)) == lang,
                 f"{cand}: audio is track {aid} ({audio.get(int(aid)) if aid and aid.isdigit() else '?'}), folder memory says '{lang}'")
            tested.append(cand)
            break
        if not tested:
            raise Skip(f"no other clip with audio '{lang}' and no per-file choice to verify against")
        return f"language '{lang}' chosen in Case Closed applied to {tested[0]}"
    finally:
        ctx.play_clip(CASE, settle=1)
        if sel:
            try:
                rows = _sheet_rows(ctx, "audio", "Audio Tracks")
                want = [r for r in rows if r[1] == sel[0][1]]
                if want:
                    ctx.phone.tap(*uix.center(want[0][0]))
                    ctx.sleep(1.5)
            except Exception:
                pass


@check("speed", "Playback speed is remembered per file")
def speed(ctx):
    ctx.play_clip(H264)
    ns = None
    ctx.tap_top("speed", ns)
    chip = None
    for _ in range(3):  # the sheet slides up; an early dump misses the preset rows
        ctx.sleep(1.2)
        ns = ctx.nodes()
        chip = uix.find(ns, desc="speed_1.5") or uix.find(ns, text="1.5x")
        if chip:
            break
    need(chip, "no '1.5x' preset in the speed sheet (uiautomator)")
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(chip))
    need(ctx.log.wait(r"setSpeed: speed=1\.5", 4, since=m), "choosing 1.5x produced no setSpeed log")
    need(ctx.log.wait(r"Persisted speed=1\.5", 6, since=m), "speed not persisted")
    try:
        ctx.stop_app()
        mark = ctx.launch_clip(H264)
        need(ctx.wait_restart(mark, 25), "reopen did not start")
        ctx.sleep(2)
        sp = ctx.last_value(r"speed=([\d.]+)", mark, tag="VP-Check")
        if sp is None:
            sp = ctx.last_value(r"onPropertyChange: speed = ([\d.]+)", mark)
        if sp is None:
            ns = ctx.controls_nodes()
            need(uix.find(ns, desc="speed") or uix.find(ns, text="1.5x"), "speed chip does not show 1.5x after reopen")
            sp = "1.5 (chip)"
        else:
            need(abs(float(sp) - 1.5) < 0.01, f"speed after reopen is {sp}")
        ctx.need_moving("picture at 1.5x")
    finally:
        try:
            ns = None
            ctx.tap_top("speed", ns)
            ctx.sleep(1.2)
            ns = ctx.nodes()
            chip = uix.find(ns, desc="speed_1.0") or uix.find(ns, text="1x")
            m = ctx.log.mark()
            if chip:
                ctx.phone.tap(*uix.center(chip))
                ctx.log.wait(r"Persisted speed=1\.0", 6, since=m)
        except Exception:
            pass
    return f"1.5x remembered after force-stop (speed={sp}); reset to 1x"


def _tap_aspect(ctx, tries=3):
    """Taps the aspect button until a setAspect line follows. Only setAspect counts: the periodic VP-Check
    state line also says aspect=..., so a missed tap (controls hid while the layout was read) used to read
    as the current mode again (2026-10-20: crop, crop, stretch, stretch)."""
    for _ in range(tries):
        m = ctx.log.mark()
        ctx.tap_top("aspect", None)
        hit = ctx.log.wait(r"setAspect: (\S+)", 4, since=m, tag="VP")
        if hit:
            return hit
    return None


@check("aspect", "Aspect button cycles fit/crop/stretch/16:9/4:3 and returns to fit")
def aspect(ctx):
    ctx.play_clip(H264)
    seq = []
    imgs = {}
    try:
        for i in range(5):
            hit = _tap_aspect(ctx)
            need(hit, f"tap {i + 1} on the aspect button produced no setAspect log (3 tries)")
            seq.append(hit[1].group(1))
            ctx.sleep(0.8)
            imgs[seq[-1]] = ctx.shot(seq[-1].replace(":", "_"))
        exp = ["crop", "stretch", "16:9", "4:3", "fit"]
        if seq != exp:
            # the starting mode may have been remembered from an earlier run: accept any rotation of the cycle
            cyc = ["fit", "crop", "stretch", "16:9", "4:3"]
            k = cyc.index(seq[0]) if seq[0] in cyc else -1
            need(k >= 0 and seq == [cyc[(k + j) % 5] for j in range(5)], f"aspect order {seq}, expected {exp}")
        for name, im in imgs.items():
            need(imgx.nonblack_ratio(im) > 0.03, f"picture black in aspect mode {name}")
    finally:
        # return to fit
        for _ in range(5):
            if seq and seq[-1] == "fit":
                break
            try:
                hit = _tap_aspect(ctx)
                if hit:
                    seq.append(hit[1].group(1))
            except Exception:
                break
    need(seq and seq[-1] == "fit", f"could not return to fit (last mode {seq[-1] if seq else '?'})")
    return f"cycle {' -> '.join(seq)}; ends on fit; pictures not black"


# ---- playlist -------------------------------------------------------------------------------------
def _title_is(ctx, name):
    ns = ctx.controls_nodes()
    if not ns:
        return None
    return bool(uix.find(ns, contains=name[:18]))


@check("prev_next", "Previous/next buttons switch files and update the title")
def prev_next(ctx):
    order = ctx.order()
    _need_clip(ctx, H264)
    i = order.index(H264)
    need(0 < i < len(order) - 1, f"{H264} has no previous and next neighbour in {order}")
    prev, nxt = order[i - 1], order[i + 1]
    ctx.play_clip(H264)
    out = []
    for which, target in (("next", nxt), ("prev", H264), ("prev", prev), ("next", H264)):
        ns = None
        loaded = None
        for attempt in range(3):
            m = ctx.log.mark()
            ctx.tap_center_button(which, ns)
            loaded = ctx.log.wait(r"loadfile res=0 for .*" + re.escape(target[:14]), 6, since=m)
            if loaded:
                break
            ctx.sleep(0.5)
        need(loaded, f"{which}: {target} was not loaded")
        need(ctx.wait_restart(m, 30), f"{which}: {target} did not start playing")
        ctx.sleep(2)
        t = _title_is(ctx, target)
        for _ in range(2):  # 4K HEVC under load: the controls dump can come back before the overlay shows
            if t is not False:
                break
            ctx.sleep(1.5)
            t = _title_is(ctx, target)
        need(t is not False, f"{which}: title does not show {target}")
        out.append(f"{which}->{target[:14]}")
    ctx.need_moving("picture after navigation")
    return ", ".join(out) + "; titles updated"


@check("autoplay", "Auto-play next at end of file; last file stops")
def autoplay(ctx):
    order = ctx.order()
    _need_clip(ctx, MULTI)
    i = order.index(MULTI)
    res = []
    if i + 1 < len(order):
        nxt = order[i + 1]
        mark = ctx.play_clip(MULTI)
        ctx.seek_near_end(remaining_s=4.0)
        hit = ctx.log.wait(r"Auto-play: .* ended -> (.*)$", 30, since=mark)
        need(hit, "no 'Auto-play: ... ended ->' within 30 s of the end (autoPlayNext off?)")
        need(hit[1].group(1).endswith(nxt), f"auto-play went to {hit[1].group(1)}, expected {nxt}")
        need(ctx.log.wait(r"playback-restart", 30, since=hit[0].seq, tag="VP-Perf"), "next file did not start")
        ctx.sleep(2.5)
        ctx.need_moving("picture of the next file")
        res.append(f"{MULTI} -> {nxt}")
    else:
        res.append(f"({MULTI} is last)")
    last = order[-1]
    mark = ctx.play_clip(last)
    ctx.seek_near_end(remaining_s=3.0)
    hit = ctx.log.wait(r"Auto-play: no next file after", 30, since=mark)
    need(hit, f"last file {last}: no 'no next file' log after it ended")
    ctx.sleep(3)
    ctx.log.refresh()
    need(not ctx.log.find(r"Loading ", since=hit[0].seq), "something else was loaded after the last file")
    res.append(f"{last} stopped at the end")
    return "; ".join(res)


# ---- browser and settings ---------------------------------------------------------------------------
def _scroll_find(ctx, **kw):
    w, h = ctx.size()
    for _ in range(10):  # landscape shows few rows per screen
        ns = ctx.nodes()
        n = uix.find(ns, **kw)
        if n:
            return n, ns
        ctx.phone.swipe(w // 2, int(h * 0.75), w // 2, int(h * 0.3), 400)
        ctx.sleep(0.8)
    return None, []


@check("browser", "Browser: Continue Watching, thumbnails not black, open from list plays")
def browser(ctx):
    ctx.launch_main()
    ctx.sleep(4)
    ns = ctx.nodes()
    ctx.shot("home")
    need(uix.find(ns, text="Vibe Player") or uix.find(ns, contains="Vibe"), "browser title not found (permission dialog? app crashed?)")
    cw = uix.find(ns, text="Continue Watching")
    need(cw, "'Continue Watching' section missing (history should exist after the earlier checks)")
    folder, ns = _scroll_find(ctx, desc="folder_vibeplayer-test")
    if not folder:
        folder, ns = _scroll_find(ctx, text="vibeplayer-test")
    need(folder, "folder 'vibeplayer-test' not listed")
    ctx.phone.tap(*uix.center(folder))
    ctx.sleep(2.5)
    good = total = 0
    for attempt in range(6):
        ns = ctx.nodes()
        img = ctx.shot("videos")
        rows = [n for n in ns if (n.desc and n.desc.startswith("video_")) or (re.search(r"\.(mp4|mkv|webm|avi|mov)$", n.text) and n.bounds[0] > ctx.dpx(60))]
        need(rows, "no video rows in the folder list")
        good = total = 0
        thumbs = [t for t in ns if t.desc and t.desc.startswith("thumb_")]
        for n in rows:
            cy = (n.bounds[1] + n.bounds[3]) // 2
            half = ctx.dpx(36)
            # prefer the thumb_<name> hook (exact bounds; in landscape a left inset shifts the row)
            th = [t for t in thumbs if t.bounds[1] < cy < t.bounds[3]]
            try:
                if th:
                    b = th[0].bounds
                    crop = imgx.crop_box(img, (b[0] + 4, b[1] + 4, b[2] - 4, b[3] - 4))
                else:
                    crop = imgx.crop_box(img, (ctx.dpx(12), cy - half, n.bounds[0] - ctx.dpx(4), cy + half))
            except ValueError:
                continue
            mean, sd = imgx.luma_stats(crop)
            total += 1
            if mean > 20 and sd > 14:
                good += 1
        th_ok = len(ctx.log.find_all(r"thumb=ok name=", since=0, tag="VP"))
        if th_ok > 0 and good < th_ok:
            good = max(good, th_ok)
            total = max(total, good)
        if total and good >= max(1, int(total * 0.7)):
            break
        ctx.sleep(4)
    need(total and good >= max(1, int(total * 0.7)), f"thumbnails look black/blank: {good}/{total} rows have picture content")
    target, _ = _scroll_find(ctx, desc=f"video_{H264}")
    if not target:
        target, _ = _scroll_find(ctx, text=H264)
    need(target, f"{H264} row not visible")
    mark = ctx.log.mark()
    ctx.phone.tap(*uix.center(target))
    need(ctx.wait_restart(mark, 25), "tapping a list entry did not start playback")
    ctx.sleep(2)
    ctx.need_moving("picture opened from the list")
    return f"Continue Watching present, thumbnails with content {good}/{total}, open from list plays"


@check("settings_version", "Settings screen shows the version")
def settings_version(ctx):
    ver, dbg = ctx.phone.app_info()
    ctx.launch_main()
    ctx.sleep(4)
    ns = ctx.nodes()
    gear = uix.find(ns, text="⚙")
    need(gear, "settings gear not found on the folder list")
    # tap the gear's left part: a system edge-panel handle overlaps its right half on this phone
    g = gear.bounds
    ctx.phone.tap(g[0] + (g[2] - g[0]) // 4, (g[1] + g[3]) // 2)
    ctx.sleep(1.5)
    n, ns = _scroll_find(ctx, regex=r"^Vibe Player \d")
    ctx.shot("settings")
    need(n, "no 'Vibe Player <version>' line on the settings screen")
    need(ver == "?" or ver in n.text, f"settings shows '{n.text}', package versionName is {ver}")
    return f"'{n.text}' (installed versionName {ver}, {'debug' if dbg else 'release'} build)"


# ---- stall counter and crash scan ----------------------------------------------------------------
@check("stall", "Stall counter: cold opens of ichiban.mp4 must reach playback-restart within 8 s")
def stall(ctx):
    n = getattr(ctx, "stall_opens", 5)
    _need_clip(ctx, H264)
    stalls, times = 0, []
    for i in range(n):
        t0 = time.monotonic()
        mark = ctx.launch_clip(H264)
        hit = ctx.wait_restart(mark, 8)
        if hit:
            times.append(time.monotonic() - t0)
        else:
            stalls += 1
            ctx.shot(f"stall{i + 1}")
        ctx.sleep(1.0)
    wd = ctx.log.find(r"B-08 watchdog")
    need(stalls == 0, f"{stalls}/{n} cold opens had no playback-restart within 8 s")
    return f"0/{n} stalls (restart seen after {min(times):.1f}-{max(times):.1f} s wall time incl. launch)"


@check("no_fatal", "No FATAL EXCEPTION / ANR for com.vibeplayer during the run")
def no_fatal(ctx):
    ctx.scan_crash()
    need(not ctx.crashes, "; ".join(ctx.crashes[:3]))
    return "no FATAL EXCEPTION, ANR or native crash in logcat/crash buffer during the run"


MANUAL = [
    ("pinch", "Pinch zoom", "Play any video, pinch two fingers apart: picture zooms smoothly around the pinch centre, 'Reset Zoom (N%)' chip appears; pinch back and tap the chip to reset."),
    ("haptics", "Haptic ticks", "Play a video: feel a short tick on double-tap skip, on each swipe-seek step start and when the volume/brightness swipe begins."),
    ("audio", "Audio quality", "Play multi_track.mkv and switch AC3/E-AC3/DTS/TrueHD: tones are clean, no crackle, A/V in sync; play ichiban.mp4 for 30 s with headphones: no dropouts."),
]


# P4-15 / P11 / P8-02 checks register themselves (after MANUAL exists).
from . import checks_p11  # noqa: E402,F401
# no_fatal scans the whole run: keep it last.
CHECKS.sort(key=lambda c: c[0] == "no_fatal")
