"""Dump the palette by running the real Java, not a reimplementation.

NativeUi depends on android.graphics.Color, so this harness stubs the two methods
the colour maths actually calls (luminance and Color.Luminance's coefficient
table) and drives the shipped source. Verifying against a hand-written copy of
the formulas is how the earlier round measured a palette that was not the one
being built.
"""
import math
import re
import shutil
import subprocess
import sys
from pathlib import Path

HARNESS = r'''
package io.github.abyxs.codingremote;

import android.content.Context;

public class PaletteDump {
    public static void main(String[] args) {
        String[] themes = NativeUi.THEMES;
        String[] keys = {"CANVAS","WHITE","SOFT","TINT","LINE","EDGE","INK","MUTED",
                         "GREEN","ACCENT","RED","EMPHASIS","CODE","KEYWORD","STRING",
                         "DIFF_ADD","DIFF_REMOVE","DIFF_ADD_TEXT","DIFF_REMOVE_TEXT",
                         "RING_TRACK","RING_PROGRESS","RING_WARNING","RING_DANGER"};
        for (String theme : themes) {
            NativeUi.theme(theme);
            StringBuilder row = new StringBuilder("THEME " + theme);
            for (String key : keys) {
                row.append(' ').append(key).append('=').append(read(key));
            }
            System.out.println(row);
        }
    }
    static int read(String key) {
        switch (key) {
            case "CANVAS": return NativeUi.CANVAS;
            case "WHITE": return NativeUi.WHITE;
            case "SOFT": return NativeUi.SOFT;
            case "TINT": return NativeUi.TINT;
            case "LINE": return NativeUi.LINE;
            case "EDGE": return NativeUi.EDGE;
            case "INK": return NativeUi.INK;
            case "MUTED": return NativeUi.MUTED;
            case "GREEN": return NativeUi.GREEN;
            case "ACCENT": return NativeUi.ACCENT;
            case "RED": return NativeUi.RED;
            case "EMPHASIS": return NativeUi.EMPHASIS;
            case "CODE": return NativeUi.CODE;
            case "KEYWORD": return NativeUi.KEYWORD;
            case "STRING": return NativeUi.STRING;
            case "DIFF_ADD": return NativeUi.DIFF_ADD;
            case "DIFF_REMOVE": return NativeUi.DIFF_REMOVE;
            case "DIFF_ADD_TEXT": return NativeUi.DIFF_ADD_TEXT;
            case "DIFF_REMOVE_TEXT": return NativeUi.DIFF_REMOVE_TEXT;
            case "RING_TRACK": return NativeUi.ringTrack();
            case "RING_PROGRESS": return NativeUi.ringProgress();
            case "RING_WARNING": return NativeUi.ringWarning();
            case "RING_DANGER": return NativeUi.ringDanger();
        }
        return 0;
    }
}
'''

# A minimal android.graphics.Color that matches the platform's luminance maths.
COLOR_STUB = r'''
package android.graphics;

public class Color {
    public static final int TRANSPARENT = 0;
    public static float luminance(int color) {
        return (float)(0.2126 * lin((color >> 16) & 0xff)
                     + 0.7152 * lin((color >> 8) & 0xff)
                     + 0.0722 * lin(color & 0xff));
    }
    private static double lin(int value) {
        double v = value / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
'''

# Build output lives beside this script, not in .local/.
BUILD = Path(__file__).resolve().parent / ".build"
root = Path("packaging/android/native")
work = BUILD / "palette"
if work.exists():
    shutil.rmtree(work)
(work / "io/github/abyxs/codingremote").mkdir(parents=True)
(work / "android/graphics").mkdir(parents=True)

# Only NativeUi carries the palette; the rest of the app needs the whole Android
# framework, which is not what is being verified here.
text = (root / "NativeUi.java").read_text(encoding="utf-8")
cut = text.find("    private final Context context;")
if cut < 0:
    sys.exit("could not find the Context-dependent tail of NativeUi")
tail = text[cut:]
# contrast() lives after the Context field but only uses Color.luminance, so pull
# it back into the harness copy.
match = re.search(r'    /\*\* WCAG contrast ratio.*?\n    \}\n', tail, re.S)
if not match:
    sys.exit("could not find contrast() in the tail")
text = text[:cut] + match.group(0) + "}\n"
# The palette block uses no Android types at all.
for unwanted in ("import android.view.*;", "import android.widget.*;",
                 "import android.text.*;", "import android.content.*;",
                 "import android.content.res.*;", "import android.util.*;",
                 "import android.graphics.drawable.*;", "import android.os.*;"):
    text = text.replace(unwanted + "\n", "")
for single in ("import android.content.Context;", "import android.content.res.ColorStateList;",
               "import android.view.Gravity;", "import android.os.Build;"):
    text = text.replace(single + "\n", "")
