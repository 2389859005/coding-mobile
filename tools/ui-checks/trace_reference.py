"""Turns a raster reference into traced centre-line paths for PetSprite.

The sprite is drawn as vectors rather than shipped as a bitmap because one form has
to stay crisp from a 38dp settings swatch to a 64dp cut-out bubble, and because the
APK is repacked with apktool, where a new drawable resource would mean a new
resource id.

What this produces is not a raster trace of the whole picture. A traced photograph
of a dog would be thousands of points and would still look like a photograph. It
extracts the two things the art style is actually made of:

  outline   the dark contour, as a closed polygon, simplified to a point budget
  fill      the flat regions between contours, as their mean colour

Usage:
    python tools/ui-checks/trace_reference.py <image> [--points 220]
"""
import argparse
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
try:
    from PIL import Image
except ImportError:
    print("需要 Pillow")
    raise SystemExit(1)


def load(path, size=256):
    image = Image.open(path).convert("RGBA")
    image.thumbnail((size, size), Image.LANCZOS)
    return image


def pixels_of(image):
    """Opaque pixels, as a list of (r, g, b). getdata() is deprecated in Pillow 14."""
    data = image.tobytes()
    out = []
    for index in range(0, len(data), 4):
        r, g, b, a = data[index], data[index + 1], data[index + 2], data[index + 3]
        if a > 128:
            out.append((r, g, b))
    return out


def palette(image, count=8):
    """The flat colours of the drawing, ignoring the transparent surround.

    Quantising a 1xN strip does not work: PIL picks the box size from the image
    dimensions, so a strip collapses to near-identical tones. The reference is
    rebuilt at a sane shape and quantised there, then the colours are ranked by how
    much of the opaque area they cover.
    """
    colours = pixels_of(image)
    if not colours:
        return []
    side = max(1, int(len(colours) ** 0.5) + 1)
    square = Image.new("RGB", (side, side))
    square.putdata(colours + [(0, 0, 0)] * (side * side - len(colours)))
    reduced = square.quantize(colors=count, method=Image.MEDIANCUT).convert("RGB")
    histogram = {}
    reduced_data = reduced.tobytes()
    for index in range(len(colours)):
        rgb = (reduced_data[index * 3], reduced_data[index * 3 + 1], reduced_data[index * 3 + 2])
        histogram[rgb] = histogram.get(rgb, 0) + 1
    total = len(colours)
    return [{"hex": "#%02x%02x%02x" % rgb, "share": n / total,
             "rgb": list(rgb)}
            for rgb, n in sorted(histogram.items(), key=lambda kv: -kv[1])]


def outline_mask(image):
    """Silhouette mask: every pixel that is not transparent.

    This used to keep only dark pixels, on the assumption that a line drawing is
    mostly outlines. That is true of a pure contour sketch and false of everything
    else - a flat-coloured character is one opaque mass with a thin dark edge, so a
    luminance threshold returns scattered fragments instead of a shape. Testing it
    on a coloured reference produced 12361 ink pixels that were not even
    8-connected: the traced boundary collapsed to a single point.

    The silhouette is what a sprite actually needs, and it is well defined for both
    styles: anything with alpha above zero is the animal.
    """
    width, height = image.size
    data = image.tobytes()
    mask = bytearray(width * height)
    for index in range(width * height):
        if data[index * 4 + 3] > 128:
            mask[index] = 1
    return mask, width, height


def edge_mask(image, threshold=110, alpha=128):
    """Just the dark contour, for art that is genuinely a line drawing.

    Kept separate from outline_mask because the two answer different questions: one
    is the silhouette to fill, this is the stroke to draw on top of it.
    """
    width, height = image.size
    data = image.tobytes()
    mask = bytearray(width * height)
    for index in range(width * height):
        r, g, b, a = data[index * 4:index * 4 + 4]
        if a < alpha:
            continue
        if (r * 299 + g * 587 + b * 114) // 1000 < threshold:
            mask[index] = 1
    return mask, width, height


