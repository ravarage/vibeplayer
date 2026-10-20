"""Device checks for P4-15, P11 and the P8-02 shader fix (written without the phone; first run may need tuning).

The clips live in their own folder so the main test folder's order (prev/next, autoplay) is unchanged;
tools/phone_check.sh generates and pushes them.
"""
import re

from . import uix
from .checks import check, MANUAL
from .ctx import Fail, Skip, need, PLAYER, file_uri

P11_DIR = "/sdcard/Movies/vibeplayer-p11"
CH = "p11_chapters.mkv"  # 4 min; chapters Prologue 0 s, Opening 30 s, Part A 120 s, Ending 210 s
PART_A_MS = 120_000


def _need_p11(ctx, name=CH):
    if name not in ctx.phone.listing(P11_DIR):
        raise Skip(f"{name} not in {P11_DIR}: run tools/phone_check.sh, it creates and pushes the P11 clips")


def _play_p11(ctx, name=CH, extras="", timeout=25, pause=False):
    _need_p11(ctx, name)
    ctx.ctrl_pinned = False  # a fresh player is playing (as Ctx.launch_clip resets it)
    ctx.trace("wake")
    ctx.phone.wake()
    ctx.trace("stop app")
    ctx.stop_app()
    ctx.trace("clear log")
    ctx.clear_log()
    mark = ctx.log.mark()
    ctx.trace(f"start {name} {extras}".strip())
    ctx.phone.start_activity(PLAYER, data=file_uri(f"{P11_DIR}/{name}"), extras=extras)
    need(ctx.wait_restart(mark, timeout), f"no playback-restart within {timeout} s of launching {name}")
    ctx.trace(f"playing (log buffer {ctx.log.raw_count} lines)")
    ctx.sleep(2.0)
    if pause:
        _pause_for_ui(ctx)
    return mark


def _pause_for_ui(ctx):
    """Pauses with the centre button. While playing, the controls hide 3 s after a tap and the picture keeps
    changing, so a ~2 s uiautomator dump often misses them; paused, they stay up and the screen is still."""
    m = ctx.log.mark()
    w, h = ctx.size()
    ctx.trace(f"tap centre {w // 2},{h // 2} to pause (screen {w}x{h})")
    ctx.tap_center_button("pause", None)
    ctx.trace("tapped; waiting for togglePause")
    hit = ctx.log.wait(r"togglePause", 6, since=m, tag="VP")
    ctx.trace(f"togglePause {'seen' if hit else 'NOT seen'}; gesture lines: "
              + "; ".join(l.msg for l, _ in ctx.log.find_all(r"Gesture detected", m))[:300])
    ctx.shot("pause_attempt")
    need(hit, "could not pause with the centre button (no togglePause)")
    ctx.ctrl_pinned = True
    ctx.sleep(0.8)


def _tap_desc(ctx, desc, tries=3):
    for _ in range(tries):
        ctx.trace(f"dump for '{desc}'")
        ns = ctx.nodes()
        ctx.trace(f"dump done ({len(ns)} nodes)")
        n = uix.find(ns, desc=desc) if ns else None
        if n:
            ctx.phone.tap(*uix.center(n))
            return True
        ctx.sleep(0.8)
    return False


def _open_panel(ctx, button_desc, title, label):
    """Shows the controls, taps a top-bar button and waits for the panel title; saves a screenshot."""
    for _ in range(3):
        ns = ctx.controls_nodes()
        n = uix.find(ns, desc=button_desc) if ns else None
        if n:
            ctx.phone.tap(*uix.center(n))
            ctx.sleep(1.2)
            if uix.find(ctx.nodes(), text=title):
                ctx.shot(label)
                return
        ctx.sleep(0.8)
    raise Fail(f"panel '{title}' did not open from the '{button_desc}' button")


def _close_panel(ctx):
    ctx.phone.key("KEYCODE_BACK")
    ctx.sleep(0.6)


def _pos_ms(ctx, since):
    v = ctx.last_value(r"pos_ms=(\d+)", since, tag="VP-Check")
    return int(v) if v else None


@check("p11_chapters", "Chapters: list read from the file, panel lists them, tapping one seeks there (P4-15)")
def p11_chapters(ctx):
    m = _play_p11(ctx, pause=True)
    need(ctx.log.wait(r"Updated chapters \(4\)", 8, since=m), "no 'Updated chapters (4)' log for the 4-chapter clip")
    _open_panel(ctx, "chapters", "Chapters", "chapters_panel")
    m2 = ctx.log.mark()  # before the tap: the log line can come before the next poll
    need(_tap_desc(ctx, "chapter_2"), "row chapter_2 (Part A) not found in the Chapters panel")
    hit = ctx.log.wait(r"seekToChapter 2 res=(-?\d+)", 5, since=m2)
    need(hit, "no 'seekToChapter 2' log after tapping Part A")
    need(hit[1].group(1) == "0", f"mpv refused the chapter seek (res={hit[1].group(1)})")
    ctx.sleep(1.0)
    ctx.shot("after_seek")
    # play again: the position is reported while playing
    _tap_desc(ctx, "play_pause")
    ctx.sleep(2.5)
    pos = _pos_ms(ctx, m2)
    need(pos is not None and PART_A_MS - 1_000 <= pos <= PART_A_MS + 5_000, f"position after the seek is {pos} ms, expected about {PART_A_MS}")
    return f"4 chapters read; Part A tap seeked to {pos} ms (screenshots: chapters_panel, after_seek: ticks on the seek bar, chapter name in the top bar)"


