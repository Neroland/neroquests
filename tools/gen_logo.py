#!/usr/bin/env python3
"""
Generate the NEROQUESTS mod logo (square), in the shared Neroland family style
(cf. neroland-core, nerospace, nerotech, nerologistics, nerolink): a deep-space starfield,
the family faceted hexagonal core-prism with a glowing centre node + specular sparkle, and a
beveled glowing wordmark.

the quest/objectives mod, so the core-prism sits inside a GOLD compass ring with a dotted quest path and a checkmark waypoint Renders supersampled, then downsamples.

Outputs:
  art/logo/neroquests_logo.png       (1024x1024 master)
  art/logo/neroquests_logo_400.png   (CurseForge/Modrinth-ready)
  common/src/main/resources/neroquests_logo.png  (256x256 in-game mods-list icon)
"""
SEED = 59
MOD = 'neroquests'
NAME = 'NEROQUESTS'
ACCENT = (230, 184, 80)
ACCENT_BRIGHT = (255, 224, 150)
BRIGHT = (255, 248, 226)
NEBULA = (112, 84, 26)
STAR_TINT = (255, 232, 182)
NAME_GLOW = (230, 184, 80, 255)
TAG_COL = (230, 210, 158, 255)

import math, os, random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "art/logo")
ICON = os.path.join(ROOT, "common/src/main/resources")
os.makedirs(OUT, exist_ok=True)
os.makedirs(ICON, exist_ok=True)

FINAL = 1024
SS = 2
R = FINAL * SS
rng = random.Random(SEED)

NERO_ALLOY = (38, 166, 154)     # family teal
STARSTEEL  = (140, 178, 208)    # family steel-blue
PLASMA     = (96, 212, 232)     # family cyan
STEEL      = (122, 132, 146)    # machine casing
STEEL_DK   = (66, 74, 86)


def _font(size):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def background():
    top = np.array([6, 11, 17], float)
    bot = np.array([13, 18, 28], float)
    yy = np.linspace(0, 1, R)[:, None, None]
    img = top[None, None, :] * (1 - yy) + bot[None, None, :] * yy
    img = np.repeat(img, R, axis=1)
    Y, X = np.mgrid[0:R, 0:R].astype(float)

    def glow(cx, cy, rad, color, strength):
        d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
        f = np.clip(1 - d / rad, 0, 1) ** 2 * strength
        for c in range(3):
            img[:, :, c] += color[c] * f

    glow(R * 0.28, R * 0.30, R * 0.55, (20, 80, 84), 0.42)   # family teal nebula
    glow(R * 0.76, R * 0.72, R * 0.55, NEBULA, 0.44)         # mod accent nebula
    glow(R * 0.5, R * 0.5, R * 0.42, (24, 44, 60), 0.28)

    d = np.sqrt((X - R / 2) ** 2 + (Y - R / 2) ** 2) / (R * 0.72)
    vig = np.clip(1 - (d ** 2) * 0.85, 0.25, 1)
    img *= vig[:, :, None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def add_stars(base):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(460):
        x, y = rng.randint(0, R), rng.randint(0, R)
        s = rng.choice([1, 1, 1, 2, 2, 3]) * SS
        b = rng.randint(120, 255)
        tint = rng.choice([(b, b, b), (b, 255, 255), STAR_TINT, (200, 200, 255)])
        d.ellipse([x, y, x + s, y + s], fill=tint + (rng.randint(120, 255),))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(2 * SS)))
    base.alpha_composite(layer)
    return base


def soft_glow(draw_fn, blur):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def draw_core_prism(base, cx, cy, rad):
    # accent aura behind the family prism
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([cx - rad * 1.3, cy - rad * 1.3, cx + rad * 1.3, cy + rad * 1.3],
                              fill=ACCENT + (110,)), 24 * SS))
    hexpts = [(cx + math.cos(math.radians(60 * i - 90)) * rad,
               cy + math.sin(math.radians(60 * i - 90)) * rad) for i in range(6)]
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i in range(6):
        shade = 0.58 + 0.42 * (i / 5.0)
        col = tuple(int(c * shade) for c in FACET_COLS[i])
        d.polygon([(cx, cy), hexpts[i], hexpts[(i + 1) % 6]], fill=col + (255,))
    ir = rad * 0.36
    d.ellipse([cx - ir, cy - ir, cx + ir, cy + ir], fill=ACCENT_BRIGHT + (255,))
    d.ellipse([cx - ir * 0.5, cy - ir * 0.5, cx + ir * 0.5, cy + ir * 0.5], fill=BRIGHT + (255,))
    for i in range(6):
        d.line([hexpts[i], hexpts[(i + 1) % 6]], fill=(230, 240, 250, 235), width=max(1, SS * 2))
        d.line([(cx, cy), hexpts[i]], fill=(220, 230, 240, 150), width=max(1, SS))
    base.alpha_composite(layer)
    return base


