#!/usr/bin/env python3
"""Kleiner Xtream-Codes-Testserver für den Emulator-Test (erfundene Daten)."""
import json, random, struct, sys, zlib, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

random.seed(7)
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
BASE = f"http://10.0.2.2:{PORT}"
NOW = int(time.time())
GENRES = ["Action", "Komödie", "Drama", "Thriller", "Horror", "Doku", "Kinder", "Sci-Fi", "Krimi", "Romantik"]

def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

IMG = {}
def img(kind, i, w, h):
    return f"{BASE}/img/{kind}_{i}_{w}x{h}.png"

live_cats = [{"category_id": str(100 + i), "category_name": ("DE | " if i < 20 else "UK | ") + f"Gruppe {i}", "parent_id": 0} for i in range(40)]
vod_cats = [{"category_id": str(200 + i), "category_name": ("DE | " if i < 40 else "EN | ") + GENRES[i % 10] + f" {i}", "parent_id": 0} for i in range(60)]
ser_cats = [{"category_id": str(300 + i), "category_name": ("DE | " if i < 30 else "EN | ") + GENRES[i % 10] + f" Serien {i}", "parent_id": 0} for i in range(40)]

live = []
for i in range(3000):
    c = live_cats[i % 40]
    de = int(c["category_id"]) < 120
    live.append({"num": i + 1, "name": ("DE: " if de else "UK: ") + f"Sender {i}", "stream_type": "live", "stream_id": 1000 + i,
                 "stream_icon": img("logo", i % 50, 120, 80) if i % 7 else "", "epg_channel_id": f"sender{i}.de" if i % 3 else None,
                 "added": str(NOW - i * 100), "category_id": c["category_id"], "tv_archive": 0})
movies = []
for i in range(int(__import__('os').environ.get('MOVIES','30000'))):
    c = vod_cats[i % 60]
    de = int(c["category_id"]) < 240
    movies.append({"num": i + 1, "name": ("DE: " if de else "EN: ") + f"Film Nummer {i} ({2000 + i % 25})", "stream_type": "movie", "stream_id": 50000 + i,
                   "stream_icon": img("poster", i % 80, 200, 300) if i % 9 else None, "rating": str(round(random.uniform(0, 9.5), 1)) if i % 4 else "",
                   "rating_5based": 3.5, "added": str(NOW - i * 37), "category_id": c["category_id"], "category_ids": [int(c["category_id"])],
                   "container_extension": "mp4", "custom_sid": None, "direct_source": ""})
series = []
for i in range(int(__import__('os').environ.get('SERIES','5000'))):
    c = ser_cats[i % 40]
    de = int(c["category_id"]) < 330
    series.append({"num": i + 1, "name": ("DE: " if de else "EN: ") + f"Serie {i}", "series_id": 90000 + i,
                   "cover": img("poster", i % 80, 200, 300) if i % 5 else "", "plot": "Eine Testserie.", "cast": "", "director": "",
                   "genre": GENRES[i % 10], "releaseDate": f"{2010 + i % 15}-01-01", "last_modified": str(NOW - i * 50),
                   "rating": "7.1", "backdrop_path": [img("backdrop", i % 20, 640, 360)] if i % 2 else [], "youtube_trailer": "",
                   "episode_run_time": "45", "category_id": c["category_id"], "category_ids": [int(c["category_id"])]})

# echte Titel für den Such-Test (stehen in deutschen Kategorien)
NOFULL = __import__('os').environ.get('NOFULL') == '1'
for j, t in enumerate(["DE: Harry Potter und der Stein der Weisen (2001)", "DE: Spider-Man: No Way Home (2021)", "DE: Shrek 2 (2004)",
                       "DE: Fluch der Karibik (2003)", "EN: Harry Potter and the Chamber of Secrets (2002)"]):
    movies[j * 7]["name"] = t
series[0]["name"] = "DE: Haus des Geldes"; series[1]["name"] = "DE: Game of Thrones"
# Serie ohne „DE“ im Titel in einer Kategorie ohne Länderkennung (wie bei vielen Anbietern)
ser_cats.append({"category_id": "399", "category_name": "Serien | Krimi & Action", "parent_id": 0})
series.append(dict(series[2], name="The Rookie", series_id=99999, category_id="399", category_ids=[399]))
SLOW = float(__import__('os').environ.get('SLOW', '0'))