@check("p11_skip", "Skip opening: button appears in the Opening chapter and jumps to Part A (P11-01)")
def p11_skip(ctx):
    _play_p11(ctx, pause=True)
    _open_panel(ctx, "chapters", "Chapters", "chapters_panel")
    need(_tap_desc(ctx, "chapter_1"), "row chapter_1 (Opening) not found")
    ctx.sleep(2.0)
    m = ctx.log.mark()
    ctx.shot("skip_button")
    need(_tap_desc(ctx, "skip_chapter"), "no skip_chapter button inside the Opening chapter")
    hit = ctx.log.wait(rf"skipChapter 1 -> (\w+) res=(-?\d+)", 5, since=m)
    need(hit, "no 'skipChapter 1' log after tapping Skip opening")
    need(hit[1].group(1) == str(PART_A_MS) and hit[1].group(2) == "0",
         f"skip went to {hit[1].group(1)} with res={hit[1].group(2)}, expected {PART_A_MS} and 0")
    return "Skip opening shown in the Opening chapter, jumped to Part A (screenshot skip_button)"


@check("p11_more", "More panel: A-B repeat sets and clears, loop toggles (P11-05)")
def p11_more(ctx):
    _play_p11(ctx, pause=True)
    _open_panel(ctx, "more", "More", "more_panel")
    m = ctx.log.mark()
    need(_tap_desc(ctx, "ab_repeat"), "ab_repeat row not found")
    need(ctx.log.wait(r"A-B repeat: a=\d+ b=null", 4, since=m), "first A-B tap did not set A")
    # Paused, B would land on A (ignored by design): close the panel, play ~2 s, pause, reopen.
    _close_panel(ctx)
    ns = ctx.nodes()
    pp = uix.find(ns, desc="play_pause") if ns else None
    need(pp, "play_pause not found to move on between A and B")
    ctx.phone.tap(*uix.center(pp))
    ctx.sleep(2.0)
    ctx.phone.tap(*uix.center(pp))  # same place, within the 3 s before the controls hide
    ctx.sleep(0.8)
    _open_panel(ctx, "more", "More", "more_panel_b")
    need(_tap_desc(ctx, "ab_repeat"), "ab_repeat row not found for B")
    hit = ctx.log.wait(r"A-B repeat: a=(\d+) b=(\d+)", 5, since=m)
    need(hit, "no 'A-B repeat: a=.. b=..' after two taps")
    a, b = int(hit[1].group(1)), int(hit[1].group(2))
    need(b > a, f"B ({b}) not after A ({a})")
    m = ctx.log.mark()
    _tap_desc(ctx, "ab_repeat")
    need(ctx.log.wait(r"A-B repeat: a=null b=null", 5, since=m), "third tap did not clear A-B repeat")
    m = ctx.log.mark()
    _tap_desc(ctx, "loop_file")
    need(ctx.log.wait(r"loop-file true", 5, since=m), "loop toggle did not log 'loop-file true'")
    m = ctx.log.mark()
    _tap_desc(ctx, "loop_file")
    need(ctx.log.wait(r"loop-file false", 5, since=m), "loop toggle back did not log 'loop-file false'")
    _close_panel(ctx)
    return f"A-B {a}..{b} ms set and cleared, loop on and off (screenshot more_panel)"


@check("p11_frame_step", "Frame step buttons while paused (P11-06)")
def p11_frame_step(ctx):
    _play_p11(ctx, pause=True)
    m = ctx.log.mark()
    need(_tap_desc(ctx, "frame_forward"), "frame_forward button not shown while paused")
    need(ctx.log.wait(r"frame step forward=true res=0", 4, since=m), "no 'frame step forward=true res=0'")
    m = ctx.log.mark()
    need(_tap_desc(ctx, "frame_back"), "frame_back button not shown while paused")
    need(ctx.log.wait(r"frame step forward=false res=0", 6, since=m), "no 'frame step forward=false res=0'")
    _tap_desc(ctx, "play_pause")
    return "one frame forward and back while paused"


