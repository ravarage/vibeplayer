"""Device checks for P12 (written without the phone; the first run may need tuning).

The clips live in /sdcard/Movies/vibeplayer-p12; tools/shower_check.sh generates and pushes them, plus a fresh
copy p12_fresh_<stamp>.mkv per run (a file without history, for the folder memory checks).
"""
import re

from . import uix
from .checks import check, MANUAL
from .checks_p11 import _tap_desc, _open_panel, _close_panel, _pause_for_ui
from .ctx import Fail, Skip, need, PLAYER, file_uri

P12_DIR = "/sdcard/Movies/vibeplayer-p12"
EP1 = "p12_ep01.mkv"      # 60 s, audio eng (default) + jpn, embedded SRT (a line every 5 s), chapters
EP2 = "p12_ep02.mkv"      # the same, the next file in the folder
PORTRAIT = "p12_portrait.mp4"  # 20 s, stored 1280x720 with rotate=90 (filmed upright)
EP_MS = 60_000
SENSOR_PORTRAIT = 7


def _need(ctx, name):
    if name not in ctx.phone.listing(P12_DIR):
        raise Skip(f"{name} not in {P12_DIR}: run tools/shower_check.sh, it creates and pushes the P12 clips")


def _fresh(ctx):
    files = sorted(f for f in ctx.phone.listing(P12_DIR) if re.match(r"p12_fresh_\d+\.mkv$", f))
    if not files:
        raise Skip(f"no p12_fresh_*.mkv in {P12_DIR}: tools/shower_check.sh pushes one per run")
    return files[-1]


def _play(ctx, name, extras="", timeout=25, pause=False, settle=2.0):
    _need(ctx, name)
    ctx.ctrl_pinned = False
    ctx.phone.wake()
    ctx.stop_app()
    ctx.clear_log()
    mark = ctx.log.mark()
    ctx.trace(f"start {name} {extras}".strip())
    ctx.phone.start_activity(PLAYER, data=file_uri(f"{P12_DIR}/{name}"), extras=extras)
    need(ctx.wait_restart(mark, timeout), f"no playback-restart within {timeout} s of launching {name}")
    ctx.sleep(settle)
    if pause:
        _pause_for_ui(ctx)
    return mark


def _debug(ctx, action):
    ctx.trace(f"debug action {action}")
    ctx.phone.broadcast(extras=f"--es action {action}")


def _find_desc(ctx, desc, tries=4, gap=0.8):
    for _ in range(tries):
        ns = ctx.nodes()
        n = uix.find(ns, desc=desc) if ns else None
        if n:
            return n
        ctx.sleep(gap)
    return None


def _open_more(ctx):
    _open_panel(ctx, "more", "More", "more_panel")


@check("p12_rotated_clip", "A clip filmed upright (stored landscape, rotate=90) opens in portrait (P12, auto-rotate)")
def p12_rotated_clip(ctx):
    m = _play(ctx, PORTRAIT)
    ctx.sleep(2.0)
    v = ctx.last_value(r"Orienting \(AUTO\): orientation=(\d+)", m)
    need(v is not None, "no 'Orienting (AUTO)' log for the rotated clip")
    ctx.shot("portrait")
    need(int(v) == SENSOR_PORTRAIT, f"last orientation {v}, expected {SENSOR_PORTRAIT} (sensor portrait)")
    return "rotate=90 clip oriented to portrait (screenshot portrait: picture upright, fills the width)"