def vod_info(i):
    return {"info": {"movie_image": img("poster", i % 80, 200, 300), "backdrop_path": [img("backdrop", i % 20, 640, 360)] if i % 2 else [],
                     "plot": "Ein spannender Testfilm mit einer langen Beschreibung, damit man sieht, wie der Text umbricht.",
                     "genre": "Action, Thriller", "duration": "01:52:00", "releasedate": "2021-05-01", "rating": "7.4"},
            "movie_data": {"stream_id": i, "name": f"Film {i}", "container_extension": "mp4"}}

def series_info(i):
    eps = {}
    for s in range(1, 1 + (i % 4) + 1):
        eps[str(s)] = [{"id": str(700000 + i * 100 + s * 10 + e), "episode_num": e, "title": f"Serie {i} - S{s:02d}E{e:02d} - Folge {e}",
                        "container_extension": "mkv", "season": s,
                        "info": {"movie_image": img("still", e, 320, 180) if e % 2 else "", "duration": "00:44:00", "plot": "Folgenbeschreibung."}}
                       for e in range(1, 9)]
    return {"seasons": [], "info": {"name": f"Serie {i}", "cover": img("poster", i % 80, 200, 300), "plot": "Eine Testserie.",
                                    "genre": "Drama", "releaseDate": "2019-01-01", "rating": "8.0", "backdrop_path": [img("backdrop", i % 20, 640, 360)],
                                    "episode_run_time": "44"}, "episodes": eps}

def xmltv():
    out = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
    for i in range(0, 300, 3):
        cid = f"sender{i + 1}.de" if (i + 1) % 3 else f"sender{i + 2}.de"
        out.append(f'<channel id="{cid}"><display-name>Sender {i}</display-name></channel>')
    for i in range(0, 300):
        if i % 3 == 0: continue
        cid = f"sender{i}.de"
        for p in range(-2, 10):
            s = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(NOW - NOW % 3600 + p * 3600))
            e = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(NOW - NOW % 3600 + (p + 1) * 3600))
            out.append(f'<programme start="{s}" stop="{e}" channel="{cid}"><title lang="de">Sendung {p} auf {i}</title><desc lang="de">Beschreibung.</desc></programme>')
    out.append("</tv>")
    return "\n".join(out).encode()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, body, ctype="application/json"):
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path.startswith("/img/"):
            n = u.path.split("/")[-1].rsplit(".", 1)[0]; w, h = map(int, n.split("_")[-1].split("x"))
            seed = sum(map(ord, n)); return self.send(png(w, h, ((seed * 37) % 200 + 30, (seed * 17) % 200 + 30, (seed * 7) % 200 + 30)), "image/png")
        if u.path.endswith("xmltv.php"): return self.send(xmltv(), "application/xml")
        if not u.path.endswith("player_api.php"): self.send_response(404); self.end_headers(); return
        a = q.get("action"); cat = q.get("category_id")
        def flt(lst): return [x for x in lst if not cat or x["category_id"] == cat]
        # NOFULL=1: wie viele echte Anbieter – Gesamtliste ohne category_id wird verweigert
        if NOFULL and not cat and a in ("get_vod_streams", "get_series"): return self.send(b"[]")
        if SLOW and cat: time.sleep(SLOW)
        if a is None:
            body = {"user_info": {"username": "test", "password": "test", "auth": 1, "status": "Active", "exp_date": str(NOW + 86400 * 90),
                                  "is_trial": "0", "active_cons": "0", "max_connections": "2", "allowed_output_formats": ["m3u8", "ts"]},
                    "server_info": {"url": "10.0.2.2", "port": str(PORT), "server_protocol": "http", "timezone": "Europe/Berlin", "timestamp_now": NOW}}
        elif a == "get_live_categories": body = live_cats
        elif a == "get_vod_categories": body = vod_cats
        elif a == "get_series_categories": body = ser_cats
        elif a == "get_live_streams": body = flt(live)
        elif a == "get_vod_streams": body = flt(movies)
        elif a == "get_series": body = flt(series)
        elif a == "get_vod_info": body = vod_info(int(q.get("vod_id", "0")))
        elif a == "get_series_info": body = series_info(int(q.get("series_id", "0")))
        elif a == "get_short_epg": body = {"epg_listings": []}
        else: body = []
        self.send(json.dumps(body).encode())

print(f"Testserver auf Port {PORT}", flush=True)
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
