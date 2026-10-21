"""Device checks for P13 network playback (written without the phone; the first run may need tuning).

tools/network_check.sh serves test clips from the PC (tools/net_test_server.py, with seeking) and makes them
reachable on the phone as http://127.0.0.1:8765 through `adb reverse`; without it these checks are skipped.
"""
from . import uix
from .checks import check, MANUAL
from .checks_p11 import _tap_desc
from .checks_p12 import _find_desc
from .ctx import Skip, need, PLAYER

BASE = "http://127.0.0.1:8765"


def _server_up(ctx):
    # The script writes this marker on the phone after `adb reverse`; the check itself has no network tool.
    if "vp_net_check" not in ctx.phone.listing("/data/local/tmp/"):
        raise Skip("no test server: run tools/network_check.sh (it serves the clips and sets up adb reverse)")


def _play_url(ctx, url, timeout=30):
    _server_up(ctx)
    ctx.ctrl_pinned = False
    ctx.phone.wake()
    ctx.stop_app()
    ctx.clear_log()
    m = ctx.log.mark()
    ctx.trace(f"start {url}")
    ctx.phone.start_activity(PLAYER, data=url)
    need(ctx.wait_restart(m, timeout), f"no playback-restart within {timeout} s of opening {url}")
    ctx.sleep(2.0)
    return m


def _open_link_in_network_screen(ctx, url):
    """Library > 🌐 > Open a link: types [url] and taps Open."""
    _server_up(ctx)
    ctx.launch_main()
    ctx.sleep(2.5)
    need(_tap_desc(ctx, "network"), "no 🌐 Network button in the library top bar")
    ctx.sleep(1.5)
    field = _find_desc(ctx, "net_link")
    need(field, "no link field on the Network screen (local network permission dialog in the way?)")
    ctx.phone.tap(*uix.center(field))
    ctx.sleep(0.6)
    ctx.phone.text(url)
    ctx.sleep(0.6)
    need(_tap_desc(ctx, "net_link_open"), "no Open button next to the link field")
    ctx.sleep(2.5)


@check("p13_http_link", "A video link (http) plays, with the network cache, and seeking works (Range requests) (P13)")
def p13_http_link(ctx):
    url = f"{BASE}/p13_ep01.mkv"
    m = _play_url(ctx, url)
    opts = ctx.log.find(r"Loading http://127\.0\.0\.1:8765/p13_ep01\.mkv with options: (.*)$", m)
    need(opts and "cache=yes" in opts[1].group(1) and "stream-lavf-o=" in opts[1].group(1),
         "the http load did not get the network options (cache, reconnect)")
    m2 = ctx.log.mark()
    ctx.phone.broadcast(extras="--es action seek:40000")
    ctx.sleep(3.0)
    pos = ctx.last_value(r"pos_ms=(\d+)", m2, tag="VP-Check")
    need(pos is None or int(pos) >= 38_000, f"after seeking to 40 s the position is {pos} ms (no Range support?)")
    ctx.need_moving("http picture")
    return f"played over http with the network options; seek to 40 s landed at {pos or '?'} ms"


@check("p13_hls", "An HLS stream (.m3u8, as IPTV and many sites serve) plays (P13)")
def p13_hls(ctx):
    _play_url(ctx, f"{BASE}/hls/stream.m3u8")
    ctx.need_moving("HLS picture")
    return "HLS stream plays"


@check("p13_folder_next", "Network > web folder link: the folder lists, a file plays, next plays the following file, its subtitle is offered (P13)")
def p13_folder_next(ctx):
    _open_link_in_network_screen(ctx, f"{BASE}/")
    ctx.shot("folder_listing")
    row = _find_desc(ctx, "net_row_p13_ep01.mkv")
    need(row, "p13_ep01.mkv not listed in the web folder")
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(row))
    need(ctx.wait_restart(m, 30), "tapping p13_ep01.mkv did not start playback")
    sub = ctx.log.wait(r"Network subtitle p13_ep01\.srt added", 15, since=m)
    ctx.sleep(1.5)
    m2 = ctx.log.mark()
    ctx.phone.broadcast(extras="--es action next")
    need(ctx.log.wait(r"Loading http://127\.0\.0\.1:8765/p13_ep02\.mkv", 15, since=m2), "next did not open p13_ep02.mkv from the same folder")
    need(ctx.wait_restart(m2, 30), "p13_ep02.mkv did not start")
    return "web folder listed; ep01 played" + (" with its subtitle" if sub else " (subtitle NOT offered)") + "; next opened ep02"


@check("p13_m3u_channels", "Network > M3U link: groups and channels list, a channel plays with its user agent (P13)")
def p13_m3u_channels(ctx):
    _open_link_in_network_screen(ctx, f"{BASE}/list.m3u")
    ctx.shot("m3u_groups")
    need(_tap_desc(ctx, "net_row_All channels (2)"), "no 'All channels (2)' row for the 2-group playlist")
    ctx.sleep(1.5)
    row = _find_desc(ctx, "net_row_Channel Two")
    need(row, "no 'Channel Two' in the channel list")
    m = ctx.log.mark()
    ctx.phone.tap(*uix.center(row))
    need(ctx.wait_restart(m, 30), "Channel Two did not start")
    opts = ctx.log.find(r"Loading http://127\.0\.0\.1:8765/p13_ep02\.mkv with options: (.*)$", m)
    need(opts and "user-agent=%" in opts[1].group(1), "the channel's #EXTVLCOPT user agent was not passed to mpv")
    return "playlist groups and channels listed; Channel Two played with its user agent"


MANUAL.extend([
    ("p13_sftp_smb", "SFTP and SMB (P13)",
     "Network > + Add > SFTP: your PC (ssh on), user and password: folders list, a video plays and seeks, next plays the "
     "following file. Same with SMB (a Windows or Samba share; the path starts with the share name, e.g. /Movies)."),
    ("p13_dlna", "DLNA media server (P13)",
     "With Jellyfin / Plex / Kodi / MiniDLNA DLNA on: Network > Media servers > Search finds it; browse to a video and play it."),
    ("p13_webdav_ftp", "WebDAV and FTP (P13)",
     "Nextcloud or a NAS WebDAV address with user and password lists and plays; an FTP server too (anonymous or with a user)."),
    ("p13_iptv", "A real IPTV playlist (P13)",
     "Add your provider's M3U link: groups list, a live channel starts within a few seconds, channel up/down (next/previous) "
     "switches, a dropped connection reconnects."),
    ("p13_open_with", "Open with from a browser (P13)",
     "In a web browser, long-press a video link (mp4 or m3u8) > Open with > Vibe Player: it plays."),
])