@check("p12_resume_chip", "Reopening a file shows 'Resumed at ... Start over'; Start over goes to 0 (P12-01)")
def p12_resume_chip(ctx):
    m = _play(ctx, EP1)
    _debug(ctx, "seek:30000")
    hit = None
    for _ in range(4):
        hit = ctx.log.wait(r"Saved position (\d+) ms", 15, since=hit[0].seq + 1 if hit else m)
        if hit and int(hit[1].group(1)) >= 20_000:
            break
    need(hit and int(hit[1].group(1)) >= 20_000, "no periodic 'Saved position' past 20 s after seeking to 30 s")
    ctx.stop_app()
    m2 = _play(ctx, EP1, settle=0)  # the chip shows for 8 s from the open; a dump takes ~2 s
    need(ctx.log.find(r"Resume: resuming from (\d+) ms", m2), "no 'Resume: resuming from' after reopening")
    chip = _find_desc(ctx, "start_over", tries=2, gap=0.3)
    ctx.shot("resume_chip")
    need(chip, "no Start over chip after the resumed open (it shows for 8 s)")
    m3 = ctx.log.mark()
    ctx.phone.tap(*uix.center(chip))
    need(ctx.log.wait(r"Resume: start over", 5, since=m3), "tapping Start over logged no 'Resume: start over'")
    ctx.sleep(2.5)
    pos = ctx.last_value(r"pos_ms=(\d+)", m3, tag="VP-Check")
    need(pos is None or int(pos) < 10_000, f"position after Start over is {pos} ms")
    return f"chip shown on the resumed open; Start over went back to {pos or '?'} ms (screenshot resume_chip)"


@check("p12_up_next", "End of a file: 'Up next' card; Cancel stays; the countdown then plays the next file (P12-02)")
def p12_up_next(ctx):
    _need(ctx, EP2)
    m = _play(ctx, EP1)
    _debug(ctx, f"seek:{EP_MS - 3000}")
    hit = ctx.log.wait(r"Up next: (\S+) in (\d+) s", 12, since=m)
    need(hit, "no 'Up next' at the end of the file (Settings > Next video countdown off, or Auto-play next off?)")
    need(hit[1].group(1).endswith(EP2), f"up next is {hit[1].group(1)}, expected {EP2}")
    card = _find_desc(ctx, "up_next_cancel", tries=3, gap=0.4)
    ctx.shot("up_next")
    need(card, "no Cancel button on the Up next card")
    m2 = ctx.log.mark()
    ctx.phone.tap(*uix.center(card))
    need(ctx.log.wait(r"Up next cancelled", 4, since=m2), "Cancel logged no 'Up next cancelled'")
    ctx.sleep(1.0)
    need(not ctx.log.find(r"Auto-play: .* ended ->", m2), "the next file started although Cancel was tapped")
    # again, this time let the countdown run out
    m3 = ctx.log.mark()
    _debug(ctx, f"seek:{EP_MS - 3000}")
    _debug(ctx, "play")
    need(ctx.log.wait(r"Up next: ", 12, since=m3), "no second 'Up next' after seeking to the end again")
    adv = ctx.log.wait(r"Auto-play: .* ended -> (.*)$", 20, since=m3)
    need(adv and adv[1].group(1).endswith(EP2), "the countdown did not start the next file")
    need(ctx.wait_restart(adv[0].seq, 20), "next file did not start playing")
    return f"Up next card shown (screenshot up_next), Cancel kept the file, countdown {hit[1].group(2)} s then played {EP2}"


@check("p12_sub_line", "Subtitles panel: Previous / Next line jump between subtitle lines (P12)")
def p12_sub_line(ctx):
    _play(ctx, EP1, pause=True)
    _open_panel(ctx, "sub", "Subtitles", "subtitle_panel_lines")
    out = []
    for desc, nxt in (("sub_line_next", "true"), ("sub_line_prev", "false")):
        m = ctx.log.mark()
        need(_tap_desc(ctx, desc), f"no '{desc}' chip (is a subtitle on?)")
        hit = ctx.log.wait(rf"sub-seek next={nxt} res=(-?\d+)", 5, since=m)
        need(hit, f"no 'sub-seek next={nxt}' log")
        need(hit[1].group(1) == "0", f"mpv refused sub-seek next={nxt} (res={hit[1].group(1)})")
        out.append(desc)
        ctx.sleep(1.0)
    _close_panel(ctx)
    return "next and previous line accepted by mpv"


