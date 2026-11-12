# 设置页参考改造 · 2.2.2

日期：2026-11-07。最终连接方案为扫码配对：临时 HTTPS 和原有 Tailscale 永久绑定。此前试做的手机 SSH、局域网和自定义入口已从产品移除，版本保持 2.2.2。

## 参考来源

- `D:/download/vscodetest/codex-mobile-bridge` 的 `SettingsPage.java`、`SettingsUi.java`：原生分组行与分隔线。
- `D:/download/vscodetest/dsh-Go` 的 `settings_screen.dart`、`settings_widgets.dart`、`docs/design/settings-ia.md`：按用户意图分组、搜索、当前值和详情导航。

手机设置以本项目原生控件独立实现；没有复制 Flutter 实现、品牌或保留吉祥物素材。保留既有许可证与来源声明。

## 实现

`SettingsScreen.java` 提供可搜索的设置首页、圆角分组、行尾当前值和普通详情页。连接、外观与交互、通知与宠物、电脑按用途分组；“应用”和“存储与关于”入口按后续要求删除。进入详情后返回设置首页，避免把全部开关堆在一个长页面。

浅色、深色与跟随系统使用同一套字号、留白、图标、输入框和控件样式。旧颜色主题网格已删除。手机列表固定显示三个应用图标；会话隐藏底栏，回复旁显示对应应用图标。宠物不再绘制角标。

Windows 保留六页侧栏和扫码首页；移除桌面宠物、标题栏三个操作按钮，以及设置里的“连接与网关”“其他连接方式”“应用”分组。Tailscale 账户、永久设备撤销等保留在应用设置。

## 验证

`ScanSettingsRegression` 在隔离模拟器验证实际应用图标、会话底栏隐藏、长按 Claude / Codex 用户消息编辑、草稿恢复、设置搜索及详情返回。`GatewayRegression` 验证二维码格式、身份校验、故障代理与错误服务拒绝。真实临时公网入口已验证 HTTP POST 配对。

测试不修改实体手机或个人会话。后续已定位并修复 Windows 端口复用冲突；安装包和最终检查以 [修订记录](revision-2.2.2.md) 与 [版本记录](releases.md) 为准。
