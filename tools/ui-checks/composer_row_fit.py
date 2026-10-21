"""Checks that the composer's option row fits on one line.

The row is four chips and an attach button inside the phone's width minus the composer's
insets. Three chips carry short fixed labels; the model chip carries whatever name the
computer reported, which can be any length, so it is capped at MainActivity's
optionMaxWidthDp. This checks the arithmetic closes on the narrowest screen the app
supports, which is what "put them on one line" depends on.

Run: .venv/Scripts/python.exe tools/ui-checks/composer_row_fit.py
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MAIN = Path("packaging/android/native/MainActivity.java").read_text(encoding="utf-8")

# Read the cap out of the source rather than restating it, so the check fails if the
# source changes and this file does not.
m = re.search(r"optionMaxWidthDp\s*=\s*(\d+)", MAIN)
CAP = int(m.group(1)) if m else None

ATTACH = 40          # the plus button
ATTACH_MARGIN = 8    # leftMargin on the option scroller
CHIP_GAP = 6         # gap between chips
COMPOSER_INSET = 24  # 12dp on each side
HEIGHT = 40


def width_sp(text, size=11.0):
    """Approximate a label's width in dp. CJK is one em; Latin averages near half."""
    total = 0.0
    for ch in text:
        if ord(ch) > 0x2E80:
            total += 1.0
        elif ch.isupper():
            total += 0.62
        elif ch.isdigit():
            total += 0.56
        elif ch == " ":
            total += 0.28
        else:
            total += 0.52
    return total * size


def chip_width(label, cap=None):
    """Chip width in dp: text plus 9dp padding each side, capped.

    The cap applies to the whole chip, so a capped chip is exactly `cap` wide however
    long its label is.
    """
    inner = width_sp(label) + 9 + 9
    return min(inner, cap) if cap else inner


# The four chips. Three carry a two-character label; the fourth may carry a model name,
# so it is measured at its cap. Four chips only fit if the cap is small enough that the
# worst case - all four at their widest - still closes on the narrowest screen.
FIXED = ["思考", "审批", "插件"]

print("chip 宽度（dp），模型 chip 上限 %s" % CAP)
print("-" * 58)
for label in FIXED:
    print("  %-16s %6.1f   %s" % (label, chip_width(label), "固定两字"))
print("  %-16s %6.1f   %s" % ("模型 · <任意长名>", CAP, "按上限截断"))
print("  %-16s %6.1f" % ("加号按钮", ATTACH))

need = ATTACH + ATTACH_MARGIN + CAP + CHIP_GAP
for label in FIXED:
    need += chip_width(label) + CHIP_GAP
need -= CHIP_GAP

print()
print("一行总需求 %.1f dp（含加号与间距）" % need)
print()
print("各屏幕可用宽度（屏宽减 %.0f dp 内边距）" % COMPOSER_INSET)
print("-" * 58)
ok = True
for name, screen in [("小屏", 320), ("常见", 360), ("常见", 393), ("大屏", 412)]:
    usable = screen - COMPOSER_INSET
    fits = need <= usable
    ok = ok and fits
    print("  %-6s %3d dp  可用 %3d  余量 %+6.1f  %s" %
          (name, screen, usable, usable - need, "OK" if fits else "溢出"))

print()
if CAP is None:
    print("结论: 源码里找不到 optionMaxWidthDp，检查未生效")
elif ok:
    print("结论: 上限 %d dp 下四枚 chip 与加号在最窄 320dp 屏幕排成一行" % CAP)
else:
    print("结论: 上限 %d dp 过宽，需要调小 optionMaxWidthDp（当前余量 %.1f dp）" % (CAP, 320 - COMPOSER_INSET - need))
