"""Checks for the floating-bubble prose filter.

The overlay shows one line of the assistant's reply, so this is the boundary
between "useful summary" and "shell transcript". Each case states what the
operator would expect to read in the bubble.
"""
import unittest

from coding_remote.native_features import NativeFeatures


class ReplyOnlyTests(unittest.TestCase):
    def test_plain_chinese_survives(self):
        text = "我已经把会话页的状态行改好了，现在不会再被截断。"
        self.assertEqual(NativeFeatures.reply_only(text), text)

    def test_shell_command_is_dropped(self):
        text = "$ npm run build\n构建通过了，可以刷新页面。"
        self.assertEqual(NativeFeatures.reply_only(text), "构建通过了，可以刷新页面。")

    def test_code_fence_is_dropped_but_surrounding_prose_kept(self):
        text = "看这段：\n```js\nconst a = 1;\n```\n改完之后逻辑更清楚了。"
        self.assertEqual(NativeFeatures.reply_only(text), "看这段：\n改完之后逻辑更清楚了。")

    def test_english_only_line_is_dropped(self):
        text = "Done.\n\n已经改好了，你刷新看看。"
        self.assertEqual(NativeFeatures.reply_only(text), "已经改好了，你刷新看看。")

    def test_bare_path_is_dropped(self):
        text = "/Users/me/project/src/main.py\n这个文件已经修好了。"
        self.assertEqual(NativeFeatures.reply_only(text), "这个文件已经修好了。")

    def test_diff_lines_are_dropped(self):
        text = "--- a/x.py\n+++ b/x.py\n@@ -1 +1 @@\n路径判断已经修正。"
        self.assertEqual(NativeFeatures.reply_only(text), "路径判断已经修正。")

    def test_tool_banner_is_dropped(self):
        text = "[工具调用] Bash\n\n对话框的样式重做完成了。"
        self.assertEqual(NativeFeatures.reply_only(text), "对话框的样式重做完成了。")

    def test_command_only_input_yields_nothing(self):
        self.assertEqual(NativeFeatures.reply_only("$ ls -la\ngit status"), "")

    def test_empty_input_yields_nothing(self):
        self.assertEqual(NativeFeatures.reply_only(""), "")

    def test_code_only_input_yields_nothing(self):
        self.assertEqual(NativeFeatures.reply_only("function foo(){ return 1; }"), "")

    def test_none_input_yields_nothing(self):
        self.assertEqual(NativeFeatures.reply_only(None), "")

    def test_long_reply_is_truncated_from_the_tail(self):
        text = "开" * 400
        got = NativeFeatures.reply_only(text)
        self.assertEqual(len(got), 240)
        self.assertEqual(got, text[-240:])


class ProgressTextTests(unittest.TestCase):
    def test_a_step_row_is_skipped_in_favour_of_the_reply(self):
        messages = [
            {"role": "assistant", "text": "已经在会话页把状态行修好了。", "kind": "text"},
            {"role": "assistant", "text": "$ python build.py", "kind": "tool"},
        ]
        self.assertEqual(NativeFeatures.progress_text(messages), "已经在会话页把状态行修好了。")

    def test_only_tool_rows_fall_back_to_the_placeholder(self):
        messages = [{"role": "assistant", "text": "$ ls", "kind": "tool"}]
        self.assertEqual(NativeFeatures.progress_text(messages), "正在思考…")

    def test_no_messages_gives_the_placeholder(self):
        self.assertEqual(NativeFeatures.progress_text([]), "正在思考…")

    def test_activity_title_becomes_an_execution_line(self):
        messages = [{"role": "activity", "title": "正在运行测试"}]
        self.assertEqual(NativeFeatures.progress_text(messages), "正在执行：正在运行测试")


if __name__ == "__main__":
    unittest.main()
