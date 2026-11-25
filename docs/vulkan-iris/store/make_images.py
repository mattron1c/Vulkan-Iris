"""Builds the Modrinth / CurseForge page images for Vulkan Iris.

Inputs: bench.json (numbers copied from docs/vulkan-iris/BENCHMARK.md) and the gallery screenshots that
`gradlew :fabric:prodLive -Dvulkaniris.live.gallery=1600x900` writes to fabric/run-prod/screenshots.
Output: docs/vulkan-iris/store/img/*.png. Only Pillow and a system font are needed:
    python docs/vulkan-iris/store/make_images.py
"""
import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = HERE / "img"
SHOTS = ROOT / "fabric" / "run-prod" / "screenshots"
W = 1200

# palette: Iris violet into Vulkan red, on near-black
BG = (14, 12, 22)
CARD = (26, 23, 38)
TEXT = (238, 236, 245)
MUTED = (160, 155, 180)
VIOLET = (139, 92, 246)
RED = (172, 22, 44)
GL = (110, 118, 140)


def font(size, bold=False):
    for name in (["seguisb.ttf", "segoeuib.ttf"] if bold else []) + ["bahnschrift.ttf", "segoeui.ttf", "arial.ttf"]:
        p = pathlib.Path("C:/Windows/Fonts") / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


def gradient(w, h, a=VIOLET, b=RED, horizontal=True):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for x in range(w if horizontal else h):
        t = x / max(1, (w if horizontal else h) - 1)
        c = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
        if horizontal:
            for y in range(h):
                px[x, y] = c
        else:
            for y in range(w):
                px[y, x] = c
    return img


def rounded(img, r):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), r, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def shot(name):
    files = sorted(SHOTS.glob(f"vulkaniris-live-*-{name}*.png"))
    if not files:
        sys.exit(f"missing gallery screenshot for {name} in {SHOTS}")
    return Image.open(files[-1]).convert("RGB")


