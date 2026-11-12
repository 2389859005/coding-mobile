# 2.2.2 连接与界面修订

日期：2026-11-08。Windows 与 Android 版本均保持 2.2.2。

## 扫码连接

两端仅保留扫码配对：临时 HTTPS 和原有 Tailscale 永久绑定。删除局域网、手机 SSH 和自定义入口设置；电脑网关只监听回环地址。已有永久网络身份和绑定可继续恢复。手机移除旧局域网活动线路时，原配置仍保存在加密迁移备份中。

手机先通过 HTTP GET `/api/gateway` 核验电脑助手，再用 HTTP POST `/api/pair` 配对。旧 JSON 二维码与 `/#pair=` 链接均保留；扫码入口限制为 HTTPS 或 Tailscale 地址。二维码携带电脑身份时必须匹配；回执也必须来自同一电脑。普通 HTTP/HTTPS 请求不继承系统代理，内置 Tailscale 使用自己的明确代理。

临时隧道使用独立配置，不继承外部 `TUNNEL_*` / `CLOUDFLARED_*` 环境变量。电脑在显示二维码前核对公网入口与本机助手的身份，拒绝重定向和错误服务。配对没有 WebSocket 升级请求。失败保留原有连接，业务写入不自动重发。

已在本机定位到 8787 端口由其他 Python 服务占用，该服务返回“仅支持 HTTP POST Responses，不支持 WebSocket”。Windows 的端口复用允许助手再次绑定同一端口，却仍将请求送往原服务，导致手机核验失败、临时隧道无法生成二维码。网关现改为独占监听；端口被占用时自动选择空闲端口并保存，临时隧道与 Tailscale 转发使用实际监听端口。同时修复绑定失败时的资源清理异常，以及退出时正在启动的临时隧道残留。手机报错改为提示退出旧助手、打开最新版并刷新扫码，不再要求修改已删除的转发设置。

用户要求停止后续测试之前，已用占用端口场景复现问题，并完成自动换端口、实际 NativeServer、公网 Cloudflare HTTPS 和 POST 配对验证。

## Windows

- 概览恢复扫码首页，保留临时扫码与 Tailscale 永久绑定。
- 侧栏保留概览、工作台、通知中心、账号与模型、查看日志、应用设置六页。
- 删除手机设备、更新与维护独立页面；永久设备撤销保留在应用设置。
- 删除设置里的“连接与网关”“其他连接方式”“应用”整组；彻底退出使用系统托盘菜单。
- 删除桌面宠物及相关设置，删除右上角三个按钮。

## Android

- 删除颜色主题网格，采用统一浅色／深色设计，支持跟随系统。
- 重写设置页：搜索、分组行、当前值、独立详情页与返回导航；删除“应用”分组及“存储与关于”入口。参考来源见 [设置页改造](dsh-go-adaptation-2.2.2.md)。
- 列表底栏固定显示 Claude、Codex、DeepSeek 真实应用图标；会话页隐藏底栏，回复旁使用对应应用图标。
- 宠物不再绘制右上角圆点或数字角标。
- 输入框上方仅保留“执行详情”，有内容时显示。点击原位展开／收起限高面板，不显示“会话状态”“已同步”和连接统计，保留草稿与阅读位置。

## 撤回编辑再发送

Claude / Codex 会话中长按用户消息，选择“撤回编辑再发送”。原文放入输入框，取消会恢复编辑前的草稿。点击发送后才由电脑端撤回该轮及后续消息，并用修改后的内容重新执行；已执行的文件修改不会自动回滚。

正在运行的会话需先停止任务。编辑期间若历史发生变化，服务端拒绝覆盖。请求编号防止重复执行；撤回结果未知时不会继续发送。编辑草稿按电脑与会话保存，独立于普通草稿同步。

Codex 使用桌面自身的编辑操作，保留原附件、工作目录、模型和权限。Claude 使用 `rewindV2` 后发送，收到明确撤回回执才继续；含图片的 Claude 消息暂需在电脑编辑以保留附件。编辑时不支持添加新附件。

## 验证入口

```powershell
.venv/Scripts/python.exe -m unittest discover -s tests
.venv/Scripts/python.exe packaging/test_native_live.py --serial emulator-5580
.venv/Scripts/python.exe packaging/test_native_ui.py --serial emulator-5580 --suite ScanSettingsRegression --theme light
.venv/Scripts/python.exe packaging/test_native_ui.py --serial emulator-5580 --suite ScanSettingsRegression --theme dark
.venv/Scripts/python.exe packaging/test_native_ui.py --serial emulator-5580 --suite StatusLayoutRegression --theme light
.venv/Scripts/python.exe packaging/test_native_ui.py --serial emulator-5580 --suite StatusLayoutRegression --theme dark
```

本次交付按用户要求直接打包，不再运行测试或 Windows 安装包自检。请求前已启动的 Python 回归完成（161 项通过）；最终安装包不以此前的界面测试结果代替验证。版本仍为 `2.2.2`。

Windows 安装包提供 `--self-check <结果.json>` 与 `--ui-self-check <结果.json>`。最终输出、校验值与验证结果见 [版本记录](releases.md)。升级时替换两端安装包，退出旧桌面后台，再启动新版刷新二维码。实体手机未被测试脚本修改。