@check("p11_night_mode", "Night mode chip in the audio panel switches the dynaudnorm filter on and off (P11-03)")
def p11_night_mode(ctx):
    _play_p11(ctx, pause=True)
    _open_panel(ctx, "audio", "Audio Tracks", "audio_panel")
    m = ctx.log.mark()
    need(_tap_desc(ctx, "night_mode"), "night_mode chip not found")
    hit = ctx.log.wait(r"setNightMode (true|false) res=(-?\d+)", 5, since=m)
    need(hit, "no setNightMode log")
    first, res = hit[1].group(1), hit[1].group(2)
    ctx.sleep(2.0)
    ctx.log.refresh()
    errs = ctx.log.find_all(r"(?i)dynaudnorm.*(error|fail)|af.*(error|fail).*lavfi", m)
    _tap_desc(ctx, "night_mode")  # back to how it was
    ctx.log.wait(r"setNightMode (true|false)", 5, since=m)
    _close_panel(ctx)
    need(res == "0", f"mpv refused af (res={res})")
    need(not errs, "mpv logged an error for the filter: " + errs[0][1].group(0) if errs else "")
    return f"night mode {first} applied (res=0) and restored; listen for the effect by hand"


@check("p11_audio_session", "Audio session announced to effect apps (ViPER4Android) when playback starts (P11-09)")
def p11_audio_session(ctx):
    m = _play_p11(ctx)
    hit = ctx.log.wait(r"Audio effect session (\d+): OPEN_AUDIO_EFFECT_CONTROL_SESSION", 6, since=m)
    need(hit, "no 'Audio effect session N: OPEN_AUDIO_EFFECT_CONTROL_SESSION' log after playback started")
    return f"session {hit[1].group(1)} announced; with ViPER4Android installed, check it lists Vibe Player"


@check("p11_anime4k_gles", "Anime4K A on OpenGL ES (quality mode): every pass compiles, picture moves (P8-02)")
def p11_anime4k_gles(ctx):
    m = _play_p11(ctx, extras="--es mode quality --es renderer gles --es shader a", timeout=30)
    applied = ctx.log.wait(r"Applied shader preset ANIME4K_A .*renderer=(\w+).*", 10, since=m)
    need(applied, "no 'Applied shader preset ANIME4K_A' log (the startup line is preset NONE)")
    need("Clamp_Highlights" in applied[1].group(0), "Clamp_Highlights is not in the applied chain")
    need(applied[1].group(1).upper() == "GLES", f"renderer is {applied[1].group(1)}, expected GLES")
    ctx.sleep(4.0)
    ctx.log.refresh()
    errs = ctx.log.find_all(r"(?i)no matching overloaded function|shader compil\w* fail|failed to compile|disabl\w+ hook", m)
    ctx.need_moving("picture with Anime4K A on GLES")
    ctx.shot("anime4k_gles")
    need(not errs, "shader error on GLES: " + errs[0][1].group(0) if errs else "")
    return "all Anime4K A passes compiled on GLES, picture moves (screenshot anime4k_gles)"


@check("p11_panels_look", "Screenshots of the compact panels for a human look (P11-08)")
def p11_panels_look(ctx):
    _play_p11(ctx, pause=True)
    _open_panel(ctx, "sub", "Subtitles", "subtitle_panel")
    _close_panel(ctx)
    _open_panel(ctx, "speed", "Playback Speed", "speed_panel")
    _close_panel(ctx)
    return "screenshots subtitle_panel and speed_panel saved: check they sit on the right and leave the video visible"


MANUAL.extend([
    ("p11_boost", "Volume boost (P11-02)",
     "Settings > Volume boost > Up to 200 %. In a video, swipe up on the right past 100 %: the indicator turns orange and goes to 200, "
     "audibly louder. Swipe down: back to 100, then the media volume drops. Set Off while boosted: volume returns to 100 %."),
    ("p11_subs", "Subtitle folders and encoding (P11-04)",
     "Settings > Subtitle folders > pick Movies/vibeplayer-p11. Open p11_chapters.mkv: 'p11_chapters.srt' is in the subtitle panel without loading it. "
     "Open p11_arabic.mkv with Settings > Subtitle encoding = Arabic (Windows-1256): its subtitle shows Arabic letters, not garbage."),
    ("p11_search", "Search (P11-07)",
     "Folder screen: tap 🔍, type 'p11 chapters': p11_chapters.mkv is listed; back closes the search. In the WhatsApp Video folder, typing stays smooth."),
    ("p11_sleep_rotate", "Sleep timer and Rotate (P11-05)",
     "More > Sleep timer 15 min with the screen off: playback pauses at 15 min. More > 'End of video': pauses at the end, no next file. "
     "More > Rotate turns a landscape video to portrait and back to Auto."),
    ("p11_portrait", "Portrait top bar (P11-08)",
     "Rotate to Portrait: the top bar shows back, title, 🎵, 💬, ⋮, 🔒 without overlap; speed, chapters, aspect, mini player and the mode are chips at the top of More."),
    ("p11_viper", "ViPER4Android (P11-09)",
     "With ViPER4Android installed, play a video: ViPER lists Vibe Player's session and its effects are audible."),
    ("p11_autoskip", "Auto-skip (P11-01)",
     "Settings > Auto-skip openings and endings on. Open p11_chapters.mkv from the start: at 0:30 it jumps to 2:00 with 'Skipped opening'; seeking back into the opening plays it."),
])