def banner(shots):
    h = 420
    img = Image.new("RGB", (W, h), BG)
    # screenshot strip on the right, fading into the background
    strip = Image.new("RGB", (720, h), BG)
    for i, s in enumerate(shots[:3]):
        tile = s.resize((round(h * s.width / s.height), h))
        strip.paste(tile.crop((tile.width // 2 - 120, 0, tile.width // 2 + 120, h)), (i * 240, 0))
    fade = Image.new("L", strip.size)
    for x in range(strip.width):
        ImageDraw.Draw(fade).line([(x, 0), (x, h)], fill=min(255, round(255 * (x / 260))))
    img.paste(strip, (W - strip.width, 0), fade)
    d = ImageDraw.Draw(img)
    d.rectangle((0, h - 6, W, h), fill=VIOLET)
    img.paste(gradient(W, 6), (0, h - 6))
    d.text((60, 110), "Vulkan Iris", font=font(88, True), fill=TEXT)
    d.text((64, 222), "Iris shader packs, drawn natively on Vulkan", font=font(30), fill=MUTED)
    tag = " Minecraft 1.21.11  ·  Fabric  ·  Sodium + Polydium "
    f = font(22)
    tw = d.textlength(tag, font=f)
    d.rounded_rectangle((60, 290, 60 + tw + 20, 330), 18, fill=CARD, outline=VIOLET, width=2)
    d.text((70, 296), tag, font=f, fill=TEXT)
    img.save(OUT / "banner.png", optimize=True)


def spacer():
    h = 28
    img = Image.new("RGBA", (W, h), (0, 0, 0, 0))
    line = gradient(W - 200, 2)
    mask = Image.new("L", line.size)
    md = ImageDraw.Draw(mask)
    for x in range(line.width):
        t = x / (line.width - 1)
        md.line([(x, 0), (x, 1)], fill=round(255 * (1 - abs(2 * t - 1)) ** 0.6))
    img.paste(line, (100, h // 2 - 1), mask)
    d = ImageDraw.Draw(img)
    cx, cy = W // 2, h // 2
    d.polygon([(cx, cy - 9), (cx + 9, cy), (cx, cy + 9), (cx - 9, cy)], fill=VIOLET)
    d.polygon([(cx, cy - 4), (cx + 4, cy), (cx, cy + 4), (cx - 4, cy)], fill=BG)
    img.save(OUT / "spacer.png", optimize=True)


def header(key, title):
    h = 96
    img = Image.new("RGBA", (W, h), (0, 0, 0, 0))
    card = Image.new("RGB", (W, h - 16), CARD)
    card.paste(gradient(10, h - 16, VIOLET, RED, horizontal=False), (0, 0))
    img.paste(rounded(card, 16), (0, 8))
    ImageDraw.Draw(img).text((40, 26), title, font=font(42, True), fill=TEXT)
    img.save(OUT / f"header-{key}.png", optimize=True)


def chart(bench):
    rows = bench["packs"]
    row_h, top, left, right = 46, 120, 345, 90
    h = top + row_h * len(rows) + 70
    img = Image.new("RGB", (W, h), BG)
    d = ImageDraw.Draw(img)
    d.text((40, 24), "Average FPS, same scene, A/B", font=font(36, True), fill=TEXT)
    d.text((40, 70), bench["setup"], font=font(19), fill=MUTED)
    top_fps = max(max(r["gl"], r["vk"]) for r in rows)
    span = W - left - right
    for i, r in enumerate(rows):
        y = top + i * row_h
        d.text((40, y + 8), r["name"], font=font(22), fill=TEXT)
        for j, (v, col) in enumerate(((r["gl"], GL), (r["vk"], None))):
            by = y + 4 + j * 18
            bw = max(2, round(span * v / top_fps))
            if col is None:
                img.paste(gradient(bw, 14), (left, by))
            else:
                d.rectangle((left, by, left + bw, by + 14), fill=col)
            d.text((left + bw + 8, by - 3), str(v), font=font(16), fill=MUTED if col else TEXT)
    ly = h - 46
    d.rectangle((40, ly + 4, 60, ly + 18), fill=GL)
    d.text((68, ly), "Iris on OpenGL", font=font(19), fill=MUTED)
    img.paste(gradient(20, 14), (240, ly + 4))
    d.text((268, ly), "Vulkan Iris on Polydium", font=font(19), fill=TEXT)
    d.text((560, ly), bench["note"], font=font(17), fill=MUTED)
    img.save(OUT / "benchmark.png", optimize=True)


def gallery_card(name, label):
    s = shot(name).resize((1200, 675), Image.LANCZOS)
    bar = Image.new("RGBA", (1200, 64), (14, 12, 22, 210))
    s = s.convert("RGBA")
    s.alpha_composite(bar, (0, 675 - 64))
    d = ImageDraw.Draw(s)
    d.text((28, 675 - 50), label, font=font(30, True), fill=TEXT)
    d.text((1200 - 28 - d.textlength("rendered on Vulkan", font=font(22)), 675 - 44), "rendered on Vulkan", font=font(22), fill=MUTED)
    rounded(s, 18).save(OUT / f"shot-{name.lower().replace(' ', '-')}.png", optimize=True)


def main():
    OUT.mkdir(exist_ok=True)
    bench = json.loads((HERE / "bench.json").read_text(encoding="utf-8"))
    gallery = bench["gallery"]
    shots = [shot(g["file"]) for g in gallery]
    banner(shots)
    spacer()
    for key, title in [("features", "What it does"), ("performance", "Performance"), ("compat", "Compatibility"),
                       ("install", "Install"), ("limits", "Known limits"), ("faq", "FAQ"), ("credits", "Credits & license")]:
        header(key, title)
    chart(bench)
    for g in gallery:
        gallery_card(g["file"], g["label"])
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))


if __name__ == "__main__":
    main()
