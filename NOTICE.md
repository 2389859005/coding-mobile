# 第三方声明与归属

本项目是社区开发的桌面 AI 客户端辅助工具，与 OpenAI、Anthropic、DeepSeek 无隶属关系。OpenAI、Codex、Claude、DeepSeek、Cloudflare、Tailscale 等名称属于相应权利人。

当前仓库包含本地适配代码、原生界面、构建脚本与使用文档，构建仍复用指定上游安装包中的组件。原有许可证与第三方声明继续保留；第三方组件各自的许可证不会因仓库内容调整而改变。

- 网关基于 [try2love/codex-mobile-bridge](https://github.com/try2love/codex-mobile-bridge)，MIT，见 [原许可证](licenses/codex-mobile-bridge-LICENSE)。
- 本项目使用的原始 Windows EXE 与 Android APK 来自 [Abyxs/codex-mobile-assistant](https://github.com/Abyxs/codex-mobile-assistant)，该项目是上述 codex-mobile-bridge 的二次开发版本。感谢其作者提供的开发成果与安装包。
- Project Nayuki QR Code generator，MIT，见 [许可证](licenses/qrcodegen-LICENSE)。
- markdown-it、KaTeX、markdown-it-texmath，MIT，原许可分别保存在 `licenses/`。
- Android 扫码使用 ZXing Android Embedded、ZXing Core、AndroidX，Apache-2.0，见 [Android 第三方声明](licenses/Android-THIRD-PARTY-NOTICES.txt)。
- Cloudflare cloudflared，Apache-2.0，见 [许可证](licenses/cloudflared-LICENSE)。
- Windows 包含 Python、CustomTkinter、Pillow、Paramiko、Cryptography、bcrypt、PyNaCl、cffi 等运行依赖；各自许可证随分发包元数据保留。PyInstaller 的许可证含构建产物分发例外。

- 内置 Tailscale 网络组件及依赖见 [网络组件声明](packaging/tailnet/THIRD_PARTY_NOTICES.txt)；应用图标与客户端标识见 [图标声明](packaging/android/assets/NOTICE.md)。

本应用不包含 Claude Desktop、Codex Desktop 或 DeepSeek Harness 程序，不提供 API 密钥、模型账号或模型授权。
