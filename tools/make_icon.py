"""Draws the launcher icons for the five styles (all densities) with Pillow.

ic_launcher       - Original (VS Code): blue rounded square with "</>"
ic_launcher_holo  - Holo: a dark slate with a soft drop shadow and a Holo-blue glyph
ic_launcher_md    - Material (2014): keyline circle, white glyph with a 45-degree long shadow
ic_launcher_m3    - Material You: tonal container circle (pre-8.0 fallback; 8.0+ uses the adaptive XML)
ic_launcher_matrix - Matrix: black screen, falling code, glowing phosphor glyph
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SIZES = {"drawable-mdpi": 48, "drawable-hdpi": 72, "drawable-xhdpi": 96, "drawable-xxhdpi": 144, "drawable-xxxhdpi": 192}

GLYPH = [((0.36, -0.17), (0.22, 0.0), (0.36, 0.17)), ((0.56, -0.21), (0.44, 0.21)), ((0.64, -0.17), (0.78, 0.0), (0.64, 0.17))]


def glyph(d, s, color, width, cx=0.5, cy=0.5, scale=1.0):
    for stroke in GLYPH:
        pts = [(s * (cx + (x - 0.5) * scale), s * (cy + y * scale)) for x, y in stroke]
        d.line(pts, fill=color, width=width, joint="curve")
        r = width / 2
        for px, py in pts:
            d.ellipse([px - r, py - r, px + r, py + r], fill=color)


def gradient(s, top, bottom):
    g = Image.new("RGBA", (s, s))
    gd = ImageDraw.Draw(g)
    for y in range(s):
        t = y / (s - 1)
        gd.line([(0, y), (s, y)], fill=tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,))
    return g


def original(s):
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    m = int(s * 0.06)
    ImageDraw.Draw(mask).rounded_rectangle([m, m, s - m, s - m], radius=int(s * 0.22), fill=255)
    img.paste(gradient(s, (0x23, 0x8F, 0xE6), (0x00, 0x5F, 0xB8)), (0, 0), mask)
    glyph(ImageDraw.Draw(img), s, (255, 255, 255, 255), int(s * 0.075))
    return img


def holo(s):
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    # Drop shadow under a slightly tilted-looking slate (Holo icons had depth and shadows).
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([s * 0.12, s * 0.16, s * 0.9, s * 0.92], radius=int(s * 0.06), fill=(0, 0, 0, 150))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(s * 0.03)))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([s * 0.1, s * 0.1, s * 0.88, s * 0.86], radius=int(s * 0.06), fill=255)
    img.paste(gradient(s, (0x4A, 0x4A, 0x4A), (0x1C, 0x1C, 0x1C)), (0, 0), mask)
    d = ImageDraw.Draw(img)
    # A bright top bevel.
    d.line([(s * 0.14, s * 0.105), (s * 0.84, s * 0.105)], fill=(255, 255, 255, 90), width=max(1, int(s * 0.012)))
    glyph(d, s, (0x33, 0xB5, 0xE5, 255), int(s * 0.07), cx=0.49, cy=0.48, scale=0.86)
    return img


def material(s, color=(0x3F, 0x51, 0xB5)):
    big = s * 4
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = big * 0.08
    circle = [pad, pad + big * 0.01, big - pad, big - pad + big * 0.01]
    # Elevation shadow of the keyline shape.
    sh = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([pad, pad + big * 0.03, big - pad, big - pad + big * 0.03], fill=(0, 0, 0, 90))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(big * 0.02)))
    d.ellipse(circle, fill=color + (255,))
    # Long shadow: the glyph smeared toward the bottom-right, clipped to the circle.
    ls = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    lsd = ImageDraw.Draw(ls)
    w = int(big * 0.075)
    for k in range(0, int(big * 0.3), max(1, big // 200)):
        glyph(lsd, big, (0, 0, 0, 255), w, cx=0.5 + k / big, cy=0.5 + k / big)
    ls_alpha = ls.split()[3].point(lambda a: 60 if a else 0)
    clip = Image.new("L", (big, big), 0)
    ImageDraw.Draw(clip).ellipse(circle, fill=255)
    from PIL import ImageChops
    ls_alpha = ImageChops.multiply(ls_alpha, clip)
    shade = Image.new("RGBA", (big, big), (0, 0, 0, 255))
    shade.putalpha(ls_alpha)
    img.alpha_composite(shade)
    glyph(ImageDraw.Draw(img), big, (255, 255, 255, 255), w)
    # Top-half light tint like the 2014 keyline icons.
    tint = Image.new("RGBA", (big, big), (255, 255, 255, 0))
    td = ImageDraw.Draw(tint)
    td.pieslice(circle, 180, 360, fill=(255, 255, 255, 26))
    img.alpha_composite(tint)
    return img.resize((s, s), Image.LANCZOS)


def m3(s, container=(0xEA, 0xDD, 0xFF), on=(0x21, 0x00, 0x5D)):
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = s * 0.06
    d.ellipse([pad, pad, s - pad, s - pad], fill=container + (255,))
    glyph(d, s, on + (255,), int(s * 0.065), scale=0.8)
    return img


def matrix(s):
    """Matrix: a black screen with faint falling code and a glowing phosphor "</>"."""
    import random
    rnd = random.Random(1999)
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    m = int(s * 0.06)
    ImageDraw.Draw(mask).rounded_rectangle([m, m, s - m, s - m], radius=int(s * 0.18), fill=255)
    screen = Image.new("RGBA", (s, s), (0, 6, 2, 255))
    sd = ImageDraw.Draw(screen)
    # Columns of "glyphs" (little strokes and boxes), brightest at the leading end.
    cols = 9
    cw = (s - 2 * m) / cols
    for c in range(cols):
        x = m + c * cw + cw * 0.2
        head = rnd.uniform(0.3, 1.0) * s
        y = head
        k = 0
        while y > m and k < 14:
            a = max(30, 230 - k * 18)
            g = (0, 255, 65, a) if k else (200, 255, 210, 255)
            gh = cw * 0.55
            kind = rnd.randint(0, 2)
            if kind == 0:
                sd.rectangle([x, y - gh, x + cw * 0.55, y - gh * 0.2], outline=g, width=max(1, int(s * 0.008)))
            elif kind == 1:
                sd.line([(x, y - gh), (x + cw * 0.55, y - gh * 0.3)], fill=g, width=max(1, int(s * 0.01)))
            else:
                sd.line([(x + cw * 0.27, y - gh), (x + cw * 0.27, y - gh * 0.2)], fill=g, width=max(1, int(s * 0.01)))
            y -= gh * 1.25
            k += 1
    # Dim the rain under the glyph.
    shade = Image.new("RGBA", (s, s), (0, 0, 0, 120))
    screen.alpha_composite(shade)
    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(glow), s, (0, 255, 65, 255), int(s * 0.11))
    screen.alpha_composite(glow.filter(ImageFilter.GaussianBlur(s * 0.03)))
    glyph(ImageDraw.Draw(screen), s, (170, 255, 190, 255), int(s * 0.07))
    img.paste(screen, (0, 0), mask)
    border = ImageDraw.Draw(img)
    border.rounded_rectangle([m, m, s - m, s - m], radius=int(s * 0.18), outline=(0, 160, 40, 255), width=max(1, int(s * 0.015)))
    return img


def render(fn, px):    return fn(px * 4).resize((px, px), Image.LANCZOS) if fn is not material else fn(px)


if __name__ == "__main__":
    for folder, px in SIZES.items():
        out = os.path.join(RES, folder)
        os.makedirs(out, exist_ok=True)
        for name, fn in (("ic_launcher", original), ("ic_launcher_holo", holo), ("ic_launcher_md", material), ("ic_launcher_m3", m3), ("ic_launcher_matrix", matrix)):
            render(fn, px).save(os.path.join(out, name + ".png"))
    sheet = Image.new("RGBA", (5 * 200, 200), (128, 128, 128, 255))
    for i, fn in enumerate((original, holo, material, m3, matrix)):
        sheet.alpha_composite(render(fn, 192), (i * 200 + 4, 4))
    sheet.save(os.path.join(ROOT, "tools", "icons-preview.png"))
    print("ok")