def trace(mask, width, height, budget):
    """Traces the outer boundary of the ink as a single closed polyline.

    The earlier version took the first and last ink run of every row and joined
    them, which only works for one convex blob: an animal with ears, legs or a tail
    has several runs per row, and joining the outermost pair of each row cut
    straight across those gaps - the points collapsed into one corner.

    This walks the boundary instead, so separate features stay separate, and then
    thins the result to the point budget by keeping the corners where direction
    changes most.
    """
    ys, xs = [], []
    for y in range(height):
        row = mask[y * width:(y + 1) * width]
        for x, on in enumerate(row):
            if on:
                ys.append(y)
                xs.append(x)
    if not xs:
        return []

    # Work on the ink's bounding box so the walk is not slowed by empty margins.
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    box_w, box_h = x1 - x0 + 1, y1 - y0 + 1
    local = bytearray(box_w * box_h)
    for y, x in zip(ys, xs):
        local[(y - y0) * box_w + (x - x0)] = 1

    # Moore-neighbour boundary walk. The search for the next boundary pixel starts
    # from the neighbour we arrived from and sweeps clockwise, which is what keeps
    # the walk hugging the inside of the contour instead of cutting across it.
    #
    # Everything below works in bounding-box coordinates. The first version mixed
    # them - it seeded the walk with an absolute start point but tested candidates
    # with `filled`, which is relative - so almost every step missed, the walk
    # closed a five-pixel loop near the origin, and the traced outline came out as a
    # diamond instead of an animal.
    neighbours = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]

    def filled(x, y):
        if x < 0 or y < 0 or x >= box_w or y >= box_h:
            return False
        return local[y * box_w + x] == 1

    start = min(((x - x0, y - y0) for y, x in zip(ys, xs)), key=lambda p: (p[1], p[0]))
    came_from = (start[0], start[1] - 1)
    contour = [start]
    current = start
    for _ in range(box_w * box_h * 4):
        step = (came_from[0] - current[0], came_from[1] - current[1])
        index = neighbours.index(step) if step in neighbours else 0
        moved = False
        for offset in range(1, 9):
            nxt = neighbours[(index + offset) % 8]
            candidate = (current[0] + nxt[0], current[1] + nxt[1])
            if filled(*candidate):
                # The pixel we swept past becomes the new 'came from', so the next
                # sweep begins where this one let off.
                prev = neighbours[(index + offset - 1) % 8]
                came_from = (current[0] + prev[0], current[1] + prev[1])
                current = candidate
                contour.append(current)
                moved = True
                break
        if not moved or current == start:
            break
    if len(contour) > 1 and contour[-1] == start:
        contour = contour[:-1]          # the walk returns to the start; drop the repeat
    if not contour:
        return []

    # Thin to the budget by keeping whichever points turn the sharpest, which is
    # where the shape's corners are; a smooth run of boundary is redundant.
    if len(contour) > budget:
        def turn(index):
            before = contour[(index - 3) % len(contour)]
            here = contour[index]
            after = contour[(index + 3) % len(contour)]
            ax, ay = here[0] - before[0], here[1] - before[1]
            bx, by = after[0] - here[0], after[1] - here[1]
            return abs(ax * by - ay * bx)

        ranked = sorted(range(len(contour)), key=lambda i: -turn(i))[:budget]
        ranked.sort()
        contour = [contour[i] for i in ranked]

    return [(x + x0, y + y0) for x, y in contour]


def to_java(points, width, height, grid=64):
    """Scales traced points into the sprite's 0..64 coordinate space."""
    if not points:
        return ""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    span = max(maxx - minx, maxy - miny) or 1
    scale = (grid - 6) / span
    out = []
    for x, y in points:
        nx = 3 + (x - minx) * scale
        ny = 3 + (y - miny) * scale
        out.append((round(nx, 1), round(ny, 1)))
    body = "path.moveTo(%sf,%sf);" % out[0]
    for nx, ny in out[1:]:
        body += "path.lineTo(%sf,%sf);" % (nx, ny)
    body += "path.close();"
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("image")
    parser.add_argument("--points", type=int, default=220)
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    path = Path(args.image)
    if not path.exists():
        print("找不到 %s" % path)
        return 1

    image = load(path, args.size)
    colours = palette(image)
    mask, width, height = outline_mask(image)
    ink = sum(mask)
    points = trace(mask, width, height, args.points)
    java = to_java(points, width, height)

    report = {
        "file": str(path),
        "size": list(image.size),
        "ink_ratio": ink / (width * height),
        "palette": colours,
        "points": len(points),
        "java": java,
    }
    if args.json:
        print(json.dumps(report, indent=1, ensure_ascii=False))
    else:
        print("文件      %s" % path)
        print("尺寸      %dx%d" % image.size)
        print("墨迹占比  %.1f%%" % (report["ink_ratio"] * 100))
        print("主色      " + "  ".join("%s %.0f%%" % (c["hex"], c["share"] * 100) for c in colours[:6]))
        print("轮廓点    %d" % report["points"])
        print()
        print(java[:600] + ("..." if len(java) > 600 else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
