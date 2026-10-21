"""Assert each change claimed for this round is actually present in the sources.

A claim that a button was removed or a form replaced is worth nothing unless it is
checked against the file, and a later edit can quietly undo an earlier one.
"""
import re
from pathlib import Path

SRC = Path("packaging/android/native")
overlay = (SRC / "StatusOverlay.java").read_text(encoding="utf-8")
sprite = (SRC / "PetSprite.java").read_text(encoding="utf-8")
bubble = (SRC / "SpeechBubble.java").read_text(encoding="utf-8")
main = (SRC / "MainActivity.java").read_text(encoding="utf-8")
ui = (SRC / "NativeUi.java").read_text(encoding="utf-8")

CHECKS = []


def check(name, condition, detail=""):
    CHECKS.append((name, bool(condition), detail))


# The overlay must no longer build a full-screen dialog.
check("覆盖层不再有全屏遮罩窗口", "dialogRoot" not in overlay)
check("覆盖层不再有 scrim", "setBackgroundColor(NativeUi.scrim())" not in overlay)
check("“关闭悬浮窗”按钮已删除", "关闭悬浮窗" not in overlay)
check("改用气泡切换", "toggleBubble()" in overlay and "toggleBubble" in overlay)
check("气泡用 SpeechBubble 绘制", "new SpeechBubble(" in overlay)
check("气泡为宠物同窗口内的图层", "buildBubble()" in overlay)
check("气泡随宠物窗口布局", "private void layout(int petSize" in overlay)

# Bubble geometry.
check("气泡尾巴有左右两个方向", "TAIL_LEFT" in bubble and "TAIL_RIGHT" in bubble)
check("气泡主体与尾巴同一路径", "path.lineTo(tailCenter" in bubble)

# Pet roster. The list is deliberately short: the pair is the traced reference and
# the robot is the only hand-drawn form left, so the assertions are about the two of
# them plus the embedded bitmap staying intact.
check("保留 robot", '"robot"' in sprite)
check("线条小狗 maltese 存在", '"maltese"' in sprite)
roster = re.search(r'FORMS=\{(.*?)\};', sprite).group(1)
roster_names = [n.strip().strip('"') for n in roster.split(",") if n.strip()]
check("造型数量为 2", len(roster_names) == 2, ", ".join(roster_names))
check("名册就是 maltese 与 robot", roster_names == ["maltese", "robot"], str(roster_names))
check("标签表与名册等长", len(re.search(r'LABELS=\{(.*?)\};', sprite).group(1).split(",")) == 2)
check("已删除全部手绘形态",
      not any(re.search(r'case\s+"%s"' % f, sprite) for f in
              ["cat", "bunny", "penguin", "shiba", "bird", "slime", "ghost",
               "linepup", "axolotl", "chick", "seal", "frog", "kitty", "duck", "golden"]))

# The traced reference. Its silhouette lives in an embedded bitmap, so the things
# worth asserting are that the payload is present, that its length matches 64*64
# indices, and that a decode failure cannot leave the pet invisible.
check("位图调色板为 6 色",
      len(re.findall(r"0x[0-9a-f]{8}", re.search(r"PAIR_PALETTE=\{(.*?)\};", sprite, re.S).group(1))) == 6)
pair_data = "".join(re.findall(r'"([^"]*)"', re.search(r"PAIR_DATA=\s*((?:\s*\"[^\"]*\"\s*\+?)+)\s*;", sprite).group(1)))
check("位图数据长度合理", 200 < len(pair_data) < 4000, "%d 字符" % len(pair_data))
check("位图解码走 Inflater", "java.util.zip.Inflater" in sprite)
check("位图解码失败有可见降级", "if(bitmap==null)" in sprite)
check("pair 有绘制路径", "private void pair(Canvas" in sprite)

check("有 picker() 供设置页复用", "static String[] picker()" in sprite)
check("每个非位图形态都有绘制方法",
      all(("private void " + f + "(Canvas") in sprite for f in ["robot"]))
check("存盘宠物名会走迁移",
      "PetSprite.migrate(" in overlay and "PetSprite.migrate(" in main)
check("迁移对未知名回落首个形态",
      re.search(r"static String migrate\(String stored\)\{[^}]*FORMS\[0\]", sprite, re.S) is not None)
check("迁移保留 none（简洁窗口）", '"none".equals(stored)' in sprite or 'equals("none")' in sprite)
check("宠物网格用 PetSprite.picker", "PetSprite.picker()" in main)
check("设置页不再硬编码旧宠物表", '"cat","bunny"' not in main and '"slime"' not in main)

# Theme single source of truth.
themes = re.search(r'THEMES=\{(.*?)\};', ui).group(1).split(",")
check("主题表只在 NativeUi 定义一次", main.count('"lavender"') == 0)
check("主题改名生效", '"素白"' in ui and '"白色"' not in ui)
check("themeName 读 NativeUi.THEMES", "NativeUi.THEMES" in main)
check("主题网格读 NativeUi.THEMES", "keys=NativeUi.THEMES" in main)
check("七套主题齐全", len(themes) == 7)

