# 原生版构建

适用于 Windows / Android **2.2.5**。当前仓库提供适配层和 Android 原生界面，构建时仍复用指定上游安装包的运行时、Codex Bridge、扫码与其他组件；不是脱离原包即可重建的完整上游工程。

两份原始安装包均来自 [Abyxs/codex-mobile-assistant](https://github.com/Abyxs/codex-mobile-assistant)，它是 [try2love/codex-mobile-bridge](https://github.com/try2love/codex-mobile-bridge) 的二次开发项目。基础包可从 [Abyxs 项目的 Releases](https://github.com/Abyxs/codex-mobile-assistant/releases) 获取，具体版本与散列要求见下表。

## 环境与输入

| 组件 | 要求 |
| --- | --- |
| 构建主机 | Windows x64，PowerShell。 |
| Python | Python 3.12+ 可运行构建脚本；另外需要 `.local/python313/python.exe`，用于生成与原 EXE 匹配的入口字节码。 |
| Node.js | 用于压缩 Claude 连接器；Terser 固定为 5.44.0，安装在 `.local/minifier/`。 |
| Windows 编译器 | .NET Framework 4 的 `csc.exe`、Windows Forms 与 UIAutomation 程序集。当前脚本使用系统 Framework64 目录。 |
| Android 工具 | JDK 17、Android SDK `platforms/android-36` 与 `build-tools/35.0.0`、NDK r27c（27.2.12479018）；脚本目前固定 SDK 路径为 `D:/android-sdk`。 |
| Apktool | 2.12.1，放在 `.local/build-tools/apktool.jar`。 |
| 内置网络 | Go 1.26.1+；依赖版本固定在 `packaging/tailnet/go.mod` 与 `go.sum`。 |
| 签名材料 | `.local/signing/claude-adapter.p12`，别名 `claude-adapter`，口令文件 `.local/signing/password.txt`。请保留原签名以支持覆盖安装。 |
| 原 Windows 包 | `CodexMobileAssistant-Windows-1.6.23.exe`，SHA-256 必须匹配 `packaging/build_common.py` 的 `EXPECTED_EXE`。 |
| 原 Android 包 | `CodexMobileAssistant-Android-1.0.19.apk`，SHA-256 必须匹配 `packaging/build_common.py` 的 `EXPECTED_APK`。也支持脚本中固定散列的 `.local/native-base-2.0.0.apk`。 |

`.local/` 不进入 Git。获取上述运行时、工具和原包后，将 JDK、Node.js、Go 放入 PATH。若本机 SDK 或编译器位置不同，需要相应调整构建脚本。

## 构建步骤

在仓库根目录执行；示例假定两份上游安装包位于仓库根目录。

```powershell
python -m venv .venv
npm install --prefix .local/minifier --save-exact terser@5.44.0

.\.venv\Scripts\python.exe packaging/build_tailnet.py
.\.venv\Scripts\python.exe packaging/build_native_exe.py --source .\CodexMobileAssistant-Windows-1.6.23.exe
.\.venv\Scripts\python.exe packaging/build_native_apk.py --source .\CodexMobileAssistant-Android-1.0.19.apk
```

先准备表中的工具链与签名材料，再执行命令。核心构建脚本使用 Python 标准库，Python 虚拟环境不会自动下载外部工具链。Windows 包使用原 EXE 内置运行依赖，构建脚本自动编译托盘／键盘组件并压缩连接器。Android 构建包含 Java 编译、D8、zipalign 与签名验证。

仅首次配置签名时，可使用 JDK 的 `keytool` 创建材料；已有签名时不要重新生成。以下命令交互输入口令，之后将同一口令保存在上述本地口令文件中：

```powershell
New-Item -ItemType Directory -Force .local/signing | Out-Null
keytool -genkeypair -keystore .local/signing/claude-adapter.p12 -storetype PKCS12 -alias claude-adapter -keyalg RSA -keysize 3072 -validity 3650 -dname "CN=Local Claude Adapter, OU=Local Build, O=Community, C=CN"
```

输出：

- `dist/CodingRemoteAssistant-Windows-2.2.5.exe`
- `dist/CodingRemoteAssistant-Windows-2.2.5.build.json`
- `dist/CodingRemoteAssistant-Android-2.2.5.apk`

构建脚本输出各安装包 SHA-256；发布前更新 `dist/SHA256SUMS.txt`。原生 Android 构建不接受任意历史 APK。省略 `--source` 时可以使用已保留的固定散列基础包，但最新 APK 不能直接充当该基础包。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests
node tests/test_desktop_file.cjs
node tests/test_claude_startup.cjs
$env:CONNECTOR_TEMPLATE = 'claude_bridge/desktop-console.js'
node tests/test_desktop_file.cjs
node tests/test_claude_startup.cjs
Remove-Item Env:CONNECTOR_TEMPLATE
node tests/test_cdp_direct.cjs
node tests/test_deepseek_host.mjs
```

Python 与 JavaScript 测试覆盖配对、撤销、请求去重、队列、文件隔离及三个后端的适配行为，使用模拟桌面接口。Windows 控件测试需要 `customtkinter`，测试会从仓库产品图标构造临时资产；缺少 GUI 依赖时会跳过。

Android 回归脚本会安装临时测试 APK、修改测试首选项并操作界面，只在独立模拟器中运行。指定实际模拟器序列号：

```powershell
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite RedesignRegression
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite RedesignRegression --theme dark
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite AppearanceRegression --color-style everforest
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite ImageReadRegression
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite OverlayStabilityRegression
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite ScanSettingsRegression
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite LiveWorkspaceRegression
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite FilePreviewRegression
.\.venv\Scripts\python.exe packaging/test_native_live.py --serial emulator-5580
.\.venv\Scripts\python.exe packaging/test_native_ui.py --serial emulator-5580 --suite MonitorRegression
```

可用套件见 `packaging/test_native_ui.py --help`，截图默认输出 `.local/ui-captures/`。`test_native_live.py` 在电脑临时端口建立隔离测试网关，经模拟器 `10.0.2.2` 测试真实 SSE、健康连接无内容轮询、断流恢复、电脑身份缓存隔离，以及无常驻通知的绑定服务和后台悬浮窗气泡更新；只接受模拟器序列号。后台悬浮窗套件需预先授予测试 App 的通知及悬浮窗权限。

Windows 包支持 `--self-check <输出JSON绝对路径>`，检查打包运行时、内嵌源码和图片读取记录，不连接业务会话。`--ui-self-check <输出JSON绝对路径>` 使用临时设置检查四个页面、工作台与通知中心已移除，也不启动业务服务。Android 测试脚本只安装测试 APK，运行前需要先安装本次构建的产品 APK。

自动测试与模拟器结果不代表实体手机、完整跨网络链路或每个桌面版本都已验证。当前功能边界见 [README](../README.md#使用边界)。

## 数据与目录维护

| 路径 | 用途 |
| --- | --- |
| `%LOCALAPPDATA%/CodingRemoteAssistant/` | 助手设置、设备绑定、请求收据、网络身份和客户端集成状态。 |
| `%LOCALAPPDATA%/CodingRemoteAssistant/bin/tailnet.exe` | Windows 内置网络组件的固定运行路径。 |
| 项目下 `.coding-remote/claude/` | Claude 本地 JSON 文件桥接数据。 |
| `.local/signing/` | APK 签名与口令，后续覆盖安装需要保留。 |
| `.local/python313/`、`build-tools/`、`minifier/`、`tailnet/` | 本地构建运行时、工具和网络组件产物。 |
| `.local/native-base-2.0.0.apk` | 散列固定的可选 Android 构建输入。 |
| Android 应用私有目录 | Keystore 加密连接凭据、缓存与内置网络身份。 |

清理历史截图、测试日志、一次性修改脚本及临时解包目录时，保留签名、运行数据、工具链、基础包和仍需交付的安装包。不要把整个 `.local/` 当作可无条件删除的缓存。

`packaging/build_common.py` 提供原生构建共用的原包散列、子进程调用与 EXE 归档解析函数；Windows、Android 构建及 Android 回归入口统一使用该模块。`tests/` 与 `packaging/test_native_ui.py` 是可重复运行的回归测试，不属于一次性测试输出。

## 扫码与原生设置回归

最终 Android 构建已移除 SSH 连接实现和 JSch / EdDSA-Java 依赖；历史 SSH 脚本不属于当前回归入口。`ScanSettingsRegression` 验证图标、无搜索设置导航和用户消息编辑；`test_native_live.py` 验证真实 HTTP 扫码、SSE 与恢复。测试仅指定隔离模拟器，不使用实体手机或个人会话。详见 [修订记录](revision-2.2.3.md)。

Windows 旧 EXE 正在运行时，可用 `--output dist/release-2.2.5/CodingRemoteAssistant-Windows-2.2.5.exe` 构建到独立发布目录。手机当前页面更新的回归：`packaging/test_native_ui.py --serial emulator-5580 --suite MobileUpdateRegression`。

2.2.5 R4 的双端交付目录为 `dist/release-2.2.5-r4/`，其中另附 `SSH连接信息.txt`。字体检查可使用 `--suite AppearanceRegression --font-family serif`；外观测试也验证 1.5／2.5 行会话高度、半行间距、17sp 项目标题、更换主题和字体后的页面恢复，以及每项应用弹窗中的重新连接请求。`MobileUpdateRegression` 验证阅读历史与滚动时模型及停止状态的更新。Windows UI 自检增加窄窗口 SSH 输入框和空提示收起检查；`tests.test_desktop_window` 覆盖 700／780／1100 像素布局和绑定后二维码隐藏。
