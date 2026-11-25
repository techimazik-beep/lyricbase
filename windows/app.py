"""Lyric Constellation for Windows.
Reads what is playing from Windows media controls (Spotify, YouTube Music, browsers...),
fetches synced lyrics from LRCLIB and serves the animated page to a fullscreen app window."""
import asyncio, json, os, subprocess, sys, threading, time, urllib.parse, urllib.request, webbrowser
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8765
MOCK = os.environ.get("LC_MOCK") == "1"
STATE = {"ok": False, "title": "", "artist": "", "duration": 0, "playing": False, "pos": 0.0, "at": time.time()}
LAST_HIT = {"t": 0.0, "seen": False}
LRC_CACHE = {}


def res(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


# ───────────── now playing (Windows SMTC) ─────────────
async def poll_smtc():
    from winsdk.windows.media.control import (
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    )
    mgr = await Manager.request_async()
    while True:
        try:
            sessions = list(mgr.get_sessions())
            pick = None
            for s in sessions:
                if s.get_playback_info().playback_status == Status.PLAYING:
                    pick = s
                    break
            if pick is None and sessions:
                pick = mgr.get_current_session() or sessions[0]
            if pick is None:
                STATE.update(ok=False)
            else:
                props = await pick.try_get_media_properties_async()
                tl = pick.get_timeline_properties()
                playing = pick.get_playback_info().playback_status == Status.PLAYING
                pos = tl.position.total_seconds() * 1000.0
                lu = tl.last_updated_time
                if lu.tzinfo is None:
                    lu = lu.replace(tzinfo=timezone.utc)
                age = max(0.0, (datetime.now(timezone.utc) - lu).total_seconds() * 1000.0)
                if playing:
                    pos += min(age, 3600_000)
                STATE.update(ok=bool(props.title), title=props.title or "", artist=props.artist or "",
                             duration=int(tl.end_time.total_seconds() * 1000), playing=playing,
                             pos=pos, at=time.time())
        except Exception as e:  # keep going whatever happens
            STATE.update(ok=False, err=str(e))
        await asyncio.sleep(0.4)


def mock_loop():
    t0 = time.time()
    while True:
        STATE.update(ok=True, title="Demo Song", artist="Demo Artist", duration=60000, playing=True,
                     pos=((time.time() - t0) * 1000) % 60000, at=time.time())
        time.sleep(0.2)


MOCK_LRC = "\n".join(f"[00:{s:02d}.00] {t}" for s, t in [
    (1, "we drift like stars far from the dark sky"), (5, "every night circles back to you again"),
    (9, "slow waves carry my voice across the water"), (13, "hold on to the light"),
    (17, "tonight we are louder than the silence"), (21, "nothing moves without you here")])


# ───────────── lyrics (LRCLIB) ─────────────
def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "LyricConstellation/1.0"})
    with urllib.request.urlopen(req, timeout=8) as r:
        return r.read().decode("utf-8")


def clean(s):
    import re
    return re.sub(r" - .*$", "", re.sub(r"\(.*?\)|\[.*?\]", "", s)).strip()


def fetch_lrc(title, artist, dur_ms):
    if MOCK:
        return MOCK_LRC
    key = (title, artist, dur_ms // 1000)
    if key in LRC_CACHE:
        return LRC_CACHE[key]
    q = urllib.parse.quote
    out = ""
    try:
        d = json.loads(http_get(f"https://lrclib.net/api/get?track_name={q(title)}&artist_name={q(artist)}&duration={dur_ms // 1000}"))
        out = d.get("syncedLyrics") or ""
    except Exception:
        pass
    if not out:
        try:
            arr = json.loads(http_get(f"https://lrclib.net/api/search?track_name={q(clean(title))}&artist_name={q(artist.split(',')[0].split('&')[0].strip())}"))
            best, bd = "", 1e18
            for o in arr:
                if not o.get("syncedLyrics"):
                    continue
                d = abs((o.get("duration") or 0) * 1000 - dur_ms) if dur_ms else 0
                if d < bd:
                    bd, best = d, o["syncedLyrics"]
            out = best
        except Exception:
            pass
    if out:
        LRC_CACHE[key] = out
    return out


# ───────────── tiny web server ─────────────
class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype):
        b = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path in ("/", "/index.html"):
            with open(res("index.html"), "rb") as f:
                return self.send(200, f.read(), "text/html; charset=utf-8")
        LAST_HIT["t"] = time.time(); LAST_HIT["seen"] = True
        if u.path == "/now":
            s = dict(STATE)
            if s.get("playing"):
                s["pos"] = s["pos"] + (time.time() - s["at"]) * 1000.0
            return self.send(200, json.dumps(s), "application/json")
        if u.path == "/lyrics":
            p = urllib.parse.parse_qs(u.query)
            lrc = fetch_lrc(p.get("title", [""])[0], p.get("artist", [""])[0], int(p.get("duration", ["0"])[0] or 0))
            return self.send(200, json.dumps({"lrc": lrc}), "application/json")
        self.send(404, "not found", "text/plain")


def launch_window(url):
    pf = [os.environ.get("ProgramFiles(x86)", ""), os.environ.get("ProgramFiles", ""), os.environ.get("LocalAppData", "")]
    rels = [r"Microsoft\Edge\Application\msedge.exe", r"Google\Chrome\Application\chrome.exe"]
    for base in pf:
        for rel in rels:
            exe = os.path.join(base, rel)
            if base and os.path.exists(exe):
                subprocess.Popen([exe, f"--app={url}", "--start-fullscreen"])
                return
    webbrowser.open(url)


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    if MOCK:
        threading.Thread(target=mock_loop, daemon=True).start()
    else:
        threading.Thread(target=lambda: asyncio.run(poll_smtc()), daemon=True).start()
        launch_window(f"http://127.0.0.1:{PORT}/")
    if MOCK:
        while True:
            time.sleep(1)
    # exit when the window has been closed
    while True:
        time.sleep(2)
        if LAST_HIT["seen"] and time.time() - LAST_HIT["t"] > 15:
            os._exit(0)


if __name__ == "__main__":
    main()