@check("p12_bookmarks", "More > Bookmarks: add one here, it is listed, delete removes it (P12-03)")
def p12_bookmarks(ctx):
    _play(ctx, EP1, pause=True)
    _open_more(ctx)
    need(_tap_desc(ctx, "bookmarks"), "no Bookmarks row in the More panel")
    ctx.sleep(1.0)
    m = ctx.log.mark()
    need(_tap_desc(ctx, "bookmark_add"), "no '+ Here' chip in the Bookmarks panel")
    hit = ctx.log.wait(r"Bookmark (\d+) added at (\d+) ms", 5, since=m)
    need(hit, "no 'Bookmark ... added' log")
    bid = hit[1].group(1)
    ctx.sleep(1.0)
    ctx.shot("bookmarks_panel")
    need(_find_desc(ctx, f"bookmark_{bid}"), f"bookmark {bid} not listed in the panel")
    m2 = ctx.log.mark()
    need(_tap_desc(ctx, f"bookmark_delete_{bid}"), "no delete (x) for the new bookmark")
    need(ctx.log.wait(rf"Bookmark {bid} deleted", 5, since=m2), "delete logged nothing")
    _close_panel(ctx)
    return f"bookmark {bid} added at {hit[1].group(2)} ms, listed and deleted (screenshot bookmarks_panel)"


@check("p12_folder_speed", "Whole folder: 'Use 1.5x for all' gives a file without its own speed 1.5x (P12-04)")
def p12_folder_speed(ctx):
    fresh = _fresh(ctx)
    _play(ctx, EP1, pause=True)
    m = ctx.log.mark()
    _debug(ctx, "speed:1.5")
    need(ctx.log.wait(r"Persisted speed=1\.5", 6, since=m), "debug speed 1.5 not persisted")
    try:
        _open_more(ctx)
        m2 = ctx.log.mark()
        need(_tap_desc(ctx, "folder_speed_set"), "no 'Use 1.5x for all' chip in More > Whole folder")
        need(ctx.log.wait(r"Folder settings for .* speed=1\.5", 5, since=m2), "folder speed not saved")
        _close_panel(ctx)
        m3 = _play(ctx, fresh)
        need(ctx.log.find(r"Speed 1\.5 from folder", m3), f"{fresh} did not take the folder speed")
        return f"{fresh} opened at the folder speed 1.5x"
    finally:
        # back to 1x for the folder and for EP1
        try:
            _play(ctx, EP1, pause=True)
            _debug(ctx, "speed:1.0")
            ctx.sleep(1.0)
            _open_more(ctx)
            _tap_desc(ctx, "folder_speed_clear")
            ctx.sleep(1.0)
            _close_panel(ctx)
        except Exception as e:  # cleanup only
            ctx.trace(f"cleanup failed: {e}")


@check("p12_shader_switch", "More > Shaders: pick GSR while playing, Compare bypasses it, the setting is put back (P12-08)")
def p12_shader_switch(ctx):
    m = _play(ctx, EP1, pause=True)
    first = ctx.log.find(r"Applied shader preset (\w+) \(", m)
    before = first[1].group(1) if first else "NONE"
    try:
        _open_more(ctx)
        m2 = ctx.log.mark()
        need(_tap_desc(ctx, "shader_snapdragon_gsr"), "no GSR chip in More > Shaders")
        need(ctx.log.wait(r"Applied shader preset SNAPDRAGON_GSR \(", 6, since=m2), "picking GSR applied nothing")
        ctx.sleep(1.0)
        m3 = ctx.log.mark()
        if _find_desc(ctx, "shader_compare", tries=2, gap=0.4):
            _tap_desc(ctx, "shader_compare")
            need(ctx.log.wait(r"Shader compare: original=true", 5, since=m3), "Compare logged nothing")
            hit = ctx.log.wait(r"Applied shader preset SNAPDRAGON_GSR \([^)]*\): ?(\S*)", 5, since=m3)
            need(hit and hit[1].group(1) == "", "Compare did not clear the shader chain")
            compared = "Compare bypassed it"
        else:
            compared = "no Compare (Efficient mode)"
        _close_panel(ctx)
        return f"GSR applied from the player; {compared}; setting put back to {before}"
    finally:
        try:
            _open_more(ctx)
            _tap_desc(ctx, f"shader_{before.lower()}")
            ctx.sleep(1.0)
            _close_panel(ctx)
        except Exception as e:
            ctx.trace(f"cleanup failed: {e}")


