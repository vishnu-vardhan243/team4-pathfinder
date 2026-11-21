"""Render the Pathfinder logo to PNG (for the video, report and website). Needs Pillow.

    python tools/make_logo.py

Same geometry as the app icon in app/pathfinder/src/main/res/drawable/ic_launcher_*.xml (108 x 108 canvas).
Writes docs/logo/pathfinder-logo-1024.png (rounded square) and pathfinder-logo-256.png.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "logo"
S = 4096 / 108  # supersampled scale: render at 4096 px, then downscale
TOP, BOTTOM = (0x3A, 0x66, 0xF0), (0x1B, 0x3B, 0xA8)
NODE_FILL, FLAG, WHITE = (0x24, 0x50, 0xD6), (0x4C, 0xC3, 0x8A), (255, 255, 255)


K, DY = 0.86, 1.5  # same as the <group> in ic_launcher_foreground.xml


def p(x, y):
    return ((54 + (x - 54) * K) * S, (54 + (y - 54) * K + DY) * S)


def bezier(p0, p1, p2, p3, n=200):
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t ** 2, t ** 3
        yield (a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1])


def stroke(draw, pts, width, colour):
    r = width * K * S / 2
    pts = list(pts)
    draw.line([p(*q) for q in pts], fill=colour, width=int(width * K * S))
    for q in pts:  # round joins and caps
        x, y = p(*q)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=colour)


def circle(draw, cx, cy, r, fill):
    x, y = p(cx, cy)
    r *= K
    draw.ellipse([x - r * S, y - r * S, x + r * S, y + r * S], fill=fill)


def render():
    size = int(108 * S)
    # Diagonal gradient background, light at the top left.
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for i in range(0, 2 * size, 8):
        t = i / (2 * size)
        col = tuple(int(TOP[k] + (BOTTOM[k] - TOP[k]) * t) for k in range(3))
        d.line([(i, 0), (0, i)], fill=col, width=10)
    path = list(bezier((36, 74), (48, 74), (56, 66), (52, 56))) + list(bezier((52, 56), (48, 46), (60, 42), (66, 42)))
    stroke(d, path, 7, WHITE)
    circle(d, 36, 74, 6, WHITE)          # start point
    circle(d, 52, 56, 6, WHITE)          # skill node: white ring...
    circle(d, 52, 56, 3, NODE_FILL)      # ...around a blue centre
    stroke(d, [(66, 44), (66, 27)], 3.5, WHITE)
    d.polygon([p(66, 27), p(78, 31.5), p(66, 36)], fill=FLAG)
    # Crop to the visible 72 x 72 icon area (18..90) and round the corners like a launcher icon.
    crop = img.crop((int(18 * S), int(18 * S), int(90 * S), int(90 * S)))
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, crop.size[0] - 1, crop.size[1] - 1], radius=int(crop.size[0] * 0.22), fill=255)
    icon = Image.new("RGBA", crop.size, (0, 0, 0, 0))
    icon.paste(crop, (0, 0), mask)
    return icon


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = render()
    for px in (1024, 256):
        icon.resize((px, px), Image.LANCZOS).save(OUT / f"pathfinder-logo-{px}.png")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