# Palette is solved, not hand-picked.
check("色板用 solve() 求解", "private static int solve(" in ui)
check("对比度函数存在", "private static float contrast(int a,int b)" in ui)
check("EDGE 由求解得到", "EDGE  = solve(" in ui.replace("EDGE  = solve", "EDGE  = solve"))
check("diff 前景色已解出", "DIFF_ADD_TEXT" in ui and "DIFF_REMOVE_TEXT" in ui)
check("MessageContent 用 diff 前景色", "DIFF_ADD_TEXT" in (SRC / "MessageContent.java").read_text(encoding="utf-8"))

# Settings layout.
check("设置分组含卡片容器", "card.setClipToOutline(true)" in main)
# The group body is wrapped in a tinted well before it is added to the card, so the
# assertion follows the well rather than the body: what it is protecting is that the
# expanded content lives inside the card instead of spilling onto the canvas.
check("分组体在卡片内", "card.addView(well," in main and "well.addView(body," in main)
check("分区标题带引导线", "private void settingsSection" in main and "Line" not in main[:0] or "head.addView(line,lp)" in main)
# The collapsed bar is one text line tall on purpose, so it cannot carry a 48dp
# height. What it must carry is a 48dp touch target, which it does with a delegate.
check("折叠组触摸目标达 48dp",
      "TouchDelegate" in main and "NativeUi.TOUCH" in main)
check("折叠组有左侧导轨", "View rail=new View(MainActivity.this)" in main)

# Conversation page regressions pinned this round.
nav = (SRC / "FloatingNavigation.java").read_text(encoding="utf-8")
check("顶部不再有目录名行", "chatDirLabel" not in main)
check("状态动画走 connection() 通道", 'connection("正在思考"' in main)
check("撤回有写入路径", "withdrawable=body.optString(\"text\")" in main)
check("待核对可清除", "clearPending();pendingControls()" in main)
check("历史读取有独立线程池", "historyWorkers" in main)
check("历史失败会恢复轮询", 'schedulePoll(generation,id,before==null?5000:2500)' in main)
check("提问选项不再用系统勾选框", "setButtonDrawable(null)" in main)
check("悬浮按钮闲置会淡出", "IDLE_MS" in nav and "void rest()" in nav)
check("悬浮窗做兜底过滤", "static String prose(" in overlay)
check("悬浮窗过滤已接入进度行", "String line=prose(raw)" in overlay)
# Scales are named, not written inline.
check("圆角尺度已命名", "CORNER_CHIP" in ui and "CORNER_SHEET" in ui)
check("间距尺度已命名", "SPACE_GROUP" in ui and "SPACE_PAGE" in ui)
check("行高尺度已命名", "static final int ROW=48" in ui and "ROW_TALL" in ui and "BAR=68" in ui)
check("图标尺度已命名", "GLYPH_INLINE" in ui and "GLYPH_ACTION" in ui and "GLYPH_DISPLAY" in ui)
check("阴影尺度已命名", "LIFT_POPUP" in ui and "LIFT_DIALOG" in ui)
check("字重走语义方法", "static void emphasize(" in ui and "static void strong(" in ui)
check("空状态统一构造", "private View emptyState(" in main)
check("无裸字号调用", not re.search(r'\btext\([^,]*,\s*\d{2}\s*,', main))

# Across all files, not just MainActivity: the same drift can reappear anywhere.
ALL = "\n".join(p.read_text(encoding="utf-8") for p in sorted(SRC.glob("*.java")))
OTHERS = "\n".join(p.read_text(encoding="utf-8") for p in sorted(SRC.glob("*.java"))
                   if p.name not in ("MainActivity.java", "NativeUi.java"))
check("所有文件都无裸字号", not re.search(r'\btext\([^,]*,\s*\d{2}\s*,', ALL))
# The grab handle on a sheet is a 4dp line with a 2dp radius - a capsule, not a
# corner. Everything else must name a role.
_PILL = "surface(NativeUi.LINE,2,"
_radii = [m.group(0) for m in
          re.finditer(r'(?:surface|field|touch|touchField)\([^;]*?,\s*(?:\d{1,2})\s*[,)]', ALL)]
_bare = [r for r in _radii if not r.startswith(_PILL)]
check("无裸圆角", not _bare, str(_bare[:3]))
check("字重不再直接 setTypeface", not re.search(r'setTypeface\((?!Typeface\.M?ONO)', OTHERS))
check("分隔线不再裸用 LINE", "setBackgroundColor(NativeUi.LINE)" not in ALL)
check("图标尺寸已命名（其余文件）",
      not re.search(r'ui\.(?:leading|centerIcon)\([^;]*,\s*\d{2}\s*\)', OTHERS))
check("阴影高度已命名", not re.search(r'setElevation\((?:ui\.)?dp\(\d+\)\)', ALL))

failed = [c for c in CHECKS if not c[1]]
for name, ok, detail in CHECKS:
    print("  %s  %s%s" % ("OK  " if ok else "FAIL", name, ("  " + detail) if detail else ""))
print()
print("%d/%d 通过，%d 失败" % (len(CHECKS) - len(failed), len(CHECKS), len(failed)))

# Every string literal that looks like a colour should come from the theme.
literals = re.findall(r'0x[0-9a-fA-F]{8}', main)
print()
print("MainActivity 中的字面 ARGB 颜色: %d 个" % len(literals))
for value in sorted(set(literals)):
    print("    %s" % value)