def sparkle(base, cx, cy, rad):
    sx, sy = cx - rad * 0.16, cy - rad * 0.46
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([sx - 9 * SS, sy - 9 * SS, sx + 9 * SS, sy + 9 * SS],
                              fill=(255, 255, 255, 255)), 5 * SS))
    dd = ImageDraw.Draw(base)
    L = 18 * SS
    dd.line([sx - L, sy, sx + L, sy], fill=(255, 255, 255, 230), width=SS * 2)
    dd.line([sx, sy - L, sx, sy + L], fill=(255, 255, 255, 230), width=SS * 2)
    return base


def _fit(text, frac, maxw):
    size = int(R * frac)
    while size > 8:
        f = _font(size)
        if ImageDraw.Draw(Image.new("RGBA", (4, 4))).textlength(text, font=f) <= maxw:
            return f
        size -= 2 * SS
    return _font(size)


def wordmark(base):
    big = _fit(NAME, 0.140, R * 0.86)
    tagf = _fit(TAG, 0.030, R * 0.92)

    def centered(text, font, y, fill, glow):
        w = ImageDraw.Draw(base).textlength(text, font=font)
        x = (R - w) / 2
        gl = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        ImageDraw.Draw(gl).text((x, y), text, font=font, fill=glow)
        base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(9 * SS)))
        base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(3 * SS)))
        out = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        ImageDraw.Draw(out).text((x, y), text, font=font, fill=(10, 12, 16, 255))
        base.alpha_composite(out.filter(ImageFilter.MaxFilter(2 * SS + 1)))
        ImageDraw.Draw(base).text((x, y), text, font=font, fill=fill)

    centered(NAME, big, int(R * 0.705), (244, 250, 252, 255), NAME_GLOW)
    tw = ImageDraw.Draw(base).textlength(TAG, font=tagf)
    ImageDraw.Draw(base).text(((R - tw) / 2, int(R * 0.862)), TAG, font=tagf, fill=TAG_COL)
    return base


def emblem(base, cx, cy, rad):
    emblem_frame(base, cx, cy, rad)
    draw_core_prism(base, cx, cy, rad)
    sparkle(base, cx, cy, rad)
    return base

FACET_COLS = [STARSTEEL, NERO_ALLOY, ACCENT, STARSTEEL, NERO_ALLOY, ACCENT]
TAG = 'Q U E S T S   ·   O B J E C T I V E S   ·   R E W A R D S'

def emblem_frame(base, cx, cy, rad):
    ring = rad * 1.6
    gl = Image.new("RGBA", (R, R), (0, 0, 0, 0)); gd = ImageDraw.Draw(gl)
    # compass ring with cardinal ticks
    gd.ellipse([cx - ring, cy - ring, cx + ring, cy + ring], outline=ACCENT + (220,), width=SS * 3)
    for i in range(24):
        a = math.radians(i * 15)
        long = (i % 6 == 0)
        r0 = ring - rad * (0.28 if long else 0.15)
        gd.line([cx + math.cos(a) * r0, cy + math.sin(a) * r0,
                 cx + math.cos(a) * ring, cy + math.sin(a) * ring],
                fill=ACCENT + (200 if long else 130,), width=SS * (2 if long else 1))
    base.alpha_composite(gl)
    # dotted quest path with a checkmark waypoint
    pl = Image.new("RGBA", (R, R), (0, 0, 0, 0)); pd = ImageDraw.Draw(pl)
    for t in range(200, 340, 12):
        a = math.radians(t); px, py = cx + math.cos(a) * ring, cy + math.sin(a) * ring
        pd.ellipse([px - SS * 2, py - SS * 2, px + SS * 2, py + SS * 2], fill=ACCENT_BRIGHT + (255,))
    a = math.radians(90); wx, wy = cx + math.cos(a) * ring, cy + math.sin(a) * ring
    pd.ellipse([wx - rad * 0.28, wy - rad * 0.28, wx + rad * 0.28, wy + rad * 0.28],
               fill=STEEL_DK + (255,), outline=ACCENT + (255,), width=SS * 2)
    pd.line([(wx - rad * 0.14, wy), (wx - rad * 0.02, wy + rad * 0.13),
             (wx + rad * 0.17, wy - rad * 0.14)], fill=ACCENT_BRIGHT + (255,), width=SS * 3, joint="curve")
    base.alpha_composite(pl)


def main():
    img = background()
    img = add_stars(img)
    cx, cy, rad = int(R * 0.5), int(R * 0.355), int(R * 0.122)
    img = emblem(img, cx, cy, rad)
    img = wordmark(img)

    final = img.convert("RGB").resize((FINAL, FINAL), Image.LANCZOS)
    p1 = os.path.join(OUT, MOD + "_logo.png")
    p2 = os.path.join(OUT, MOD + "_logo_400.png")
    p3 = os.path.join(ICON, MOD + "_logo.png")
    final.save(p1)
    final.resize((400, 400), Image.LANCZOS).save(p2)
    final.resize((256, 256), Image.LANCZOS).save(p3)
    for p in (p1, p2, p3):
        print("wrote", os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
