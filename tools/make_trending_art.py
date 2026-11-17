"""Draws the Trending tile banner and renders TMDB's logo for the attribution line.

Usage (from the repo root, with the icon venv):
	tools/iconvenv/Scripts/python -I tools/make_trending_art.py launcher/app/src/main/res launcher/app/src/main/res/font/inter.ttf

TMDB's logo comes from https://www.themoviedb.org/about/logos-attribution (their terms ask apps to show it).
"""
import pathlib, sys, urllib.request
import resvg_py
from PIL import Image, ImageDraw, ImageFont

RES = pathlib.Path(sys.argv[1]); FONT = sys.argv[2]
OUT = RES / "drawable-nodpi"
LOGO_URL = ("https://www.themoviedb.org/assets/v4/logos/v2/"
            "blue_short-8e7b30f73a4020692ccca9c88bafe5dcb6f8a62a4c6bc55cd9ba82bb2cd95f6c.svg")

# TMDB logo: the short blue wordmark, 40 px tall (shown at 14dp).
svg = urllib.request.urlopen(LOGO_URL, timeout=30).read().decode("utf-8")
png = bytes(resvg_py.svg_to_bytes(svg_string=svg, height=40))
(OUT / "tmdb_logo.png").write_bytes(png)
print("tmdb_logo.png", Image.open(OUT / "tmdb_logo.png").size)

# Tile banner, 16:9 like other TV banners: deep night-blue to crimson, "Trending" in Inter.
W, H, K = 640, 360, 2
img = Image.new("RGB", (W * K, H * K))
top, bottom = (24, 14, 46), (176, 18, 43)
px = img.load()
for y in range(H * K):
    for x in range(W * K):
        t = min(1.0, max(0.0, (x / (W * K)) * 0.55 + (y / (H * K)) * 0.45))
        px[x, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
d = ImageDraw.Draw(img)


def inter(size, weight):
    f = ImageFont.truetype(FONT, size * K)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


title, sub = inter(92, 700), inter(34, 500)
tw = d.textlength("Trending", font=title)
sw = d.textlength("Movies  ·  Series", font=sub)
d.text(((W * K - tw) / 2, 112 * K), "Trending", font=title, fill=(255, 255, 255))
d.text(((W * K - sw) / 2, 232 * K), "Movies  ·  Series", font=sub, fill=(255, 255, 255, 190))
# A small rising-line mark above the word.
d.line([(292 * K, 92 * K), (312 * K, 74 * K), (326 * K, 84 * K), (348 * K, 60 * K)], fill=(255, 255, 255), width=5 * K,
       joint="curve")
d.polygon([(348 * K, 60 * K), (336 * K, 60 * K), (348 * K, 72 * K)], fill=(255, 255, 255))
img.resize((W, H), Image.LANCZOS).save(OUT / "banner_trending.png")
print("banner_trending.png", (W, H))
