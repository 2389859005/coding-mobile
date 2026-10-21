"""Runs the real StatusOverlay.prose() against the same cases as the Python filter.

The two implementations guard the same boundary on opposite sides of the wire, so
they have to agree. Compiling proves neither of them correct, and the desktop
version is not reachable from here, so the Java one is exercised through a small
harness that reflects into the compiled class.
"""
import re
import subprocess
import sys
from pathlib import Path

BUILD = Path(__file__).resolve().parent / ".build"
BUILD.mkdir(exist_ok=True)
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "packaging" / "android" / "native" / "StatusOverlay.java"

HARNESS = r'''
import java.lang.reflect.Method;

public class ProseHarness {
    static Method prose;

    public static void main(String[] args) throws Exception {
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        prose = Class.forName("io.github.abyxs.codingremote.StatusOverlay")
            .getDeclaredMethod("prose", String.class);
        prose.setAccessible(true);
        String[] cases = {
            "plain|我已经把会话页的状态行改好了，现在不会再被截断。",
            "shell|$ npm run build\n构建通过了，可以刷新页面。",
            "fence|看这段：\n```js\nconst a = 1;\n```\n改完之后逻辑更清楚了。",
            "english|Done.\n\n已经改好了，你刷新看看。",
            "path|/Users/me/project/src/main.py\n这个文件已经修好了。",
            "windrive|C:\\work\\app\\main.java\n这里也改好了。",
            "diff|--- a/x.py\n+++ b/x.py\n@@ -1 +1 @@\n路径判断已经修正。",
            "tool|[工具调用] Bash\n\n对话框的样式重做完成了。",
            "onlycmd|$ ls -la\ngit status",
            "empty|",
            "codeonly|function foo(){ return 1; }",
            "activity|正在执行：正在运行测试",
            // Pure stage labels carry no statement and must be dropped. They are all
            // Chinese, so the older "does this line contain Chinese" test let every one of
            // them through - "正在执行：执行结果" appeared verbatim inside the bubble.
            "stageboth|正在执行：执行结果",
            "stageplain|正在执行",
            "stageresult|执行结果",
            "stagetool|工具调用：grep",
            "stagestatus|当前状态：执行中",
            "stagetask|任务状态：已完成",
            // A stage word followed by real content is a statement, not a label.
            "stagemixed|正在执行：把页面渲染出来",
            "stageread|正在读取 src/MainActivity.java",
            "stagebuild|构建中：编译 Android 界面",
            "stagetests|测试通过：135 项",
            "mixed|我先看一下：\n$ cat main.py\n看完了，问题在第三行。\n```py\nx = 1\n```\n已经改好了。"
        };
        for (String entry : cases) {
            int bar = entry.indexOf('|');
            String name = entry.substring(0, bar);
            String input = entry.substring(bar + 1);
            Object reply = prose.invoke(null, input);
            out.print("ROW " + name + "\t" + String.valueOf(reply).replace("\n", "\\n") + "\n");
        }
    }
}
'''


def build_classpath():
    native = ROOT / "packaging" / "android" / "native"
    classes = BUILD / "prose-classes"
    classes.mkdir(exist_ok=True)
    harness = BUILD / "ProseHarness.java"
    harness.write_text(HARNESS, encoding="utf-8")
    android_jar = Path("D:/android-sdk/platforms/android-36/android.jar")
    sources = [str(p) for p in native.glob("*.java") if p.name != "ProseHarness.java"]
    result = subprocess.run(
        ["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-classpath", str(android_jar), "-d", str(classes)] + sources + [str(harness)],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print(result.stdout[-3000:])
        print(result.stderr[-3000:])
        sys.exit("harness did not compile")
    return classes


def main():
    # The console is GBK on this machine and the results are Chinese, so the
    # stream is reconfigured rather than left to fail on the first repr().
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    classes = build_classpath()
    android_jar = "D:/android-sdk/platforms/android-36/android.jar"
    # -Dfile.encoding / -Dstdout.encoding: the harness prints Chinese, and the
    # Windows default code page mangles it before Python ever sees the bytes.
    run = subprocess.run(["java",
                          "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                          "-cp", str(classes) + ";" + android_jar + ";" + str(classes),
                          "ProseHarness"], capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    if run.returncode != 0:
        print(run.stdout[-2000:])
        print(run.stderr[-2000:])
        sys.exit("harness did not run")

    expected = {
        "plain": "我已经把会话页的状态行改好了，现在不会再被截断。",
        "shell": "构建通过了，可以刷新页面。",
        "fence": "看这段：\n改完之后逻辑更清楚了。",
        "english": "已经改好了，你刷新看看。",
        "path": "这个文件已经修好了。",
        "windrive": "这里也改好了。",
        "diff": "路径判断已经修正。",
        "tool": "对话框的样式重做完成了。",
        "onlycmd": "",
        "empty": "",
        "codeonly": "",
        "activity": "正在执行：正在运行测试",
        # Pure stage labels: nothing left once the wording is gone.
        "stageboth": "",
        "stageplain": "",
        "stageresult": "",
        "stagetool": "",
        "stagestatus": "",
        "stagetask": "",
        # Stage word plus real content: the statement survives intact.
        "stagemixed": "正在执行：把页面渲染出来",
        "stageread": "正在读取 src/MainActivity.java",
        "stagebuild": "构建中：编译 Android 界面",
        "stagetests": "测试通过：135 项",
        "mixed": "我先看一下：\n看完了，问题在第三行。\n已经改好了。",
    }

    failures = 0
    seen = {}
    for line in run.stdout.splitlines():
        if not line.startswith("ROW "):
            continue
        name, _, value = line[4:].partition("\t")
        seen[name] = value.replace("\\n", "\n")

    for name, want in expected.items():
        got = seen.get(name, "<missing>")
        if got == want:
            print("OK    %-10s %r" % (name, got))
        else:
            failures += 1
            print("FAIL  %-10s" % name)
            print("      得到 %r" % got)
            print("      期望 %r" % want)

    print()
    print("失败 %d / %d" % (failures, len(expected)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
