"""Measures the surface hierarchy of every theme from the Java's real HSL maths.

The first version of this file mis-modelled hsl() as a mix-toward-grey helper, which
understated everything: the Java takes a true HSL saturation in 0..1, so a theme's
canvas is already a faint colour wash rather than a neutral. Getting the model right is
what makes the numbers here usable as a target.

Two things are being sized:

  * SOFT against CANVAS - the input field against the page behind it. At 1.07:1 the
    field is invisible, which is the reported "input background almost transparent".
  * theme against theme - five light themes whose canvases differ by one step in one
    channel are the same theme to the eye, which is the reported "themes are flat,
    no contrast".
"""
import colorsys
import math
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def hsl(hue, saturation, lightness):
    """Port of NativeUi.hsl: hue in degrees, saturation and lightness in 0..1."""
    h = ((hue % 360) + 360) % 360 / 360.0
    s = max(0.0, min(1.0, saturation))
    l = max(0.0, min(1.0, lightness))
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return int(round(r * 255)), int(round(g * 255)), int(round(b * 255))


def luminance(rgb):
    def channel(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


# name: hue, dark, chroma - from scheme() in NativeUi.java.
SCHEMES = [
    ("light",    150, False, 0.22),
    ("dark",     150, True,  0.30),
    ("green",    150, False, 0.30),
    ("ocean",    205, False, 0.34),
    ("lavender", 272, False, 0.28),
    ("sand",      34, False, 0.30),
    ("midnight", 232, True,  0.34),
]


def surfaces(dark, hue, chroma):
    canvas = hsl(hue, chroma * (0.32 if dark else 0.14), 0.100 if dark else 0.982)
    white = hsl(hue, chroma * (0.34 if dark else 0.04), 0.145 if dark else 0.999)
    soft = hsl(hue, chroma * 0.32 if dark else chroma * 0.22, 0.185 if dark else 0.950)
    tint = hsl(hue, chroma * 0.46 if dark else chroma * 0.38, 0.265 if dark else 0.910)
    return canvas, white, soft, tint


print("当前状态")
print("%-9s %-9s %-9s %-9s %-9s %9s %9s" %
      ("主题", "CANVAS", "WHITE", "SOFT", "TINT", "SOFT/CAN", "SOFT/TIN"))
print("-" * 76)
current = {}
for name, hue, dark, chroma in SCHEMES:
    canvas, white, soft, tint = surfaces(dark, hue, chroma)
    current[name] = (canvas, white, soft, tint)
    print("%-9s #%02x%02x%02x  #%02x%02x%02x  #%02x%02x%02x  #%02x%02x%02x %9.2f %9.2f" %
          (name, *canvas, *white, *soft, *tint, contrast(soft, canvas), contrast(soft, tint)))

print()
print("亮色主题两两之间 CANVAS 的对比度（1.01 以下等于同一个颜色）：")
lights = [n for n, _, d, _ in SCHEMES if not d]
for i, a in enumerate(lights):
    for b in lights[i + 1:]:
        value = contrast(current[a][0], current[b][0])
        mark = "  <-- 肉眼无法区分" if value < 1.01 else ""
        print("  %-9s vs %-9s %.4f%s" % (a, b, value, mark))
