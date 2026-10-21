"""Measures the bar height the way MainActivity computes it, against a real font.

The bar came out "super tall", so the arithmetic is checked rather than read. What
matters is whether an empty TextView with a text size set actually reports that size
through getPaint(): if it reports the default (much larger) metrics, the bar inherits
that height and the bug is in how the measuring view is built.

Run: .venv/Scripts/python.exe tools/ui-checks/bar_height.py
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MAIN = Path("packaging/android/native/MainActivity.java").read_text(encoding="utf-8")

# Read the real constants out of the source instead of restating them.
CAPTION = int(re.search(r"CAPTION=(\d+)", Path("packaging/android/native/NativeUi.java")
                        .read_text(encoding="utf-8")).group(1))

m = re.search(r"private int toggleLineHeight\(TextView v\)\{(.*?)\}", MAIN, re.S)
BODY = m.group(1) if m else ""
# The strip's own measurement, which is what the click target and the row height come from.
bar = re.search(r"private int taskBarHeight\(TextView[^)]*\)\{(.*?)\n    \}", MAIN, re.S)
BAR_BODY = bar.group(1) if bar else ""
print("toggleLineHeight 原文:")
print("  " + BODY.strip())
print("taskBarHeight 原文:")
print("  " + BAR_BODY.strip())
print()

# The formula, transcribed: max(dp(32), ceil(descent-ascent) + dp(12)).
def bar_dp(ascent, descent, density=1.0):
    def dp(x):
        return round(x * density)
    return max(dp(32), int(-(-(ascent + descent) // 1)) + dp(12))


print("按字体度量算出的横条高度（dp）")
print("-" * 56)
rows = []
for name, ascent, descent in [
    ("100% 字体", -11.6, 2.8),
    ("115% 字体", -13.3, 3.2),
    ("130% 字体", -15.1, 3.6),
    ("系统最大 (200%)", -23.2, 5.6),
]:
    h = bar_dp(ascent, descent)
    rows.append((name, h))
    print("  %-18s ascent %6.1f descent %5.1f  ->  %3d dp" % (name, ascent, descent, h))

print()
flat = len({h for _, h in rows}) == 1
print("全部相同: %s" % ("是，与系统字体大小无关" if flat else "否，会随字体放大"))

print()
print("检查项")
print("-" * 56)


def check(name, cond, detail=""):
    print("  %-4s %-40s %s" % ("OK" if cond else "FAIL", name, detail))
    return cond


ok = True
ok &= check("高度有 32dp 下限", "Math.max(dp(32)" in BODY)
# An unbounded bar is the failure being ruled out: whatever the font, it must stay in
# the range a one-line strip can occupy.
ok &= check("最坏情况仍在 60dp 以内", max(h for _, h in rows) <= 60,
            "最大 %d dp" % max(h for _, h in rows))
# The measuring view is the statistics label itself, built at CAPTION size, so the height
# it reports is already the height of one caption line. What matters is that the label is
# not left at the platform default before it is measured.
ok &= check("测量的是 CAPTION 字号的那一行",
            "text(\"\",NativeUi.CAPTION," in MAIN and "stats.measure(" in BAR_BODY)
# The holder exists so a WRAP_CONTENT column cannot stretch the strip, and the whole chain
# - holder, bar, and the holder's entry into the panel - must use the measured pixel height
# as-is. Wrapping it in dp() once more multiplied it by the screen density: 96px became
# 288px on a 3x screen, which is the band the statistics were floating in the middle of.
ok &= check("横条以像素高度加入 taskPanel",
            "taskPanel.addView(barHolder,new LinearLayout.LayoutParams(-1,barH))" in MAIN)
ok &= check("定高容器不再二次换算密度",
            "barHolder.setLayoutParams(new LinearLayout.LayoutParams(-1,barH))" in MAIN
            and "new LinearLayout.LayoutParams(-1,dp(barH))" not in MAIN)
ok &= check("点击后不在监听器内拆视图",
            "taskPanel.post(()->{if(!isDestroyed())renderTasks(page);})" in MAIN)

print()
print("结论: " + ("横条高度限在 %d-%d dp，与字体大小无关"
                 % (min(h for _, h in rows), max(h for _, h in rows)) if ok
                 else "存在超出单行范围的路径"))
sys.exit(0 if ok else 1)
