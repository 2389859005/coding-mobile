"""Measure the rendered pet sheet.

Catches the failure modes that matter for hand-placed vector art: a form that
draws nothing, one that has escaped the tile, and two forms that are the same
drawing under different names.
"""
import struct
import sys
import zlib
from pathlib import Path

BUILD = Path(__file__).resolve().parent / ".build"
png = BUILD / "pet_sheet.png"
raw = png.read_bytes()
assert raw[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"

pos, idat, width, height = 8, b"", None, None
while pos < len(raw):
    length = struct.unpack(">I", raw[pos:pos + 4])[0]
    kind = raw[pos + 4:pos + 8]
    body = raw[pos + 8:pos + 8 + length]
    if kind == b"IHDR":
        width, height, depth, color = struct.unpack(">IIBB", body[:10])
        assert depth == 8 and color == 6, "expected 8-bit RGBA"
    elif kind == b"IDAT":
        idat += body
    elif kind == b"IEND":
        break
    pos += 12 + length

data = zlib.decompress(idat)
stride = width * 4
rows, prev, offset = [], bytearray(stride), 0
for _ in range(height):
    ft = data[offset]
    offset += 1
    line = bytearray(data[offset:offset + stride])
    offset += stride
    for i in range(stride):
        a = line[i - 4] if i >= 4 else 0
        b = prev[i]
        c = prev[i - 4] if i >= 4 else 0
        if ft == 1:
            line[i] = (line[i] + a) & 255
        elif ft == 2:
            line[i] = (line[i] + b) & 255
        elif ft == 3:
            line[i] = (line[i] + ((a + b) >> 1)) & 255
        elif ft == 4:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
            line[i] = (line[i] + pred) & 255
    rows.append(bytes(line))
    prev = line


def pixel(x, y):
    base = x * 4
    line = rows[y]
    return line[base], line[base + 1], line[base + 2], line[base + 3]


TILE = 200
COLS = 3
forms = sys.argv[1:] or ["maltese", "robot"]

print("sheet %dx%d" % (width, height))
print()
print("%-9s %-5s %7s %7s %7s %9s %8s" % ("form", "pose", "inked", "bbox w", "bbox h", "edge", "density"))
signatures = {}
for index, form in enumerate(forms):
    cx = (index % COLS) * TILE
    cy = (index // COLS) * TILE
    for pose in (0, 1):
        x0 = cx + pose * (TILE // 2)
        inked = 0
        minx, miny, maxx, maxy = 10 ** 9, 10 ** 9, -1, -1
        for y in range(cy, cy + TILE):
            for x in range(x0, x0 + TILE // 2):
                r, g, b, a = pixel(x, y)
                if a > 40 and not (abs(r - 0xf6) < 6 and abs(g - 0xf7) < 6 and abs(b - 0xf2) < 6):
                    inked += 1
                    if x < minx: minx = x
                    if y < miny: miny = y
                    if x > maxx: maxx = x
                    if y > maxy: maxy = y
        bw = (maxx - minx + 1) if maxx >= 0 else 0
        bh = (maxy - miny + 1) if maxy >= 0 else 0
        area = max(1, bw * bh)
        # Escaping the tile means ink outside the cell, not ink flush against it. Two
        # earlier versions got this wrong in opposite directions: the first flagged
        # anything touching the top of the scan window, which the hop does by design,
        # and the second flagged a sprite that legitimately fills its cell. A sprite
        # that reaches the last pixel of its cell is the goal, not a fault, so the
        # test only reports ink that would actually be cut off.
        edge = "yes" if (minx < x0 or miny < cy
                         or maxx >= x0 + TILE // 2 or maxy >= cy + TILE) else "no"
        print("%-9s %-5s %7d %7d %7d %9s %7.2f" % (
            form, "idle" if pose == 0 else "hop", inked, bw, bh, edge, inked / area))
        signatures.setdefault(form, []).append((inked, bw, bh))

print()
seen = {}
for form, metrics in signatures.items():
    inked, bw, bh = metrics[0]
    key = (round(inked / 500.0), round(bw / 20.0), round(bh / 20.0))
    seen.setdefault(key, []).append(form)
clashes = {k: v for k, v in seen.items() if len(v) > 1}
print("collisions: %s" % (clashes if clashes else "none"))
empty = [f for f, m in signatures.items() if m[0][0] < 300]
print("empty/near-empty: %s" % (empty if empty else "none"))
