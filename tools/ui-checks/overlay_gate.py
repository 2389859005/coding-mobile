"""Traces StatusOverlay.render()'s gate against the states a user actually hits.

"Still does not show" was reported twice, so this pins the behaviour rather than
re-reading the code. The gate has four conditions and the order matters: the state the
user is in when they press the check button is the one the old code refused.

Run: .venv/Scripts/python.exe tools/ui-checks/overlay_gate.py
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SRC = Path("packaging/android/native/StatusOverlay.java").read_text(encoding="utf-8")


def gate(closed=False, app_visible=False, forced=False, overlay_on=False, permitted=False):
    """The Java gate, transcribed. Returns None when the window is drawn."""
    if closed:
        return "悬浮窗已关闭"
    if app_visible and not forced:
        return "App 正在前台，切到其他应用后显示"
    if not overlay_on:
        return "「显示实时任务悬浮窗」未开启"
    if not permitted:
        return "缺少「显示在其他应用上层」权限"
    return None


STATES = [
    ("刚装上，什么都没开",            dict(), "拒绝"),
    ("开了开关，没给权限",            dict(overlay_on=True), "拒绝"),
    ("开了开关，给了权限，在 App 里",  dict(overlay_on=True, permitted=True, app_visible=True), "拒绝"),
    ("开了开关，给了权限，切到桌面",    dict(overlay_on=True, permitted=True), "通过"),
    ("点「立即显示悬浮窗」（在 App 里）", dict(overlay_on=True, permitted=True, app_visible=True, forced=True), "通过"),
    ("点检查但没开开关",              dict(app_visible=True, forced=True), "拒绝"),
    ("点检查但没给权限",              dict(overlay_on=True, app_visible=True, forced=True), "拒绝"),
    ("服务已关闭",                   dict(closed=True, overlay_on=True, permitted=True), "拒绝"),
]

print("悬浮窗门闸状态表")
print("-" * 74)
ok = True
for name, kwargs, want in STATES:
    reason = gate(**kwargs)
    got = "通过" if reason is None else "拒绝"
    mark = "OK" if got == want else "FAIL"
    if got != want:
        ok = False
    print("  %-34s %-4s %-9s %s" % (name, got, mark, reason or ""))

print()


def check(name, condition, detail=""):
    global ok
    ok = ok and condition
    print("  %-4s %-46s %s" % ("OK" if condition else "FAIL", name, detail))


# The two things the fix had to change, asserted against the real source.
check("前台门闸允许 forced 通过",
      re.search(r"AppVisibility\.visible\(\)&&!forced", SRC) is not None)
check("prime() 走 UI 线程",
      re.search(r"static void prime\(Context context\)\{.*?current\.main\.post", SRC, re.S) is not None)
check("prime() 未启动服务时自行拉起",
      "MonitorConnection.start(context.getApplicationContext())" in SRC)
check("无 token 时仍投递空状态给悬浮窗",
      re.search(r"if\(overlay!=null\)overlay\.update\(new JSONObject\(\)\)",
                Path("packaging/android/native/TaskNotificationService.java").read_text(encoding="utf-8")) is not None)
check("renderNow 无论成败都清掉 forced",
      re.search(r"forced=force;.*?finally\{forced=false;\}", SRC, re.S) is not None)
check("空状态能算出「就绪」",
      'if(active.isEmpty())return "就绪";' in SRC)

print()
print("结论: " + ("门闸在全部状态下的行为都符合预期" if ok else "存在不正确的分支"))
sys.exit(0 if ok else 1)