# rippleColor() returns an Android type; it plays no part in the palette.
text = re.sub(r'    private static ColorStateList rippleColor\(\)\{[^\n]*\}\n', "", text)
(work / "io/github/abyxs/codingremote/NativeUi.java").write_text(text, encoding="utf-8")
(work / "android/graphics/Color.java").write_text(COLOR_STUB, encoding="utf-8")
(work / "io/github/abyxs/codingremote/PaletteDump.java").write_text(
    HARNESS.replace("import android.content.Context;\n", ""), encoding="utf-8")

sources = [str(p) for p in work.rglob("*.java")]
out = work / "classes"
out.mkdir()
result = subprocess.run(
    ["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-d", str(out)] + sources,
    capture_output=True, text=True)
if result.returncode != 0:
    print(result.stdout[-4000:])
    print(result.stderr[-4000:])
    sys.exit("harness compile failed")

run = subprocess.run(["java", "-cp", str(out), "io.github.abyxs.codingremote.PaletteDump"],
                     capture_output=True, text=True)
if run.returncode != 0:
    print(run.stdout[-2000:])
    print(run.stderr[-4000:])
    sys.exit("harness run failed")


def luminance(color):
    def lin(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * lin((color >> 16) & 255) + 0.7152 * lin((color >> 8) & 255) \
        + 0.0722 * lin(color & 255)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def on_primary(green):
    return 0xff16201a if luminance(green) > .42 else 0xffffffff


CHECKS = [
    ("INK / CANVAS", "INK", "CANVAS", 4.5),
    ("INK / WHITE", "INK", "WHITE", 4.5),
    ("INK / SOFT", "INK", "SOFT", 4.5),
    ("MUTED / CANVAS", "MUTED", "CANVAS", 4.5),
    ("MUTED / WHITE", "MUTED", "WHITE", 4.5),
    ("MUTED / SOFT", "MUTED", "SOFT", 4.5),
    ("MUTED / TINT", "MUTED", "TINT", 4.5),
    ("EDGE / SOFT", "EDGE", "SOFT", 3.0),
    ("CODE / WHITE", "CODE", "WHITE", 4.5),
    ("KEYWORD / WHITE", "KEYWORD", "WHITE", 4.5),
    ("STRING / WHITE", "STRING", "WHITE", 4.5),
    ("EMPHASIS / WHITE", "EMPHASIS", "WHITE", 4.5),
    ("RED / WHITE", "RED", "WHITE", 4.5),
    ("ACCENT / WHITE", "ACCENT", "WHITE", 4.5),
    ("DIFF_ADD_TEXT / DIFF_ADD", "DIFF_ADD_TEXT", "DIFF_ADD", 4.5),
    ("DIFF_REMOVE_TEXT / DIFF_REMOVE", "DIFF_REMOVE_TEXT", "DIFF_REMOVE", 4.5),
    # The context ring is drawn on the accent fill, so this is where it has to read.
    # A 3dp arc is a graphical object rather than text, which is the 3:1 tier - the
    # user reported it was "not clearly visible", and it was onPrimary() white on a
    # mid green at about 2.4:1, under even that floor.
    ("RING_PROGRESS / GREEN", "RING_PROGRESS", "GREEN", 3.0),
    ("RING_WARNING / GREEN", "RING_WARNING", "GREEN", 3.0),
    ("RING_DANGER / GREEN", "RING_DANGER", "GREEN", 3.0),
    # The track is deliberately faint, but it still has to be distinguishable from
    # nothing at all, or the empty part of the ring disappears.
    ("RING_TRACK / GREEN", "RING_TRACK", "GREEN", 1.2),
]

failures = 0
for line in run.stdout.strip().split("\n"):
    parts = line.split()
    name = parts[1]
    palette = {k: int(v, 16) if v.startswith("0x") else int(v)
               for k, v in (p.split("=") for p in parts[2:])}
    palette = {k: (v if v < 0 else v) for k, v in palette.items()}
    # Java printed signed ints; normalise to unsigned 32-bit.
    palette = {k: v & 0xffffffff for k, v in palette.items()}
    print("=== %s ===" % name)
    print("    画布 #%06x  卡片 #%06x  主色 #%06x  二手 #%06x" % (
        palette["CANVAS"] & 0xffffff, palette["WHITE"] & 0xffffff,
        palette["GREEN"] & 0xffffff, palette["ACCENT"] & 0xffffff))
    for label, fg, bg, floor in CHECKS:
        ratio = contrast(palette[fg], palette[bg])
        ok = ratio >= floor
        if not ok:
            failures += 1
        print("    %-30s %5.2f  %s" % (label, ratio, "OK" if ok else "FAIL"))
    ratio = contrast(on_primary(palette["GREEN"]), palette["GREEN"])
    ok = ratio >= 4.5
    if not ok:
        failures += 1
    print("    %-30s %5.2f  %s" % ("onPrimary / GREEN", ratio, "OK" if ok else "FAIL"))

print()
print("failures: %d" % failures)
