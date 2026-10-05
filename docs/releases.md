# 版本记录

以下为当前修改版的主要变化，本地构建产物位于 `dist/`。构建所用的原始 Windows EXE 与 Android APK 来自 [Abyxs/codex-mobile-assistant 的 Releases](https://github.com/Abyxs/codex-mobile-assistant/releases)。

## 2.0.11 · Windows / Android · 2026-10-06

- 修复 Codex 已运行时启动无动作，补充连接握手与实际状态读取。
- 适配 Claude 命令目录接口的 `options` 参数。
- 悬浮窗仅在回复正文变化时显示沿边框流动的彩色提醒，过滤思考、工具步骤与状态变化。
- 图片直接点击预览；工作目录复用弹窗和缓存，支持长按复制完整路径。
- 归档会话集中到列表末尾的“归档”弹窗，设置页读取安装包版本号。

## 2.0.10 · Android · 2026-10-06

Git 页统一使用应用主题，标题、分组、按钮和提交图同步适配浅色、深色及彩色主题。

## 2.0.9 · Windows / Android · 2026-10-06

同步会话名称与基础信息，整理 Git 页面和弹窗；App 可见时隐藏悬浮窗并停止消息通知。修复图片步骤折叠，持续展示内置网络连接进度。

## 2.0.8 · Windows / Android · 2026-10-06

增加 Codex 插件整包选择、普通／计划／目标模式与 Git 页面；完善 DeepSeek 插件状态，输入区选项改为覆盖展开，悬浮窗增加消息变化动画。

## 2.0.7 · Windows · 2026-10-06

内置网络组件固定在 `%LOCALAPPDATA%\CodingRemoteAssistant\bin\tailnet.exe` 运行，避免不同解压路径导致防火墙重复询问。

## 2.0.6 · Windows / Android · 2026-10-06

Claude 自动连接取得回执后关闭 DevTools 并最小化助手；增加提问与审批提醒、部分文字复制／引用及上下文用量圆环，完善模型与插件设置回读。

## 2.0.0–2.0.5 · 原生版与连接改进 · 2026-10-05 至 2026-10-06

引入 Android 原生界面、七种主题、文件预览、任务通知、悬浮窗和双端内置网络；完善临时／永久独立凭据、重连、任务队列、托盘与 Claude 键盘连接流程。

## 升级提示

Windows 先从设置退出旧后台服务，再运行新版；Android 使用相同签名覆盖安装以保留绑定。建议同时更新两端。构建验证方法见[构建说明](native-build.md)，功能限制见 [README](../README.md#使用边界)。
