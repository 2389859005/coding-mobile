"""Finds view teardown races and self-referencing touch delegates.

Two crash shapes are scanned for here.

The teardown race: a click handler calls a method that starts with removeAllViews() on an
ancestor of the very view whose listener is on the stack.

The self-referencing TouchDelegate: a View is handed a TouchDelegate aimed at itself. The
touch then goes View.onTouchEvent -> TouchDelegate.onTouchEvent -> View.dispatchTouchEvent
-> View.onTouchEvent, forever, and the process dies of StackOverflowError on the first real
finger. This is the shape that actually closed the app when the statistics strip or a step
row was tapped; performClick() skips touch dispatch entirely, so no click-based assertion
can see it. Both delegates must forward to a *different* view - the clicked button, held by
its parent.

Run: .venv/Scripts/python.exe tools/ui-checks/view_teardown_race.py
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SRC = Path("packaging/android/native").glob("*.java")
FILES = {p.name: p.read_text(encoding="utf-8") for p in SRC}

# Bodies that clear a container. A listener reaching one of these inline is suspect.
CLEARERS = re.compile(r"(\w+)\.removeAllViews\(\)")

# A TouchDelegate whose target argument repeats the View it is installed on.
SELF_DELEGATE = re.compile(r"(\w+)\.setTouchDelegate\(\s*new\s+(?:android\.view\.)?TouchDelegate\(([^;]*?)\)\s*\);")


def listeners(text):
    """Yields (line number, method called, whether it is deferred)."""
    for match in re.finditer(r"setOnClickListener\((\w+)->\{(.*?)\}\);", text, re.S):
        body = match.group(2)
        line = text[: match.start()].count("\n") + 1
        deferred = ".post(" in body
        for call in re.finditer(r"(?<![.\w])([a-z]\w{2,})\([^;]*\);", body):
            yield line, call.group(1), deferred


def clears(text, name):
    """The container names method `name` empties, or an empty set.

    Only the head of the body counts. A renderer that clears a container ten lines in,
    behind its own guards, is not the teardown race being looked for; the race is a
    method that starts by emptying a container.

    Returning the container *name* rather than a boolean is what lets the caller check
    the real question: not "does this method clear anything" but "does it clear the
    container the clicked view is inside". renderQueue() empties queuePanel, which is
    unrelated to whatever button was tapped, and reporting that as a crash risk buries
    the one case that matters.
    """
    body = re.search(r"\b%s\s*\([^)]*\)\s*\{(.{0,300})" % re.escape(name), text, re.S)
    return {m.group(1) for m in CLEARERS.finditer(body.group(1))} if body else set()


print("检查：点击监听里就地销毁自己所在的视图")
print("-" * 70)
# The containers that wrap the views users actually tap, and the setters that build the
# listeners. A handler that empties one of these inline is the crash being hunted.
TAPPED_CONTAINERS = {"taskPanel", "inlineOptions", "optionsDismiss"}
issues = 0
checks = 0
for filename, text in FILES.items():
    for line, called, deferred in listeners(text):
        if deferred:
            continue
        cleared = clears(text, called)
        if not (cleared & TAPPED_CONTAINERS):
            continue
        checks += 1
        issues += 1
        print("  FAIL %s:%d  内联调用 %s()，会清空 %s"
              % (filename, line, called, ", ".join(sorted(cleared & TAPPED_CONTAINERS))))

if checks == 0:
    print("  OK   没有内联清空可点击容器的监听器")

print()
print("检查：renderTasks 重建前必须清空 taskPanel")
print("-" * 70)
main = FILES["MainActivity.java"]
m = re.search(r"private void renderTasks\(SessionPage page\)\{(.*?)\n    /\*\*", main, re.S)
# The head is whatever runs before the first nested block ends; the guard lines pushed
# removeAllViews() past a fixed 400-character window, so the whole body is searched and
# the panel's own field name is matched rather than one literal call site.
body = m.group(1) if m else ""
ok = "taskPanel.removeAllViews()" in body
print("  %-4s renderTasks 内清空 taskPanel -> 监听器必须延后调用"
      % ("OK" if ok else "FAIL"))
issues += 0 if ok else 1

print()
print("检查：崩溃点必须延后重建")
print("-" * 70)
bar_ok = "taskPanel.post(()->{if(!isDestroyed())renderTasks(page);})" in main
print("  %-4s 统计条/步骤条点击延后重建" % ("OK" if bar_ok else "FAIL"))
issues += 0 if bar_ok else 1

body_ok = "setOnClickListener(v->{taskExpanded=!taskExpanded;taskRevision=\"\";renderTasks(page);})" not in main
print("  %-4s 没有残留的就地重建写法" % ("OK" if body_ok else "FAIL"))
issues += 0 if body_ok else 1

print()
print("检查：TouchDelegate 不得把触摸转发给持有它的那个视图")
print("-" * 70)
delegates = 0
delegate_issues = 0
for filename, text in FILES.items():
    for match in SELF_DELEGATE.finditer(text):
        holder, args = match.group(1), match.group(2)
        line = text[: match.start()].count("\n") + 1
        target = args.rsplit(",", 1)[-1].strip()
        if not target:
            continue
        delegates += 1
        if target == holder:
            issues += 1
            delegate_issues += 1
            print("  FAIL %s:%d  %s 的委托目标是它自己 -> onTouchEvent 无限递归，首次真实触摸即栈溢出"
                  % (filename, line, holder))
if delegates == 0:
    print("  --   源码里没有 TouchDelegate 可查")
elif delegate_issues == 0:
    print("  OK   %d 处委托都转发给子视图" % delegates)

print()
print("检查：悬浮窗绘制不得让异常逃逸到主线程")
print("-" * 70)
overlay = FILES["StatusOverlay.java"]
guarded = re.search(r"private void render\(\)\{\s*try\{renderUnchecked\(\);", overlay) is not None
print("  %-4s render() 有 try/catch 包裹" % ("OK" if guarded else "FAIL"))
issues += 0 if guarded else 1
inner = re.search(r"catch\(Throwable\s+\w+\)\{\s*panel=null;", overlay) is not None
print("  %-4s 失败记录为 blockReason 而不是抛出" % ("OK" if inner else "FAIL"))
issues += 0 if inner else 1
posted = re.search(r"current\.main\.post\(\(\)->\{\s*try\{", overlay) is not None
print("  %-4s prime() 在主线程投递体内也包了 try" % ("OK" if posted else "FAIL"))
issues += 0 if posted else 1

print()
print("结论: " + ("未发现视图销毁竞态，崩溃路径均有防护" if issues == 0
                 else "%d 处问题仍待修复" % issues))
sys.exit(0 if issues == 0 else 1)