@check("p12_folder_lang", "Audio language chosen in one file of a folder is used for a new file there (was the skipped folder_track_memory)")
def p12_folder_lang(ctx):
    fresh = _fresh(ctx)
    _play(ctx, EP1, pause=True)
    _open_panel(ctx, "audio", "Audio Tracks", "audio_panel")
    ns = ctx.nodes()
    jpn = uix.find(ns, text="Japanese")
    need(jpn, "no 'Japanese' audio track row in the panel")
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(jpn))
    hit = ctx.log.wait(r"Persisted folder audio: lang=(\S+), title", 6, since=m)
    need(hit, "no 'Persisted folder audio' log")
    lang = hit[1].group(1)
    try:
        m2 = _play(ctx, fresh)
        ctx.sleep(1.0)
        ts = ctx.log.find_all(r"id=(\d+) type=audio lang=(\S+) ", m2, tag="VP-Track")
        audio = {int(x[1].group(1)): x[1].group(2) for x in ts}
        aid = ctx.last_value(r"aid=(\S+)", m2, tag="VP-Check") or ctx.last_value(r"Updated tracks \(\d+\): aid=(\S+), sid=", m2)
        need(aid and aid.isdigit(), f"no audio track id for {fresh}")
        need(audio.get(int(aid)) == lang, f"{fresh} plays audio {aid} ({audio.get(int(aid))}), the folder choice was '{lang}'")
        return f"'{lang}' chosen in {EP1} was used for the new file {fresh}"
    finally:
        try:  # back to English for the folder
            _play(ctx, EP1, pause=True)
            _open_panel(ctx, "audio", "Audio Tracks", "audio_panel_reset")
            eng = uix.find(ctx.nodes(), text="English")
            if eng:
                ctx.phone.tap(*uix.center(eng))
                ctx.sleep(1.0)
        except Exception as e:
            ctx.trace(f"cleanup failed: {e}")


MANUAL.extend([
    ("p12_cards_look", "Resume chip and Up next card (P12-01, P12-02)",
     "Open a half-watched video: 'Resumed at ...' with Start over shows bottom left for 8 s. Let an episode end: the "
     "Up next card counts down 5 s at the bottom right; Play now starts the next one at once."),
    ("p12_browser_tools", "Hide folder, rename, delete (P12-05, P12-06)",
     "Long-press the vibeplayer-p12 folder > Hide: it leaves the list and search; Settings > Library > Hidden folders > Show "
     "brings it back. Long-press p12_fresh_*.mkv > Rename to 'renamed': Android asks, the list shows renamed.mkv. "
     "Long-press it > Delete: Android asks, it is gone."),
    ("p12_backup", "Backup and restore (P12-10)",
     "Settings > Library > Back up: save the zip. Change a setting and watch a minute of a video. Restore from backup > "
     "pick the zip > Restore: the app restarts with the old setting and position."),
    ("p12_stats", "Stats overlay rows (P12-09)",
     "Open the debug overlay on an H.264 and an HEVC 10-bit clip: VIDEO, PIXFMT, COLOR, SHADERS, A/V SYNC and CACHE read sensibly."),
    ("p12_portrait_look", "Upright phone clip (auto-rotate)",
     "Open a video you filmed upright with the phone camera: the player turns to portrait and the picture is upright."),
])
